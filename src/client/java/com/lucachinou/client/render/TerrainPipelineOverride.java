package com.lucachinou.client.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.pipeline.RenderPipelines;
import net.minecraft.client.renderer.pipeline.variant.TerrainRenderPipelineVariant;
import org.jspecify.annotations.Nullable;

/**
 * Terrain draws no longer accept a pipeline, so the chunk renderers ask this class (through a mixin) whether
 * one of our passes is replacing the vanilla terrain pipeline.
 */
public final class TerrainPipelineOverride {
	private static @Nullable RenderPipeline single;
	private static @Nullable RenderPipeline multiDraw;

	private TerrainPipelineOverride() {
	}

	public static @Nullable RenderPipeline get(final boolean isMultiDraw) {
		return isMultiDraw ? multiDraw : single;
	}

	/** Same setup as {@link RenderPipelines#terrain}, without the color, fog or OIT parts. */
	public static RenderPipeline.Builder terrainBuilder(final boolean isMultiDraw) {
		TerrainRenderPipelineVariant variant = TerrainRenderPipelineVariant.SOLID;
		int texelSize = BindGroupLayouts.TERRAIN_VERTEX_TEXEL_FORMAT.blockSize();
		RenderPipeline.Builder builder = RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
			.withBindGroupLayout(BindGroupLayouts.FOG)
			.withBindGroupLayout(BindGroupLayouts.SAMPLER0)
			.withBindGroupLayout(BindGroupLayouts.SAMPLER2)
			.withPrimitiveTopology(PrimitiveTopology.QUADS)
			.withDepthStencilState(DepthStencilState.DEFAULT)
			.withBindGroupLayout(BindGroupLayouts.PROJECTION)
			.withBindGroupLayout(BindGroupLayouts.TERRAIN_INFO)
			.withVertexShader("core/terrain")
			.withFragmentShader("core/terrain")
			.withShaderDefine("PER_FACE_INCREMENT", variant.vertexFaceSize() / texelSize)
			.withShaderDefine("PER_BAKED_FACE_INCREMENT", variant.vertexBakedFaceSize() / texelSize)
			.withShaderDefine("PER_BAKED_FACE_VERTEX_INCREMENT", variant.vertexBakedFaceSize() / 4 / texelSize);
		if (isMultiDraw) {
			builder.withVertexBinding(0, DefaultVertexFormat.CHUNK_DATA_INSTANCED).withShaderDefine("MULTIDRAW_TERRAIN");
		} else {
			builder.withBindGroupLayout(BindGroupLayouts.CHUNK_SECTION);
		}
		return builder;
	}

	public static void renderLayers(
		final ChunkSectionsToRender sections,
		final ChunkSectionLayer[] layers,
		final RenderPass pass,
		final GpuTextureView atlas,
		final RenderPipeline pipeline,
		final RenderPipeline pipelineMultiDraw
	) {
		single = pipeline;
		multiDraw = pipelineMultiDraw;
		try {
			sections.renderLayers(
				layers,
				RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST),
				pass,
				atlas,
				Minecraft.getInstance().gameRenderer.lightmap(),
				null,
				false,
				null,
				false
			);
		} finally {
			single = null;
			multiDraw = null;
		}
	}
}
