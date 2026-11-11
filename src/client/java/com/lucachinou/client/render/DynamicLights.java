package com.lucachinou.client.render;

import com.lucachinou.VibrantsVisuals;
import com.lucachinou.client.VibrantsVisualsOptions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.BeaconBeamBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Point lights for the post pass: light sources held by entities, and nearby light-emitting blocks.
 * Held items are picked from the item tag {@code vibrants-visuals:held_light_sources}, which datapacks can extend.
 */
public final class DynamicLights {
	public static final int MAX_LIGHTS = 8;
	public static final TagKey<Item> HELD_LIGHT_SOURCES = TagKey.create(Registries.ITEM, VibrantsVisuals.id("held_light_sources"));
	private static final double ENTITY_RANGE = 32.0;
	private static final int BLOCK_RANGE = 12;

	private static final List<Light> BLOCK_LIGHTS = new ArrayList<>();
	private static long lastScanTime = Long.MIN_VALUE;
	private static BlockPos lastScanCenter = BlockPos.ZERO;

	/** pos: world position, w: radius in blocks. color: rgb, w: 1 for held lights (add light), 0 for block lights (shadows only). */
	public record Light(Vec3 pos, float radius, float held, Vector3f color, Vector4f[] glass) {
	}

	private static final Vector3f WARM = new Vector3f(1.0F, 0.78F, 0.52F);
	private static final Vector4f[] NO_GLASS = noGlass();

	private static Vector4f[] noGlass() {
		Vector4f[] glass = new Vector4f[6];
		java.util.Arrays.fill(glass, new Vector4f(1.0F));
		return glass;
	}

	/** Light color guessed from the block or item name, the same rules apply to modded content. */
	static Vector3f colorOf(final String name) {
		if (name.contains("redstone")) {
			return new Vector3f(1.0F, 0.22F, 0.12F);
		} else if (name.contains("soul")) {
			return new Vector3f(0.35F, 0.78F, 1.0F);
		} else if (name.contains("copper_torch") || name.contains("copper_lantern")) {
			return new Vector3f(0.55F, 1.0F, 0.55F);
		} else if (name.contains("amethyst") || name.contains("crying_obsidian") || name.contains("end_portal")) {
			return new Vector3f(0.75F, 0.4F, 1.0F);
		} else if (name.contains("sea_lantern") || name.contains("beacon") || name.contains("end_rod") || name.contains("sea_pickle") || name.contains("conduit")) {
			return new Vector3f(0.8F, 0.95F, 1.0F);
		} else if (name.contains("magma") || name.contains("lava")) {
			return new Vector3f(1.0F, 0.5F, 0.15F);
		}
		return WARM;
	}


	private DynamicLights() {
	}

