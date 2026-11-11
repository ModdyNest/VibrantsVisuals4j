package com.lucachinou.client.mixin;

import com.lucachinou.client.VibrantsVisualsOptions;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.client.OptionInstance;

@Mixin(VideoSettingsScreen.class)
public abstract class VideoSettingsScreenMixin extends OptionsSubScreen {
	private static final Component VIBRANTS_VISUALS_HEADER = Component.translatable("options.video.vibrants_visuals.header")
		.withStyle(ChatFormatting.UNDERLINE, ChatFormatting.BOLD);

	private VideoSettingsScreenMixin(final Screen lastScreen, final Options options, final Component title) {
		super(lastScreen, options, title);
	}

	@Inject(method = "preferenceOptions", at = @At("RETURN"), cancellable = true)
	private static void vibrantsVisuals$addCloudShadows(final Options options, final CallbackInfoReturnable<OptionInstance<?>[]> cir) {
		OptionInstance<?>[] base = cir.getReturnValue();
		OptionInstance<?>[] result = java.util.Arrays.copyOf(base, base.length + VibrantsVisualsOptions.PREFERENCES.length);
		System.arraycopy(VibrantsVisualsOptions.PREFERENCES, 0, result, base.length, VibrantsVisualsOptions.PREFERENCES.length);
		cir.setReturnValue(result);
	}

	/** Inserts the Vibrants Visuals category between the quality options and the preferences header. */
	@Inject(
		method = "addOptions",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/OptionsList;addHeader(Lnet/minecraft/network/chat/Component;)V", ordinal = 2)
	)
	private void vibrantsVisuals$addCategory(final CallbackInfo ci) {
		this.list.addHeader(VIBRANTS_VISUALS_HEADER);
		this.list.addSmall(VibrantsVisualsOptions.all());
	}
}
