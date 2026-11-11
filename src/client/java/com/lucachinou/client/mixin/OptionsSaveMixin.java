package com.lucachinou.client.mixin;

import com.lucachinou.client.VibrantsVisualsOptions;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Options.class)
public abstract class OptionsSaveMixin {
	@Inject(method = "save", at = @At("TAIL"))
	private void vibrantsVisuals$save(final CallbackInfo ci) {
		VibrantsVisualsOptions.save();
	}
}