	/** Writes {@link #MAX_LIGHTS} pairs of vec4 (camera-relative position + radius, color + kind) and returns the count. */
	public static List<Vector4f> collect(final Vec3 camera, final float partialTick) {
		Minecraft minecraft = Minecraft.getInstance();
		List<Light> lights = new ArrayList<>();
		if (minecraft.level != null) {
			if (VibrantsVisualsOptions.HELD_LIGHT.get()) {
				for (Entity entity : minecraft.level.entitiesForRendering()) {
					if (entity instanceof LivingEntity living && entity.position().distanceToSqr(camera) < ENTITY_RANGE * ENTITY_RANGE) {
						ItemStack main = living.getMainHandItem();
						ItemStack off = living.getOffhandItem();
						ItemStack brightest = heldLight(main) >= heldLight(off) ? main : off;
						int level = heldLight(brightest);
						if (level > 0) {
							Vector3f color = colorOf(BuiltInRegistries.ITEM.getKey(brightest.getItem()).getPath());
							lights.add(new Light(living.getEyePosition(partialTick).subtract(0.0, 0.45, 0.0), level, 1.0F, color, NO_GLASS));
						}
					}
				}
			}
			if (VibrantsVisualsOptions.DYNAMIC_LIGHT.get() || VibrantsVisualsOptions.COLORED_LIGHT.get()) {
				scanBlocks(minecraft, camera);
				lights.addAll(BLOCK_LIGHTS);
			}
		}

		lights.sort(Comparator.comparingDouble(light -> light.pos.distanceToSqr(camera) - light.held * 1.0e6));
		boolean colored = VibrantsVisualsOptions.COLORED_LIGHT.get();
		List<Vector4f> data = new ArrayList<>();
		List<Vector4f> glass = new ArrayList<>();
		for (int i = 0; i < MAX_LIGHTS; i++) {
			if (i < lights.size()) {
				Light light = lights.get(i);
				Vec3 rel = light.pos.subtract(camera);
				data.add(new Vector4f((float) rel.x, (float) rel.y, (float) rel.z, light.radius));
				// w: 1 held light, 0 placed light, 2 placed light recolored by the Colored Light option
				Vector3f color = colored ? light.color : WARM;
				// Placed lights are colored by ColoredLightVolume, which propagates through glass in every direction
				data.add(new Vector4f(color, light.held));
				for (Vector4f tint : NO_GLASS) {
					glass.add(tint);
				}
			} else {
				data.add(new Vector4f());
				data.add(new Vector4f());
				for (int d = 0; d < 6; d++) {
					glass.add(new Vector4f(1.0F));
				}
			}
		}
		// The first person player isn't in the depth buffer, so the shader casts its shadow from a capsule (z: enabled, w: eye height)
		boolean firstPerson = minecraft.player != null && minecraft.options.getCameraType().isFirstPerson();
		data.add(new Vector4f(Math.min(lights.size(), MAX_LIGHTS), VibrantsVisualsOptions.DYNAMIC_LIGHT.get() ? 1.0F : 0.0F,
			firstPerson ? 1.0F : 0.0F, firstPerson ? minecraft.player.getEyeHeight() : 0.0F));
		data.addAll(glass);
		data.add(ColoredLightVolume.update(minecraft, camera));
		return data;
	}

	private static int heldLight(final ItemStack stack) {
		if (stack.isEmpty()) {
			return 0;
		}
		boolean tagLoaded = BuiltInRegistries.ITEM.getTagOrEmpty(HELD_LIGHT_SOURCES).iterator().hasNext();
		int emission = stack.getItem() instanceof BlockItem blockItem ? blockItem.getBlock().defaultBlockState().getLightEmission() : 0;
		if (tagLoaded) {
			return stack.is(HELD_LIGHT_SOURCES) ? (emission > 0 ? emission : 15) : 0;
		}
		// Servers without the mod don't send the tag: fall back to any light-emitting block item
		return emission;
	}

	/** Light-emitting blocks around the camera, rescanned twice per second or after moving a few blocks. */
	private static void scanBlocks(final Minecraft minecraft, final Vec3 camera) {
		BlockPos center = BlockPos.containing(camera);
		long time = minecraft.level.getGameTime();
		if (time - lastScanTime < 10 && center.distManhattan(lastScanCenter) < 4) {
			return;
		}
		lastScanTime = time;
		lastScanCenter = center;
		BLOCK_LIGHTS.clear();
		for (BlockPos pos : BlockPos.betweenClosed(center.offset(-BLOCK_RANGE, -BLOCK_RANGE, -BLOCK_RANGE), center.offset(BLOCK_RANGE, BLOCK_RANGE, BLOCK_RANGE))) {
			BlockState state = minecraft.level.getBlockState(pos);
			int emission = state.getLightEmission();
			// Dim lights such as redstone torches only matter when they are colored
			int minEmission = VibrantsVisualsOptions.COLORED_LIGHT.get() ? 7 : 10;
			if (emission >= minEmission && state.getFluidState().isEmpty()) {
				Block block = state.getBlock();
				BlockPos immutable = pos.immutable();
				BLOCK_LIGHTS.add(new Light(Vec3.atCenterOf(immutable), emission, 0.0F,
					colorOf(BuiltInRegistries.BLOCK.getKey(block).getPath()), NO_GLASS));
			}
		}
	}
}
