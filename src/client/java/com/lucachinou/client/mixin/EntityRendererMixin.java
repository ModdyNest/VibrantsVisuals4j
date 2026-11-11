package com.lucachinou.client.mixin;

import com.lucachinou.client.VibrantsVisualsOptions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {
	/** The vanilla round blob is replaced by real shadows. */
	@Inject(method = "extractShadow", at = @At("HEAD"), cancellable = true)
	private void vibrantsVisuals$hideBlobShadow(final EntityRenderState state, final Minecraft minecraft, final Level level, final CallbackInfo ci) {
		if (VibrantsVisualsOptions.SHADOWS.get()) {
			state.shadowPieces.clear();
			ci.cancel();
		}
	}
}
