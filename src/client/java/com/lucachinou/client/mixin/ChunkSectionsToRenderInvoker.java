package com.lucachinou.client.mixin;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ChunkSectionsToRender.class)
public interface ChunkSectionsToRenderInvoker {
	@Invoker("renderLayers")
	void vibrantsVisuals$renderLayers(
		ChunkSectionLayer[] layers,
		GpuSampler sampler,
		RenderPass renderPass,
		GpuTextureView atlas,
		GpuTextureView lightmap,
		RenderPipeline renderPipelineOverride,
		RenderPipeline renderPipelineOverrideMultidraw
	);
}
