package com.lucachinou.client.render;

import com.lucachinou.VibrantsVisuals;
import com.lucachinou.client.VibrantsVisualsOptions;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.util.Optional;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.material.FogType;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import net.minecraft.client.Minecraft;
import org.joml.Vector3f;
import com.mojang.blaze3d.pipeline.TextureTarget;
import net.minecraft.client.CloudStatus;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

public final class VibrantsVisualsRenderer {
	private static final RenderPipeline PIPELINE = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
		.withLocation(VibrantsVisuals.id("pipeline/vibrant_visuals"))
		.withVertexShader(Identifier.withDefaultNamespace("core/screenquad"))
		.withFragmentShader(VibrantsVisuals.id("post/vibrant_visuals"))
		.withBindGroupLayout(
			BindGroupLayout.builder()
				.withUniform("InSampler", UniformType.COMBINED_IMAGE_SAMPLER)
				.withUniform("DepthSampler", UniformType.COMBINED_IMAGE_SAMPLER)
				.withUniform("ShadowSampler", UniformType.COMBINED_IMAGE_SAMPLER)
				.withUniform("TintSampler", UniformType.COMBINED_IMAGE_SAMPLER)
				.withUniform("TintDepthSampler", UniformType.COMBINED_IMAGE_SAMPLER)
				.withUniform("CloudSampler", UniformType.COMBINED_IMAGE_SAMPLER)
				.withUniform("NormalSampler", UniformType.COMBINED_IMAGE_SAMPLER)
				.withUniform("NormalDepthSampler", UniformType.COMBINED_IMAGE_SAMPLER)
				.withUniform("ColorLightSampler", UniformType.COMBINED_IMAGE_SAMPLER)
				.withUniform("VibrantsConfig", UniformType.UNIFORM_BUFFER)
				.build()
		)
		.withColorTargetState(ColorTargetState.DEFAULT)
		.build();
	private static final Identifier CLOUDS_TEXTURE = Identifier.withDefaultNamespace("textures/environment/clouds.png");
	private static final int UBO_SIZE = new Std140SizeCalculator()
		.putMat4f()
		.putMat4f()
		.putIVec4()
		.putVec4()
		.putVec4()
		.putVec4()
		.putVec4()
		.putVec4()
		.putIVec4()
		.putVec4()
		.putVec4()
		.putVec4()
		.putMat4f()
		.get() + 16 * (DynamicLights.MAX_LIGHTS * 8 + 2);

	private static @Nullable MappableRingBuffer configBuffer;
	private static @Nullable TextureTarget sceneCopy;
	private static boolean failed;
	/** Projection actually used for the level this frame, including view bobbing, hurt tilt and nausea. */
	public static final Matrix4f LEVEL_PROJECTION = new Matrix4f();

	private VibrantsVisualsRenderer() {
	}

