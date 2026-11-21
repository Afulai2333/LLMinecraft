package dev.llmcraft.client.actions;

import com.google.gson.JsonObject;
import dev.llmcraft.client.ClientAction;
import dev.llmcraft.client.tools.StateSnapshot;
import dev.llmcraft.client.util.InputController;
import dev.llmcraft.mcp.ToolResult;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Attacks one entity repeatedly, respecting the vanilla attack cooldown. */
public final class AttackAction implements ClientAction {
	private final Integer entityId;
	private final String entityQuery;
	private final int times;
	private int attacks;
	private int cooldown;
	private Entity target;
	private String targetName = "target";

	public AttackAction(Integer entityId, String entityQuery, int times) {
		this.entityId = entityId;
		this.entityQuery = entityQuery;
		this.times = Math.max(1, Math.min(times, 50));
	}

	@Override
	public ToolResult start(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (player == null || minecraft.level == null || minecraft.gameMode == null) {
			return ToolResult.error("No world is loaded.");
		}
		if (minecraft.gui.screen() != null) {
			return ToolResult.error("A GUI screen is open; close it before attacking.");
		}
		target = resolveTarget(minecraft, player);
		if (target == null) {
			return ToolResult.error(entityId != null
					? "No loaded entity with id " + entityId + "."
					: "No entity matching '" + entityQuery + "' within 24 blocks.");
		}
		targetName = target.getName().getString();
		return null;
	}

	@Override
	public ToolResult tick(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (player == null || minecraft.level == null || minecraft.gameMode == null) {
			return ToolResult.error("The world was closed while attacking.");
		}
		if (target == null || !target.isAlive() || target.isRemoved()) {
			return done(player, "Target is no longer alive.");
		}
		if (player.isDeadOrDying()) {
			return ToolResult.error("The player died while attacking.");
		}
		if (!player.isWithinEntityInteractionRange(target, 1.0)) {
			return ToolResult.error("Target moved out of reach; use goto or attack a closer entity.");
		}
		if (cooldown > 0) {
			cooldown--;
			return null;
		}
		InputController.lookAt(player, target.getBoundingBox().getCenter());
		minecraft.gameMode.attack(player, target);
		attacks++;
		cooldown = 12;
		if (attacks >= times) {
			return done(player, "Attacked " + targetName + " " + attacks + " time(s).");
		}
		return null;
	}

	private Entity resolveTarget(Minecraft minecraft, LocalPlayer player) {
		if (entityId != null) {
			return minecraft.level.getEntity(entityId);
		}
		AABB box = player.getBoundingBox().inflate(24.0);
		List<Entity> candidates = new ArrayList<>(minecraft.level.getEntities(player, box,
				e -> e.isAlive() && matches(e, entityQuery)));
		candidates.sort(Comparator.comparingDouble(e -> e.distanceToSqr(player)));
		return candidates.isEmpty() ? null : candidates.getFirst();
	}

	private boolean matches(Entity entity, String query) {
		if (entity == null || query == null || query.isBlank()) {
			return true;
		}
		String needle = query.toLowerCase();
		if (needle.startsWith("minecraft:")) {
			needle = needle.substring("minecraft:".length());
		}
		String type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
		return type.contains(needle)
				|| type.substring(type.indexOf(':') + 1).equals(needle)
				|| entity.getName().getString().toLowerCase().contains(needle);
	}

	private ToolResult done(LocalPlayer player, String message) {
		JsonObject json = new JsonObject();
		json.addProperty("ok", true);
		json.addProperty("message", message);
		json.addProperty("attacks", attacks);
		json.addProperty("target", targetName);
		if (target != null && target.isAlive() && target instanceof LivingEntity living) {
			json.addProperty("target_health", StateSnapshot.round(living.getHealth()));
		}
		json.addProperty("player_health", StateSnapshot.round(player.getHealth()));
		return ToolResult.text(json.toString());
	}

	@Override
	public void cleanup(Minecraft minecraft) {
		InputController.releaseMovement(minecraft);
	}

	@Override
	public String name() {
		return "attack";
	}
}
