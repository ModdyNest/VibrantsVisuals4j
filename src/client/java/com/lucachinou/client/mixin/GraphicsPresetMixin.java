package com.lucachinou.client.mixin;

import com.lucachinou.client.VibrantsVisualsOptions;
import com.lucachinou.client.VibrantsVisualsPresets;
import java.util.Arrays;
import net.minecraft.client.GraphicsPreset;
import net.minecraft.client.Minecraft;
import net.minecraft.client.PreferredGraphicsApi;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GraphicsPreset.class)
public abstract class GraphicsPresetMixin {
	@Shadow
	@Final
	@Mutable
	private static GraphicsPreset[] $VALUES;

	/**
	 * Adds the "Vibrants Visuals" preset right after $VALUES is assigned, before CODEC is built,
	 * and places it between "Fabulous" and "Custom" on the preset slider.
	 */
	@Inject(method = "<clinit>", at = @At(value = "FIELD", opcode = Opcodes.PUTSTATIC, target = "Lnet/minecraft/client/GraphicsPreset;$VALUES:[Lnet/minecraft/client/GraphicsPreset;", shift = At.Shift.AFTER))
	private static void vibrantsVisuals$addPreset(final CallbackInfo ci) {
		GraphicsPreset preset = GraphicsPresetAccessor.vibrantsVisuals$create(
			"VIBRANTS_VISUALS", $VALUES.length, "vibrants_visuals", "options.graphics.vibrants_visuals"
		);
		GraphicsPreset[] values = Arrays.copyOf($VALUES, $VALUES.length + 1);
		values[values.length - 1] = values[values.length - 2];
		values[values.length - 2] = preset;
		$VALUES = values;
		VibrantsVisualsPresets.VIBRANTS_VISUALS = preset;
	}

	@Inject(method = "apply", at = @At("HEAD"), cancellable = true)
	private void vibrantsVisuals$apply(final Minecraft minecraft, final CallbackInfo ci) {
		GraphicsPreset self = (GraphicsPreset) (Object) this;
		if (self == VibrantsVisualsPresets.VIBRANTS_VISUALS) {
			GraphicsPreset.FABULOUS.apply(minecraft);
			VibrantsVisualsOptions.setAll(minecraft, true);
			// Vulkan performs better with the extra shadow pass; the vanilla restart warning shows up if needed
			if (minecraft.options.preferredGraphicsBackend().get() != PreferredGraphicsApi.VULKAN) {
				minecraft.options.preferredGraphicsBackend().set(PreferredGraphicsApi.VULKAN);
				if (minecraft.gui != null && minecraft.gui.screen() instanceof OptionsSubScreen screen) {
					screen.resetOption(minecraft.options.preferredGraphicsBackend());
				}
			}
			ci.cancel();
		} else if (self != GraphicsPreset.CUSTOM) {
			VibrantsVisualsOptions.setAll(minecraft, false);
		}
	}
}
