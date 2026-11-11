package com.lucachinou.client.render;

import com.lucachinou.VibrantsVisuals;
import com.lucachinou.client.mixin.ChunkSectionsToRenderInvoker;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/**
 * Draws the opaque terrain a second time from the camera, writing exact face normals.
 * The post pass uses them wherever the depth matches, instead of normals rebuilt from the depth buffer.
 */
public final class NormalRenderer {
	private static final RenderPipeline PIPELINE = pipeline(RenderPipelines.TERRAIN_SNIPPET, "pipeline/normals_terrain");
	private static final RenderPipeline PIPELINE_MULTIDRAW = pipeline(RenderPipelines.MULTIDRAW_TERRAIN_SNIPPET, "pipeline/normals_terrain_multidraw");

	private static @Nullable TextureTarget target;
	private static @Nullable ProjectionMatrixBuffer projectionBuffer;
	private static boolean active;
	private static boolean failed;

	private NormalRenderer() {
	}

	private static RenderPipeline pipeline(final RenderPipeline.Snippet snippet, final String location) {
		return RenderPipeline.builder(snippet)
			.withLocation(VibrantsVisuals.id(location))
			.withVertexShader(VibrantsVisuals.id("core/normals"))
			.withFragmentShader(VibrantsVisuals.id("core/normals"))
			.withColorTargetState(ColorTargetState.DEFAULT)
			.withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true))
			.build();
	}

	public static boolean isActive() {
		return active;
	}

	public static @Nullable TextureTarget target() {
		return target;
	}

	public static void render(final ChunkSectionsToRender sections, final GpuBufferSlice terrainFog) {
		active = false;
		if (failed) {
			return;
		}

		try {
			if (RenderSystem.getCompiledPipelineNullable(PIPELINE) == null || RenderSystem.getCompiledPipelineNullable(PIPELINE_MULTIDRAW) == null) {
				throw new IllegalStateException("pipeline compilation failed");
			}
		} catch (RuntimeException e) {
			VibrantsVisuals.LOGGER.error("Failed to compile the normal pipelines, falling back to rebuilt normals", e);
			failed = true;
			return;
		}

		Minecraft minecraft = Minecraft.getInstance();
		RenderTarget main = minecraft.gameRenderer.mainRenderTarget();
		if (target == null) {
			target = new TextureTarget("Vibrants Visuals normals", main.width, main.height, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
			projectionBuffer = new ProjectionMatrixBuffer("Vibrants Visuals normals");
		} else if (target.width != main.width || target.height != main.height) {
			target.resize(main.width, main.height);
		}

		// Same projection as the main pass, so the depths match exactly
		GpuBufferSlice projection = projectionBuffer.getBuffer(VibrantsVisualsRenderer.LEVEL_PROJECTION);
		try (RenderPass pass = RenderSystem.getDevice()
				.createCommandEncoder()
				.createRenderPass(
					() -> "Vibrants Visuals normals",
					target.getColorTextureView(),
					Optional.of(new Vector4f(0.0F)),
					target.getDepthTextureView(),
					OptionalDouble.of(0.0)
				)) {
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform("Fog", terrainFog);
			pass.setUniform("Projection", projection);
			((ChunkSectionsToRenderInvoker) sections).vibrantsVisuals$renderLayers(
				ChunkSectionLayerGroup.OPAQUE.layers(),
				RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST),
				pass,
				minecraft.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView(),
				minecraft.gameRenderer.lightmap(),
				PIPELINE,
				PIPELINE_MULTIDRAW
			);
		}

		active = true;
	}
}
