package com.lucachinou.client.render;

import com.lucachinou.VibrantsVisuals;
import com.lucachinou.client.VibrantsVisualsOptions;
import com.lucachinou.client.mixin.ChunkSectionsToRenderInvoker;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.lucachinou.client.mixin.PreparedFrameAccessor;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.UniformType;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryStack;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.BindGroupLayouts;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import net.minecraft.client.renderer.SubmitNodeCollection;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/**
 * Directional shadow map rendered from the sun (or moon), the way Bedrock's Vibrant Visuals does it.
 * The opaque terrain is redrawn from the light's point of view into a depth-only target.
 */
public final class ShadowRenderer {
	public static final int RESOLUTION = 4096;
	/** Half-size of the area covered by the shadow map, in blocks. */
	public static final float RADIUS = 96.0F;
	public static final float DEPTH_RANGE = 256.0F;

	private static final RenderPipeline PIPELINE = shadowPipeline(RenderPipelines.TERRAIN_SNIPPET, "pipeline/shadow_terrain");
	private static final RenderPipeline PIPELINE_MULTIDRAW = shadowPipeline(RenderPipelines.MULTIDRAW_TERRAIN_SNIPPET, "pipeline/shadow_terrain_multidraw");

	// Reversed depth: a negative bias pushes the stored depth away from the light, which removes acne on thin geometry
	private static final float SLOPE_BIAS = -2.0F;
	private static final float CONSTANT_BIAS = -4.0F;

