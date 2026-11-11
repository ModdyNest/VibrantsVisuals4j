package com.lucachinou.client.mixin;

import com.lucachinou.client.render.ShadowRenderer;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(PreparedRenderType.class)
public abstract class PreparedRenderTypeMixin {
	/** Translucent entity pipelines (the player skin uses one) don't write depth, so they are swapped during the shadow pass. */
	@ModifyVariable(method = "draw", at = @At("HEAD"), argsOnly = true)
	private RenderPipeline vibrantsVisuals$shadowPipeline(final RenderPipeline pipeline) {
		return ShadowRenderer.isRenderingShadowMap() ? ShadowRenderer.shadowPipelineFor(pipeline) : pipeline;
	}
}
