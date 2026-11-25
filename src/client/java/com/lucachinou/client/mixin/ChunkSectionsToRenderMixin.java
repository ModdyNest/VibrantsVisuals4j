package com.lucachinou.client.mixin;

import com.lucachinou.client.render.TerrainPipelineOverride;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.oit.OitStage;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin({ChunkSectionsToRender.DrawIndirect.class, ChunkSectionsToRender.DrawSeparate.class})
public abstract class ChunkSectionsToRenderMixin {
	@Redirect(
		method = "render",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;pipeline(ZZZZLnet/minecraft/client/renderer/oit/OitStage;Z)Lcom/mojang/blaze3d/pipeline/RenderPipeline;"
		)
	)
	private RenderPipeline vibrantsVisuals$overridePipeline(
		final ChunkSectionLayer layer,
		final boolean multiDraw,
		final boolean improvedFog,
		final boolean cloudsInFog,
		final boolean wireframe,
		final @Nullable OitStage oitStage,
		final boolean hasWaterMask
	) {
		RenderPipeline override = TerrainPipelineOverride.get(multiDraw);
		return override != null ? override : layer.pipeline(multiDraw, improvedFog, cloudsInFog, wireframe, oitStage, hasWaterMask);
	}
}