	private static final RenderPipeline ENTITY_PIPELINE = RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET)
		.withLocation(VibrantsVisuals.id("pipeline/shadow_entity"))
		.withShaderDefine("ALPHA_CUTOUT", 0.5F)
		.withBindGroupLayout(BindGroupLayouts.SAMPLER1)
		.withColorTargetState(ColorTargetState.DEFAULT)
		.withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true, SLOPE_BIAS, CONSTANT_BIAS))
		.withCull(false)
		.build();

	private static final RenderPipeline TRANSLUCENT_PIPELINE = translucentShadowPipeline(RenderPipelines.TERRAIN_SNIPPET, "pipeline/shadow_translucent_terrain");
	private static final RenderPipeline TRANSLUCENT_PIPELINE_MULTIDRAW = translucentShadowPipeline(RenderPipelines.MULTIDRAW_TERRAIN_SNIPPET, "pipeline/shadow_translucent_terrain_multidraw");
	/** Must match the CasterRects array size in core/shadow.fsh. */
	private static final Identifier[] CASTER_TEXTURES = casterTextures();

	/** Water first (it only marks caustics), then the blocks that tint the light. Order must match core/shadow.fsh. */
	private static Identifier[] casterTextures() {
		java.util.List<String> names = new java.util.ArrayList<>(java.util.List.of(
			"water_still", "water_flow", "slime_block", "honey_block_top", "honey_block_side", "honey_block_bottom", "ice"
		));
		for (String color : new String[]{
			"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
			"light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"
		}) {
			names.add(color + "_stained_glass");
			names.add(color + "_stained_glass_pane_top");
		}
		return names.stream().map(name -> Identifier.withDefaultNamespace("block/" + name)).toArray(Identifier[]::new);
	}
	private static final int CASTERS_UBO_SIZE = 16 * CASTER_TEXTURES.length;
	/** Translucent casters only tint the light, so they get their own (smaller) map holding their color and depth. */
	public static final int TINT_RESOLUTION = 2048;
	private static @Nullable TextureTarget tintTarget;
	private static @Nullable GpuBuffer castersBuffer;

	private static boolean renderingShadowMap;
	private static @Nullable TextureTarget shadowTarget;
	private static @Nullable ProjectionMatrixBuffer projectionBuffer;
	/** Maps camera-relative positions to shadow map clip space, valid when {@link #active} is true. */
	private static final Matrix4f SHADOW_MATRIX = new Matrix4f();
	private static boolean active;
	private static boolean failed;

	private ShadowRenderer() {
	}

	private static RenderPipeline shadowPipeline(final RenderPipeline.Snippet snippet, final String location) {
		return shadowPipelineBuilder(snippet, location).build();
	}

	/** Translucent terrain only casts shadows for the blocks listed in {@link #CASTER_TEXTURES}, water and glass stay see-through. */
	private static RenderPipeline translucentShadowPipeline(final RenderPipeline.Snippet snippet, final String location) {
		return shadowPipelineBuilder(snippet, location)
			.withShaderDefine("TRANSLUCENT_CASTERS")
			.withBindGroupLayout(BindGroupLayout.builder().withUniform("VibrantsShadowCasters", UniformType.UNIFORM_BUFFER).build())
			.withColorTargetState(ColorTargetState.DEFAULT)
			.build();
	}

	private static RenderPipeline.Builder shadowPipelineBuilder(final RenderPipeline.Snippet snippet, final String location) {
		return RenderPipeline.builder(snippet)
			.withLocation(VibrantsVisuals.id(location))
			.withFragmentShader(VibrantsVisuals.id("core/shadow"))
			.withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true, SLOPE_BIAS, CONSTANT_BIAS))
			.withCull(false);
	}

	public static boolean isRenderingShadowMap() {
		return renderingShadowMap;
	}

	public static RenderPipeline shadowPipelineFor(final RenderPipeline pipeline) {
		DepthStencilState depth = pipeline.getDepthStencilState();
		boolean writesDepth = depth != null && depth.writeDepth();
		return !writesDepth && pipeline.getVertexFormatBinding(0) == DefaultVertexFormat.ENTITY ? ENTITY_PIPELINE : pipeline;
	}

	public static boolean isActive() {
		return active;
	}

	public static Matrix4f shadowMatrix() {
		return SHADOW_MATRIX;
	}

	public static @Nullable TextureTarget tintTarget() {
		return tintTarget;
	}

	public static @Nullable GpuTextureView depthView() {
		return shadowTarget == null ? null : shadowTarget.getDepthTextureView();
	}

	/** Direction towards the sun during the day and towards the moon at night. */
	public static Vector3f lightDirection(final SkyRenderState sky) {
		// The light only moves in small steps: a continuously rotating shadow map makes every edge crawl over the texel grid
		float step = (float) Math.toRadians(0.5);
		float sunAngle = Math.round(sky.sunAngle / step) * step;
		return directionFromAngle(sunAngle);
	}

	/** Unstepped direction, for effects that must move smoothly such as cloud shadows. */
	public static Vector3f smoothLightDirection(final SkyRenderState sky) {
		return directionFromAngle(sky.sunAngle);
	}

	private static Vector3f directionFromAngle(final float sunAngle) {
		Vector3f sunDir = new Quaternionf().rotationY((float) (-Math.PI / 2)).rotateX(sunAngle).transform(new Vector3f(0.0F, 1.0F, 0.0F));
		return sunDir.y > -0.05F ? sunDir : sunDir.negate();
	}

	public static void render(final ChunkSectionsToRender sections, final FeatureRenderDispatcher.PreparedFrame featureFrame, final GpuBufferSlice terrainFog) {
		active = false;
		Minecraft minecraft = Minecraft.getInstance();
		CameraRenderState camera = minecraft.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
		SkyRenderState sky = minecraft.gameRenderer.gameRenderState().levelRenderState.skyRenderState;
		if (failed || !VibrantsVisualsOptions.SHADOWS.get() || sky.skybox != DimensionType.Skybox.OVERWORLD) {
			return;
		}

		Vector3f lightDir = lightDirection(sky);
		// Like Bedrock, the light elevation is clamped to 10 degrees so shadows stay usable at sunrise and sunset
		float minElevation = (float) Math.sin(Math.toRadians(10.0));
		if (lightDir.y < minElevation) {
			float horizontal = (float) Math.sqrt(1.0F - minElevation * minElevation);
			float xzLength = (float) Math.sqrt(lightDir.x * lightDir.x + lightDir.z * lightDir.z);
			lightDir.set(lightDir.x / xzLength * horizontal, minElevation, lightDir.z / xzLength * horizontal);
		}

		try {
			if (RenderSystem.getCompiledPipelineNullable(PIPELINE) == null || RenderSystem.getCompiledPipelineNullable(PIPELINE_MULTIDRAW) == null
				|| RenderSystem.getCompiledPipelineNullable(TRANSLUCENT_PIPELINE) == null
				|| RenderSystem.getCompiledPipelineNullable(TRANSLUCENT_PIPELINE_MULTIDRAW) == null) {
				throw new IllegalStateException("pipeline compilation failed");
			}
		} catch (RuntimeException e) {
			VibrantsVisuals.LOGGER.error("Failed to compile the shadow pipelines, shadows are disabled", e);
			failed = true;
			return;
		}

		if (shadowTarget == null) {
			shadowTarget = new TextureTarget("Vibrants Visuals shadow map", RESOLUTION, RESOLUTION, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
			projectionBuffer = new ProjectionMatrixBuffer("Vibrants Visuals shadow");
			tintTarget = new TextureTarget("Vibrants Visuals shadow tint", TINT_RESOLUTION, TINT_RESOLUTION, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
		}

		computeShadowMatrix(camera.pos, lightDir, SHADOW_MATRIX);
		// The terrain shader multiplies by the camera view rotation, cancel it so only the light matrix remains
		Matrix4f terrainProjection = new Matrix4f(SHADOW_MATRIX).mul(new Matrix4f(camera.viewRotationMatrix).invert());

		GpuTextureView depthView = shadowTarget.getDepthTextureView();
		RenderPassDescriptor descriptor = RenderPassDescriptor.builder(() -> "Vibrants Visuals shadows")
			.withDepthAttachment(depthView, OptionalDouble.of(0.0))
			.build();
		GpuTextureView blockAtlas = minecraft.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
		// Must be written before the render pass is opened, buffer uploads are not allowed during a pass
		GpuBufferSlice projection = projectionBuffer.getBuffer(terrainProjection);
		GpuBufferSlice casters = writeCasters(minecraft);

		try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(descriptor)) {
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform("Fog", terrainFog);
			pass.setUniform("Projection", projection);
			((ChunkSectionsToRenderInvoker) sections).vibrantsVisuals$renderLayers(
				ChunkSectionLayerGroup.OPAQUE.layers(),
				RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST),
				pass,
				blockAtlas,
				minecraft.gameRenderer.lightmap(),
				PIPELINE,
				PIPELINE_MULTIDRAW
			);
		}

		// Only the caster nearest to the light is kept, so the slime and honey inner cubes don't darken the tint twice
		try (RenderPass pass = RenderSystem.getDevice()
				.createCommandEncoder()
				.createRenderPass(
					() -> "Vibrants Visuals shadow tint",
					tintTarget.getColorTextureView(),
					Optional.of(new Vector4f(1.0F, 1.0F, 1.0F, 1.0F)),
					tintTarget.getDepthTextureView(),
					OptionalDouble.of(0.0)
				)) {
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform("Fog", terrainFog);
			pass.setUniform("Projection", projection);
			pass.setUniform("VibrantsShadowCasters", casters);
			((ChunkSectionsToRenderInvoker) sections).vibrantsVisuals$renderLayers(
				new ChunkSectionLayer[]{ChunkSectionLayer.TRANSLUCENT},
				RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST),
				pass,
				blockAtlas,
				minecraft.gameRenderer.lightmap(),
				TRANSLUCENT_PIPELINE,
				TRANSLUCENT_PIPELINE_MULTIDRAW
			);
		}

		renderFeatures(featureFrame, shadowTarget, projection);

		active = true;
	}

	private static GpuBufferSlice writeCasters(final Minecraft minecraft) {
		if (castersBuffer == null) {
			castersBuffer = RenderSystem.getDevice().createBuffer(() -> "Vibrants Visuals shadow casters", 136, CASTERS_UBO_SIZE);
		}

		TextureAtlas atlas = (TextureAtlas) minecraft.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			Std140Builder builder = Std140Builder.onStack(stack, CASTERS_UBO_SIZE);
			for (Identifier texture : CASTER_TEXTURES) {
				TextureAtlasSprite sprite = atlas.getSprite(texture);
				builder.putVec4(sprite.getU0(), sprite.getV0(), sprite.getU1(), sprite.getV1());
			}
			RenderSystem.getDevice().createCommandEncoder().writeToBuffer(castersBuffer.slice(), builder.get());
		}

		return castersBuffer.slice();
	}

	/**
	 * Entities and block entities (chests, heads, golems...) are drawn by the feature renderers with their own pipelines,
	 * which expect an RGBA8 color target, so they get a throwaway color attachment. Their model-view matrix is the camera
	 * view rotation, exactly like terrain, so the same projection works.
	 */
	private static void renderFeatures(final FeatureRenderDispatcher.PreparedFrame featureFrame, final TextureTarget target, final GpuBufferSlice projection) {
		RenderSystem.backupProjectionMatrix();
		RenderSystem.setProjectionMatrix(projection, ProjectionType.ORTHOGRAPHIC);
		try (RenderPass pass = RenderSystem.getDevice()
				.createCommandEncoder()
				.createRenderPass(() -> "Vibrants Visuals entity shadows", target.getColorTextureView(), Optional.empty(), target.getDepthTextureView(), OptionalDouble.empty())) {
			RenderSystem.bindDefaultUniforms(pass);
			renderingShadowMap = true;
			featureFrame.executeSolid(pass);
			// Translucent models include the player, whose skin is drawn with a translucent render type
			PreparedFrameAccessor accessor = (PreparedFrameAccessor) (Object) featureFrame;
			for (SubmitNodeCollection collection : (accessor.vibrantsVisuals$storage()).getSubmitsPerOrder().values()) {
				accessor.vibrantsVisuals$executePhase(collection.translucentModels, accessor.vibrantsVisuals$context(), pass);
			}
		} catch (RuntimeException e) {
			VibrantsVisuals.LOGGER.error("Failed to render entity shadows", e);
		} finally {
			renderingShadowMap = false;
			RenderSystem.restoreProjectionMatrix();
		}
	}

	private static void computeShadowMatrix(final Vec3 cameraPos, final Vector3f lightDir, final Matrix4f dest) {
		Matrix4f rotation = new Matrix4f().lookAlong(new Vector3f(lightDir).negate(), Math.abs(lightDir.y) > 0.99F ? new Vector3f(0, 0, 1) : new Vector3f(0, 1, 0));

		// Snap the shadow map origin to its texel grid in light space so shadows do not shimmer when moving
		Vector3d lightSpaceCamera = new Vector3d(cameraPos.x, cameraPos.y, cameraPos.z);
		new Matrix4d(rotation).transformDirection(lightSpaceCamera);
		double texel = 2.0 * RADIUS / RESOLUTION;
		double snappedX = Math.floor(lightSpaceCamera.x / texel) * texel;
		double snappedY = Math.floor(lightSpaceCamera.y / texel) * texel;

		boolean zZeroToOne = RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
		// Near/far swapped on purpose: the game uses reversed depth (cleared to 0, closer is greater)
		dest.setOrtho(-RADIUS, RADIUS, -RADIUS, RADIUS, DEPTH_RANGE, -DEPTH_RANGE, zZeroToOne)
			.translate((float) (lightSpaceCamera.x - snappedX), (float) (lightSpaceCamera.y - snappedY), 0.0F)
			.mul(rotation);
	}
}