	public static void render(final GameRenderState state, final RenderTarget mainTarget) {
		if (failed || !VibrantsVisualsOptions.anyEnabled() || mainTarget.getColorTexture() == null || mainTarget.getDepthTextureView() == null) {
			return;
		}

		CompiledRenderPipeline pipeline;
		try {
			pipeline = RenderSystem.getCompiledPipelineNullable(PIPELINE);
		} catch (RuntimeException e) {
			pipeline = null;
		}

		if (pipeline == null) {
			VibrantsVisuals.LOGGER.error("Failed to compile the Vibrants Visuals pipeline, effects are disabled");
			failed = true;
			return;
		}

		int width = mainTarget.width;
		int height = mainTarget.height;
		if (sceneCopy == null) {
			sceneCopy = new TextureTarget("Vibrants Visuals scene", width, height, GpuFormat.RGBA8_UNORM, null);
		} else if (sceneCopy.width != width || sceneCopy.height != height) {
			sceneCopy.resize(width, height);
		}

		if (configBuffer == null) {
			configBuffer = new MappableRingBuffer(() -> "Vibrants Visuals config", 130, UBO_SIZE);
		}

		writeConfig(state, configBuffer);

		// Loading a texture uploads it, which is not allowed once the render pass is open
		GpuTextureView cloudTexture = Minecraft.getInstance().getTextureManager().getTexture(CLOUDS_TEXTURE).getTextureView();
		net.minecraft.client.renderer.texture.DynamicTexture coloredLight = VibrantsVisualsOptions.COLORED_LIGHT.get() ? ColoredLightVolume.texture() : null;
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		encoder.copyTextureToTexture(mainTarget.getColorTexture(), sceneCopy.getColorTexture(), 0, 0, 0, 0, 0, width, height);

		try (RenderPass pass = encoder.createRenderPass(() -> "Vibrants Visuals", mainTarget.getColorTextureView(), Optional.empty())) {
			pass.setPipeline(pipeline);
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform("InSampler", sceneCopy.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
			pass.setUniform("DepthSampler", mainTarget.getDepthTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
			GpuTextureView shadowView = ShadowRenderer.isActive() ? ShadowRenderer.depthView() : null;
			pass.setUniform(
				"ShadowSampler",
				shadowView != null ? shadowView : mainTarget.getDepthTextureView(),
				RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST)
			);
			TextureTarget tint = ShadowRenderer.tintTarget();
			pass.setUniform(
				"TintSampler",
				tint != null ? tint.getColorTextureView() : sceneCopy.getColorTextureView(),
				RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST)
			);
			pass.setUniform(
				"TintDepthSampler",
				tint != null ? tint.getDepthTextureView() : mainTarget.getDepthTextureView(),
				RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST)
			);
			pass.setUniform(
				"CloudSampler",
				cloudTexture,
				RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST)
			);
			TextureTarget normals = NormalRenderer.target();
			boolean normalsReady = NormalRenderer.isActive() && normals != null && normals.width == width && normals.height == height;
			pass.setUniform("NormalSampler", normalsReady ? normals.getColorTextureView() : sceneCopy.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
			pass.setUniform("NormalDepthSampler", normalsReady ? normals.getDepthTextureView() : mainTarget.getDepthTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
			pass.setUniform("ColorLightSampler", coloredLight != null ? coloredLight.getTextureView() : sceneCopy.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
			pass.setUniform("VibrantsConfig", configBuffer.currentBuffer());
			pass.draw(3, 1, 0, 0);
		}

		configBuffer.rotate();
	}

	/** x: time in seconds, y: enabled, zw: camera X/Z wrapped to 256 blocks (a multiple of the caustics tile) */
	private static Vector4f causticsParams(final GameRenderState state) {
		if (!VibrantsVisualsOptions.CAUSTICS.get() || !ShadowRenderer.isActive()) {
			return new Vector4f(0.0F, 0.0F, 0.0F, 0.0F);
		}

		float seconds = ((state.levelRenderState.gameTime % 24000L) + state.levelRenderState.worldPartialTicks) / 20.0F;
		Vec3 pos = state.levelRenderState.cameraRenderState.pos;
		return new Vector4f(seconds, 1.0F, (float) (pos.x - Math.floor(pos.x / 256.0) * 256.0), (float) (pos.z - Math.floor(pos.z / 256.0) * 256.0));
	}

	/** Same scrolling as CloudRenderer: 12 blocks per texture pixel, moving 0.03 blocks per tick along X. */
	private static Vector4f cloudParams(final GameRenderState state) {
		Minecraft minecraft = Minecraft.getInstance();
		boolean enabled = VibrantsVisualsOptions.CLOUD_SHADOWS.get()
			&& minecraft.level != null
			&& minecraft.options.cloudStatus().get() != CloudStatus.OFF
			&& state.levelRenderState.skyRenderState.skybox == DimensionType.Skybox.OVERWORLD;
		if (!enabled) {
			return new Vector4f(0.0F, 0.0F, 0.0F, 0.0F);
		}

		GpuTextureView clouds = minecraft.getTextureManager().getTexture(CLOUDS_TEXTURE).getTextureView();
		double periodX = clouds.getWidth(0) * 12.0;
		double periodZ = clouds.getHeight(0) * 12.0;
		Vec3 cameraPos = state.levelRenderState.cameraRenderState.pos;
		// Same time values the cloud renderer receives, so the shadow moves exactly with the clouds
		long gameTime = state.levelRenderState.gameTime;
		double cloudOffset = (gameTime % (clouds.getWidth(0) * 400L)) + state.levelRenderState.worldPartialTicks;
		double x = cameraPos.x + cloudOffset * 0.03F;
		double z = cameraPos.z + 3.96F;
		return new Vector4f(
			(float) (x - Math.floor(x / periodX) * periodX),
			(float) (z - Math.floor(z / periodZ) * periodZ),
			state.levelRenderState.cloudHeight,
			1.0F
		);
	}

	private static void writeConfig(final GameRenderState state, final MappableRingBuffer buffer) {
		CameraRenderState camera = state.levelRenderState.cameraRenderState;
		SkyRenderState sky = state.levelRenderState.skyRenderState;

		Matrix4f viewProj = new Matrix4f(LEVEL_PROJECTION).mul(camera.viewRotationMatrix);
		Matrix4f invViewProj = new Matrix4f(viewProj).invert();

		boolean overworldSky = sky.skybox == DimensionType.Skybox.OVERWORLD;
		boolean inAir = camera.fogType == FogType.NONE;

		// Same transform as SkyRenderer#renderSunMoonAndStars: the sun sits at +Y of that rotated frame
		Vector3f sunDir = new Quaternionf().rotationY((float) (-Math.PI / 2)).rotateX(sky.sunAngle).transform(new Vector3f(0.0F, 1.0F, 0.0F));
		boolean isDay = sunDir.y > -0.05F;
		Vector3f lightDir = ShadowRenderer.lightDirection(sky);
		float lightStrength = overworldSky ? (isDay ? 1.0F : 0.25F) * sky.rainBrightness : 0.0F;

		Vector4f lightClip = viewProj.transform(new Vector4f(lightDir, 0.0F));
		float lightOnScreen = 0.0F;
		float lightU = 0.5F;
		float lightV = 0.5F;
		if (lightClip.w > 1.0E-4F) {
			lightU = lightClip.x / lightClip.w * 0.5F + 0.5F;
			lightV = lightClip.y / lightClip.w * 0.5F + 0.5F;
			lightOnScreen = 1.0F;
		}

		Vector4f fogColor = camera.fogData.color;
		boolean zZeroToOne = RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
		boolean fog = VibrantsVisualsOptions.VOLUMETRIC_FOG.get() && inAir && sky.skybox != DimensionType.Skybox.END;
		boolean shafts = VibrantsVisualsOptions.LIGHT_SHAFTS.get() && inAir && lightStrength > 0.0F;

		try (GpuBufferSlice.MappedView view = buffer.currentBuffer().map(false, true)) {
			Std140Builder builder = Std140Builder.intoBuffer(view.data());
			builder
				.putMat4f(invViewProj)
				.putMat4f(ShadowRenderer.shadowMatrix())
				.putIVec4(ShadowRenderer.isActive() ? 1 : 0, 0, 0, 0)
				.putVec4(ShadowRenderer.RADIUS, 2.0F * ShadowRenderer.RADIUS / ShadowRenderer.RESOLUTION, 2.0F * ShadowRenderer.DEPTH_RANGE, 0.0F)
				.putVec4(lightDir.x, lightDir.y, lightDir.z, lightStrength)
				.putVec4(lightU, lightV, lightOnScreen, isDay ? 1.0F : 0.0F)
				.putVec4(fogColor.x, fogColor.y, fogColor.z, 1.0F)
				.putVec4((float) camera.pos.y, 1.0F - sky.rainBrightness, zZeroToOne ? 1.0F : 0.0F, camera.depthFar)
				.putIVec4(fog ? 1 : 0, shafts ? 1 : 0, VibrantsVisualsOptions.BLOOM.get() ? 1 : 0, VibrantsVisualsOptions.TONE_MAPPING.get() ? 1 : 0)
				.putVec4(cloudParams(state))
				.putVec4(causticsParams(state))
				.putVec4(new Vector4f(ShadowRenderer.smoothLightDirection(sky), NormalRenderer.isActive() ? 1.0F : 0.0F))
				.putMat4f(viewProj);
			for (Vector4f value : DynamicLights.collect(camera.pos, state.levelRenderState.worldPartialTicks)) {
				builder.putVec4(value);
			}
		}
	}
}
