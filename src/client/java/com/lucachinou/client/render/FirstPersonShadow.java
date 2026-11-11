package com.lucachinou.client.render;

import com.lucachinou.client.VibrantsVisualsOptions;
import com.mojang.blaze3d.vertex.PoseStack;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.phase.FeatureRenderPhase;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.phys.Vec3;

/**
 * In first person the game never builds the local player model. It is built here into separate submit collections
 * that only the shadow pass draws.
 */
public final class FirstPersonShadow {
	private static final int ORDER_OFFSET = 1_000_000;
	private static final SubmitNodeStorage STORAGE = new SubmitNodeStorage();
	private static final Set<FeatureRenderPhase<?>> SHADOW_ONLY_PHASES = Collections.newSetFromMap(new IdentityHashMap<>());

	private FirstPersonShadow() {
	}

	public static boolean isShadowOnly(final FeatureRenderPhase<?> phase) {
		return SHADOW_ONLY_PHASES.contains(phase);
	}

	public static void submit(final LevelRenderState levelState, final SubmitNodeStorage mainStorage, final boolean improvedTransparency) {
		SHADOW_ONLY_PHASES.clear();
		Minecraft minecraft = Minecraft.getInstance();
		Camera camera = minecraft.gameRenderer.mainCamera();
		LocalPlayer player = minecraft.player;
		if (!VibrantsVisualsOptions.SHADOWS.get() || player == null || minecraft.level == null
			|| camera.isDetached() || camera.entity() != player || player.isSleeping() || player.isInvisible()) {
			return;
		}

		EntityRenderDispatcher dispatcher = minecraft.levelRenderer.entityRenderDispatcher();
		float partialTick = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(!minecraft.level.tickRateManager().isEntityFrozen(player));
		EntityRenderState state = dispatcher.extractEntity(player, partialTick);
		Vec3 cameraPos = levelState.cameraRenderState.pos;

		STORAGE.setUseImprovedTransparency(improvedTransparency);
		dispatcher.submit(state, levelState.cameraRenderState, state.x - cameraPos.x, state.y - cameraPos.y, state.z - cameraPos.z, new PoseStack(), STORAGE);

		for (Int2ObjectMap.Entry<SubmitNodeCollection> entry : STORAGE.getSubmitsPerOrder().int2ObjectEntrySet()) {
			SubmitNodeCollection collection = entry.getValue();
			SHADOW_ONLY_PHASES.addAll(collection.allPhases());
			mainStorage.getSubmitsPerOrder().put(ORDER_OFFSET + entry.getIntKey(), collection);
		}
		// The collections now belong to the main storage, new ones are created next frame
		STORAGE.getSubmitsPerOrder().clear();
	}
}
