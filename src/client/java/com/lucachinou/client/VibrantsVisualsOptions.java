package com.lucachinou.client;

import com.lucachinou.VibrantsVisuals;
import com.lucachinou.client.mixin.OptionsMixin;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

public final class VibrantsVisualsOptions {
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("vibrants-visuals.properties");
	private static final Map<String, OptionInstance<Boolean>> ALL = new LinkedHashMap<>();
	private static boolean loading;

	public static final OptionInstance<Boolean> SHADOWS = create("shadows");
	public static final OptionInstance<Boolean> CAUSTICS = create("caustics");
	public static final OptionInstance<Boolean> VOLUMETRIC_FOG = create("volumetricFog");
	public static final OptionInstance<Boolean> LIGHT_SHAFTS = create("lightShafts");
	public static final OptionInstance<Boolean> BLOOM = create("bloom");
	public static final OptionInstance<Boolean> TONE_MAPPING = create("toneMapping");

	/** Preference, not an effect: listed under "Preferences" and left alone by the graphics presets. */
	public static final OptionInstance<Boolean> CLOUD_SHADOWS = OptionInstance.createBoolean(
		"options.vibrants_visuals.cloudShadows",
		OptionInstance.cachedConstantTooltip(Component.translatable("options.vibrants_visuals.cloudShadows.tooltip")),
		true
	);

	/** Off by default for parity with Bedrock, and not enabled by the preset. */
	public static final OptionInstance<Boolean> HELD_LIGHT = preference("heldLight", false);
	public static final OptionInstance<Boolean> DYNAMIC_LIGHT = preference("dynamicLight", false);
	public static final OptionInstance<Boolean> COLORED_LIGHT = preference("coloredLight", false);
	public static final OptionInstance<?>[] PREFERENCES = {CLOUD_SHADOWS, HELD_LIGHT, DYNAMIC_LIGHT, COLORED_LIGHT};

	private static OptionInstance<Boolean> preference(final String name, final boolean defaultValue) {
		String key = "options.vibrants_visuals." + name;
		return OptionInstance.createBoolean(key, OptionInstance.cachedConstantTooltip(Component.translatable(key + ".tooltip")), defaultValue);
	}

	static {
		load();
	}

	private VibrantsVisualsOptions() {
	}

	private static OptionInstance<Boolean> create(final String name) {
		String key = "options.vibrants_visuals." + name;
		OptionInstance<Boolean> option = OptionInstance.createBoolean(
			key, OptionInstance.cachedConstantTooltip(Component.translatable(key + ".tooltip")), false, value -> onChanged()
		);
		ALL.put(name, option);
		return option;
	}

	public static OptionInstance<?>[] all() {
		return ALL.values().toArray(OptionInstance[]::new);
	}

	public static boolean anyEnabled() {
		return ALL.values().stream().anyMatch(OptionInstance::get) || HELD_LIGHT.get() || DYNAMIC_LIGHT.get() || COLORED_LIGHT.get();
	}

	/** Toggling an effect by hand switches the graphics preset to "Custom", like vanilla options do. */
	private static void onChanged() {
		Minecraft minecraft = Minecraft.getInstance();
		if (!loading && minecraft != null && minecraft.options != null && minecraft.gui != null) {
			((OptionsMixin) minecraft.options).vibrantsVisuals$setGraphicsPresetToCustom();
		}
	}

	public static void setAll(final Minecraft minecraft, final boolean enabled) {
		OptionsSubScreen screen = minecraft.gui != null && minecraft.gui.screen() instanceof OptionsSubScreen subScreen ? subScreen : null;
		for (OptionInstance<Boolean> option : ALL.values()) {
			if (option.get() != enabled) {
				option.set(enabled);
				if (screen != null) {
					screen.resetOption(option);
				}
			}
		}
	}

	private static void load() {
		if (!Files.exists(FILE)) {
			return;
		}

		Properties properties = new Properties();
		try (Reader reader = Files.newBufferedReader(FILE)) {
			properties.load(reader);
		} catch (IOException e) {
			VibrantsVisuals.LOGGER.warn("Failed to read {}", FILE, e);
			return;
		}

		loading = true;
		try {
			ALL.forEach((name, option) -> {
				String value = properties.getProperty(name);
				if (value != null) {
					option.set(Boolean.parseBoolean(value));
				}
			});
			loadPreference(properties, "cloudShadows", CLOUD_SHADOWS);
			loadPreference(properties, "heldLight", HELD_LIGHT);
			loadPreference(properties, "coloredLight", COLORED_LIGHT);
			loadPreference(properties, "dynamicLight", DYNAMIC_LIGHT);
		} finally {
			loading = false;
		}
	}

	private static void loadPreference(final Properties properties, final String name, final OptionInstance<Boolean> option) {
		String value = properties.getProperty(name);
		if (value != null) {
			option.set(Boolean.parseBoolean(value));
		}
	}

	public static void save() {
		Properties properties = new Properties();
		ALL.forEach((name, option) -> properties.setProperty(name, option.get().toString()));
		properties.setProperty("cloudShadows", CLOUD_SHADOWS.get().toString());
		properties.setProperty("heldLight", HELD_LIGHT.get().toString());
		properties.setProperty("coloredLight", COLORED_LIGHT.get().toString());
		properties.setProperty("dynamicLight", DYNAMIC_LIGHT.get().toString());
		try (Writer writer = Files.newBufferedWriter(FILE)) {
			properties.store(writer, "Vibrants Visuals");
		} catch (IOException e) {
			VibrantsVisuals.LOGGER.warn("Failed to write {}", FILE, e);
		}
	}
}
