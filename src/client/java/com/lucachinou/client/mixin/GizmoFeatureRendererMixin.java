package com.lucachinou.client.mixin;

import com.lucachinou.client.VibrantsVisualsOptions;
import net.minecraft.client.renderer.feature.GizmoFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(GizmoFeatureRenderer.class)
public abstract class GizmoFeatureRendererMixin {
	/** Debug lines (chunk borders...) must not write depth, the volumetric fog would treat them as solid walls. */
	@Redirect(method = "buildLines", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/rendertype/RenderTypes;lines()Lnet/minecraft/client/renderer/rendertype/RenderType;"))
	private RenderType vibrantsVisuals$noDepthLines() {
		return VibrantsVisualsOptions.VOLUMETRIC_FOG.get() ? RenderTypes.linesTranslucentNoDepthWrite() : RenderTypes.lines();
	}
}
