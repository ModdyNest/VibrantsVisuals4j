package com.lucachinou.client.mixin;

import com.lucachinou.client.render.FirstPersonShadow;
import com.lucachinou.client.render.ShadowRenderer;
import com.mojang.renderpearl.api.commands.RenderPass;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.feature.phase.FeatureRenderPhase;
import net.minecraft.client.renderer.oit.OitStage;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FeatureRenderDispatcher.PreparedFrame.class)
public abstract class PreparedFrameMixin {
	@Inject(
		method = "executePhase(Lnet/minecraft/client/renderer/feature/phase/FeatureRenderPhase;Lnet/minecraft/client/renderer/feature/FeatureFrameContext;Lnet/minecraft/client/renderer/oit/OitStage;Lcom/mojang/renderpearl/api/commands/RenderPass;)V",
		at = @At("HEAD"),
		cancellable = true
	)
	private void vibrantsVisuals$skipShadowOnly(
		final FeatureRenderPhase<?> phase, final FeatureFrameContext context, final @Nullable OitStage stage, final RenderPass renderPass, final CallbackInfo ci
	) {
		if (!ShadowRenderer.isRenderingShadowMap() && FirstPersonShadow.isShadowOnly(phase)) {
			ci.cancel();
		}
	}
}
