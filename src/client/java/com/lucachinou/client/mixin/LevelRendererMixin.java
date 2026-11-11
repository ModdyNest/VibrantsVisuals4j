package com.lucachinou.client.mixin;

import com.lucachinou.client.render.FirstPersonShadow;
import com.lucachinou.client.render.ShadowRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
	@Shadow
	@Final
	private GameRenderer gameRenderer;

	@Inject(method = "submitFeatures", at = @At("HEAD"))
	private void vibrantsVisuals$submitFirstPersonShadow(
		final LevelRenderState levelRenderState, final SubmitNodeCollector submitNodeCollector, final boolean renderOutline, final CallbackInfo ci
	) {
		if (submitNodeCollector instanceof SubmitNodeStorage storage) {
			FirstPersonShadow.submit(levelRenderState, storage, this.gameRenderer.useImprovedTransparency());
		}
	}

	/** The chunk draws are ready at this point, so the shadow map is drawn from them before the main pass. */
	@Inject(method = "addMainPass", at = @At("HEAD"))
	private void vibrantsVisuals$renderShadowMap(
		final FrameGraphBuilder frame,
		final FeatureRenderDispatcher.PreparedFrame featureFrame,
		final GpuBufferSlice terrainFog,
		final ChunkSectionsToRender chunkSectionsToRender,
		final boolean consistentDepthRequired,
		final CallbackInfo ci
	) {
		ShadowRenderer.render(chunkSectionsToRender, featureFrame, terrainFog);
		if (com.lucachinou.client.VibrantsVisualsOptions.anyEnabled()) {
			com.lucachinou.client.render.NormalRenderer.render(chunkSectionsToRender, terrainFog);
		}
	}
}
