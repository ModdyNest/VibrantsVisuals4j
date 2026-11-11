package com.lucachinou.client.render;

import com.lucachinou.client.VibrantsVisualsOptions;
import java.util.ArrayDeque;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
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

	private static @Nullable DynamicTexture texture;
	private static final int[] LEVELS = new int[SIZE * SIZE * SIZE * 3];
	private static long lastUpdate = Long.MIN_VALUE;
	private static BlockPos origin = BlockPos.ZERO;

	private ColoredLightVolume() {
	}

	public static @Nullable DynamicTexture texture() {
		return texture;
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
						LEVELS[i] = Math.round(emission * color.x);
						LEVELS[i + 1] = Math.round(emission * color.y);
						LEVELS[i + 2] = Math.round(emission * color.z);
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
					int rgb = beam.getColor().getTextureDiffuseColor();
					r = ((rgb >> 16) & 255) / 255.0F;
					g = ((rgb >> 8) & 255) / 255.0F;
					b = (rgb & 255) / 255.0F;
				}
				int nr = (int) ((LEVELS[from] - 1) * r);
				int ng = (int) ((LEVELS[from + 1] - 1) * g);
				int nb = (int) ((LEVELS[from + 2] - 1) * b);
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
			texture = new DynamicTexture(() -> "Vibrants Visuals colored light", SIZE * SIZE, SIZE, true);
		}
		for (int y = 0; y < SIZE; y++) {
			for (int z = 0; z < SIZE; z++) {
				for (int x = 0; x < SIZE; x++) {
					int i = index(x, y, z);
					int r = Math.max(LEVELS[i], 0) * 17;
					int g = Math.max(LEVELS[i + 1], 0) * 17;
					int b = Math.max(LEVELS[i + 2], 0) * 17;
					texture.getPixels().setPixelABGR(x + y * SIZE, z, 0xFF000000 | b << 16 | g << 8 | r);
				}
			}
		}
		texture.upload();
	}
}
