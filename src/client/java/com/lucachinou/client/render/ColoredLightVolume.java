package com.lucachinou.client.render;

import com.lucachinou.client.VibrantsVisualsOptions;
import java.util.ArrayDeque;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.BeaconBeamBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/**
 * Colored block light propagated in 3D around the camera, like vanilla light but with one level per color channel.
 * Stained glass multiplies the light passing through it. Stored as 32 slices of 32x32 cells side by side in one texture.
 */
public final class ColoredLightVolume {
	public static final int SIZE = 32;
	private static final Direction[] DIRECTIONS = Direction.values();

	private static @Nullable GpuTexture texture;
	private static @Nullable GpuTextureView textureView;
	private static final int[] LEVELS = new int[SIZE * SIZE * SIZE * 3];
	private static long lastUpdate = Long.MIN_VALUE;
	private static BlockPos origin = BlockPos.ZERO;

	private ColoredLightVolume() {
	}

	public static @Nullable GpuTextureView texture() {
		return textureView;
	}

	/** Returns the volume corner relative to the camera in xyz, and 1 in w when enabled. */
	public static Vector4f update(final Minecraft minecraft, final Vec3 camera) {
		if (!VibrantsVisualsOptions.COLORED_LIGHT.get() || minecraft.level == null) {
			return new Vector4f();
		}

		BlockPos center = BlockPos.containing(camera);
		long time = minecraft.level.getGameTime();
		if (texture == null || time - lastUpdate >= 10 || center.distManhattan(origin.offset(SIZE / 2, SIZE / 2, SIZE / 2)) >= 4) {
			lastUpdate = time;
			origin = center.offset(-SIZE / 2, -SIZE / 2, -SIZE / 2);
			propagate(minecraft);
			upload();
		}
		return new Vector4f((float) (origin.getX() - camera.x), (float) (origin.getY() - camera.y), (float) (origin.getZ() - camera.z), 1.0F);
	}

	private static int index(final int x, final int y, final int z) {
		return ((y * SIZE + z) * SIZE + x) * 3;
	}

	private static void propagate(final Minecraft minecraft) {
		java.util.Arrays.fill(LEVELS, 0);
		BlockState[] states = new BlockState[SIZE * SIZE * SIZE];
		ArrayDeque<int[]> queue = new ArrayDeque<>();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int y = 0; y < SIZE; y++) {
			for (int z = 0; z < SIZE; z++) {
				for (int x = 0; x < SIZE; x++) {
					BlockState state = minecraft.level.getBlockState(pos.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z));
					states[index(x, y, z) / 3] = state;
					int emission = state.getLightEmission();
					if (emission > 0) {
						Vector3f color = DynamicLights.colorOf(BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath());
						int i = index(x, y, z);
						// 16 steps per light level: integer rounding no longer creates hue bands far from the source
						float max = Math.max(color.x, Math.max(color.y, color.z));
						LEVELS[i] = Math.round(emission * 16 * color.x / max);
						LEVELS[i + 1] = Math.round(emission * 16 * color.y / max);
						LEVELS[i + 2] = Math.round(emission * 16 * color.z / max);
						queue.add(new int[]{x, y, z});
					}
				}
			}
		}

		while (!queue.isEmpty()) {
			int[] cell = queue.poll();
			int from = index(cell[0], cell[1], cell[2]);
			for (Direction dir : DIRECTIONS) {
				int x = cell[0] + dir.getStepX();
				int y = cell[1] + dir.getStepY();
				int z = cell[2] + dir.getStepZ();
				if (x < 0 || y < 0 || z < 0 || x >= SIZE || y >= SIZE || z >= SIZE) {
					continue;
				}
				int to = index(x, y, z);
				BlockState state = states[to / 3];
				if (state.canOcclude()) {
					continue;
				}
				float r = 1.0F, g = 1.0F, b = 1.0F;
				if (state.getBlock() instanceof BeaconBeamBlock beam) {
					int rgb = beam.getColor();
					r = ((rgb >> 16) & 255) / 255.0F;
					g = ((rgb >> 8) & 255) / 255.0F;
					b = (rgb & 255) / 255.0F;
				}
				// Attenuate all channels by the same ratio so the hue is kept while the light fades
				int level = Math.max(LEVELS[from], Math.max(LEVELS[from + 1], LEVELS[from + 2]));
				if (level <= 16) {
					continue;
				}
				float fade = (level - 16) / (float) level;
				int nr = (int) (LEVELS[from] * fade * r);
				int ng = (int) (LEVELS[from + 1] * fade * g);
				int nb = (int) (LEVELS[from + 2] * fade * b);
				if (nr > LEVELS[to] || ng > LEVELS[to + 1] || nb > LEVELS[to + 2]) {
					LEVELS[to] = Math.max(LEVELS[to], nr);
					LEVELS[to + 1] = Math.max(LEVELS[to + 1], ng);
					LEVELS[to + 2] = Math.max(LEVELS[to + 2], nb);
					queue.add(new int[]{x, y, z});
				}
			}
		}
	}

	private static void upload() {
		if (texture == null) {
			GpuDevice device = RenderSystem.getDevice();
			texture = device.createTexture(
				"Vibrants Visuals colored light", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA8_UNORM, SIZE * SIZE, SIZE, 1, 1
			);
			textureView = device.createTextureView(texture);
		}
		ByteBuffer pixels = MemoryUtil.memAlloc(SIZE * SIZE * SIZE * 4);
		for (int y = 0; y < SIZE; y++) {
			for (int z = 0; z < SIZE; z++) {
				for (int x = 0; x < SIZE; x++) {
					int i = index(x, y, z);
					int r = Math.min(Math.max(LEVELS[i], 0), 255);
					int g = Math.min(Math.max(LEVELS[i + 1], 0), 255);
					int b = Math.min(Math.max(LEVELS[i + 2], 0), 255);
					pixels.putInt(((z * SIZE * SIZE) + x + y * SIZE) * 4, 0xFF000000 | b << 16 | g << 8 | r);
				}
			}
		}
		try {
			RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, pixels, 0, 0, 0, 0, SIZE * SIZE, SIZE);
		} finally {
			MemoryUtil.memFree(pixels);
		}
	}
}
