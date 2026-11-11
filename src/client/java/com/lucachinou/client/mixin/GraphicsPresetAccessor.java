package com.lucachinou.client.mixin;

import net.minecraft.client.GraphicsPreset;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(GraphicsPreset.class)
public interface GraphicsPresetAccessor {
	@Invoker("<init>")
	static GraphicsPreset vibrantsVisuals$create(final String internalName, final int ordinal, final String serializedName, final String key) {
		throw new AssertionError();
	}
}
