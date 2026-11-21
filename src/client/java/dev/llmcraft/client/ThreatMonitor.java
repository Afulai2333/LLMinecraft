package dev.llmcraft.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.llmcraft.LLMinecraft;
import dev.llmcraft.client.tools.StateSnapshot;
import dev.llmcraft.client.util.InputController;
import dev.llmcraft.client.util.ItemUtil;
import dev.llmcraft.config.LLMinecraftConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Periodically scans for hostile mobs, records threats for get_state/read_chat and warns the
 * human. When idle and safe, it can auto-melee a nearby hostile with a sword/axe. It never
 * hits creepers and never interrupts a running agent action.
 */
public final class ThreatMonitor {
	private static int scanTimer;
	private static int attackTimer;
	private static int warnTimer;
	private static String lastWarnKey = "";

	private ThreatMonitor() {
	}

	public static void tick(Minecraft minecraft) {
		LLMinecraftConfig config = LLMinecraftConfig.get();
		if (!config.threatDetection || minecraft.player == null || minecraft.level == null) {
			return;
		}
		LocalPlayer player = minecraft.player;
		if (++scanTimer < 10) {
			return;
		}
		scanTimer = 0;
		List<Threat> threats = scan(minecraft, player, config.threatRadius);
		if (threats.isEmpty()) {
			lastWarnKey = "";
			return;
		}
		Threat nearest = threats.getFirst();
		String warnKey = nearest.type + ":" + (int) nearest.distance;
		if (!warnKey.equals(lastWarnKey) && nearest.distance <= 10) {
			lastWarnKey = warnKey;
			String message = "Hostile nearby: " + nearest.name + " at "
					+ StateSnapshot.round(nearest.distance) + " blocks (" + nearest.type + ")";
			ChatLog.add("threat", message);
			JsonObject event = new JsonObject();
			event.addProperty("message", message);
			event.addProperty("entity_id", nearest.entity.getId());
			event.addProperty("type", nearest.type);
			event.addProperty("name", nearest.name);
			event.addProperty("distance", StateSnapshot.round(nearest.distance));
			event.addProperty("health", StateSnapshot.round(nearest.living.getHealth()));
			event.addProperty("targeting_player", nearest.targeting);
			event.addProperty("creeper", nearest.creeper);
			EventLog.record("threat", nearest.distance <= 4 ? "warning" : "info", event);
		}
		if (++warnTimer >= 20) {
			warnTimer = 0;
			player.sendOverlayMessage(net.minecraft.network.chat.Component.literal(
					"! " + nearest.name + " " + StateSnapshot.round(nearest.distance) + "m"));
		}
		if (config.autoDefend && ClientBridge.queueSize() == 0) {
			autoDefend(minecraft, player, nearest, config);
		}
	}

	private static void autoDefend(Minecraft minecraft, LocalPlayer player, Threat threat,
			LLMinecraftConfig config) {
		if (attackTimer > 0) {
			attackTimer--;
			return;
		}
		if (minecraft.gameMode == null || player.getHealth() <= 6.0f
				|| threat.distance > 3.2 || threat.creeper) {
			return;
		}
		if (!player.hasLineOfSight(threat.entity)) {
			return;
		}
		int weapon = findWeapon(player);
		if (weapon >= 0 && weapon != player.getInventory().getSelectedSlot()) {
			player.getInventory().setSelectedSlot(weapon);
		}
		InputController.lookAt(player, threat.entity.getBoundingBox().getCenter());
		TickFreezer.unfreezeForWork(minecraft);
		minecraft.gameMode.attack(player, threat.entity);
		attackTimer = 12;
		LLMinecraft.LOGGER.debug("Auto-defended against {} at {} blocks",
				threat.type, StateSnapshot.round(threat.distance));
	}

	private static int findWeapon(LocalPlayer player) {
		int fallback = -1;
		for (int i = 0; i < 9; i++) {
			String id = ItemUtil.id(player.getInventory().getItem(i));
			if (id.contains("sword") || id.contains("axe")) {
				return i;
			}
			if (fallback < 0 && !player.getInventory().getItem(i).isEmpty()) {
				fallback = i;
			}
		}
		return fallback;
	}

	public static List<Threat> scan(Minecraft minecraft, LocalPlayer player, double radius) {
		AABB box = player.getBoundingBox().inflate(radius);
		List<Threat> result = new ArrayList<>();
		for (Entity entity : minecraft.level.getEntities(player, box, Entity::isAlive)) {
			if (!(entity instanceof Enemy) || !(entity instanceof LivingEntity living)) {
				continue;
			}
			boolean targeting = entity instanceof Mob mob && mob.getTarget() == player;
			String type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
			result.add(new Threat(entity, living, type, entity.getName().getString(),
					Math.sqrt(entity.distanceToSqr(player)), type.contains("creeper"), targeting));
		}
		result.sort(Comparator.comparingDouble(Threat::distance));
		return result;
	}

	public static JsonObject snapshot() {
		JsonObject json = new JsonObject();
		Minecraft minecraft = ClientBridge.mc();
		LocalPlayer player = minecraft == null ? null : minecraft.player;
		if (player == null || minecraft.level == null) {
			json.addProperty("level", "none");
			json.add("hostiles", new JsonArray());
			return json;
		}
		LLMinecraftConfig config = LLMinecraftConfig.get();
		List<Threat> threats = scan(minecraft, player, config.threatRadius);
		json.addProperty("level", threats.isEmpty() ? "none"
				: threats.getFirst().distance <= 4 ? "danger"
				: threats.getFirst().distance <= 8 ? "near" : "far");
		json.addProperty("count", threats.size());
		JsonArray array = new JsonArray();
		for (int i = 0; i < Math.min(threats.size(), 8); i++) {
			Threat threat = threats.get(i);
			JsonObject item = new JsonObject();
			item.addProperty("entity_id", threat.entity.getId());
			item.addProperty("type", threat.type);
			item.addProperty("name", threat.name);
			item.addProperty("distance", StateSnapshot.round(threat.distance));
			item.addProperty("health", StateSnapshot.round(threat.living.getHealth()));
			item.addProperty("max_health", StateSnapshot.round(threat.living.getMaxHealth()));
			item.addProperty("targeting_player", threat.targeting);
			item.addProperty("creeper", threat.creeper);
			item.addProperty("x", StateSnapshot.round(threat.entity.getX()));
			item.addProperty("y", StateSnapshot.round(threat.entity.getY()));
			item.addProperty("z", StateSnapshot.round(threat.entity.getZ()));
			array.add(item);
		}
		json.add("hostiles", array);
		return json;
	}

	private record Threat(Entity entity, LivingEntity living, String type, String name,
			double distance, boolean creeper, boolean targeting) {
	}
}
