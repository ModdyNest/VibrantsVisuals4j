package com.lucachinou.client.mixin;

import com.lucachinou.client.render.VibrantsVisualsRenderer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	@Shadow
	@Final
	private GameRenderState gameRenderState;

	@Shadow
	public abstract RenderTarget mainRenderTarget();

	/** Captures the final level projection so effects reconstruct positions with the same bobbing as the world. */
	@ModifyArg(
		method = "renderLevel",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;")
	)
	private Matrix4f vibrantsVisuals$captureProjection(final Matrix4f projection) {
		VibrantsVisualsRenderer.LEVEL_PROJECTION.set(projection);
		return projection;
	}

	/** Runs after the world is drawn but before the hand and screen effects, so they stay untouched. */
	@Inject(
		method = "renderLevel",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lorg/joml/Vector4f;ZZ)V", shift = At.Shift.AFTER)
	)
	private void vibrantsVisuals$applyEffects(final CallbackInfo ci) {
		VibrantsVisualsRenderer.render(this.gameRenderState, this.mainRenderTarget());
	}
}
