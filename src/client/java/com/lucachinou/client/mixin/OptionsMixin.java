package com.lucachinou.client.mixin;

import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Options.class)
public interface OptionsMixin {
	@Invoker("setGraphicsPresetToCustom")
	void vibrantsVisuals$setGraphicsPresetToCustom();
}
