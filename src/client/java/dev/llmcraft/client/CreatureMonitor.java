package dev.llmcraft.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.llmcraft.client.tools.StateSnapshot;
import dev.llmcraft.config.LLMinecraftConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Detects all non-player living creatures (not only hostiles), classifies food animals, and
 * pushes a food hint when the player is hungry. This is what lets the agent find cows,
 * pigs, sheep, chickens and fish without polling scan_entities.
 */
public final class CreatureMonitor {
	private static final Set<String> FOOD_TYPES = Set.of(
			"minecraft:cow", "minecraft:mooshroom", "minecraft:pig", "minecraft:sheep",
			"minecraft:chicken", "minecraft:rabbit", "minecraft:cod", "minecraft:salmon",
			"minecraft:tropical_fish"
	);

	private static int timer;
	private static String lastFoodKey = "";
	private static int foodWarnTimer;

	private CreatureMonitor() {
	}

	public static void tick(Minecraft minecraft) {
		LLMinecraftConfig config = LLMinecraftConfig.get();
		if (!config.creatureDetection || minecraft.player == null || minecraft.level == null) {
			return;
		}
		LocalPlayer player = minecraft.player;
		if (++timer < 20) {
			return;
		}
		timer = 0;
		if (player.getFoodData().getFoodLevel() > 14) {
			lastFoodKey = "";
			return;
		}
		List<Creature> food = foodSources(minecraft, player, config.creatureRadius);
		if (food.isEmpty()) {
			lastFoodKey = "";
			return;
		}
		Creature nearest = food.getFirst();
		String key = nearest.type + ":" + (int) nearest.distance;
		if (!key.equals(lastFoodKey)) {
			lastFoodKey = key;
			JsonObject data = new JsonObject();
			data.addProperty("message", "Food animal nearby: " + nearest.name + " at "
					+ StateSnapshot.round(nearest.distance) + " blocks");
			data.addProperty("entity_id", nearest.entity.getId());
			data.addProperty("type", nearest.type);
			data.addProperty("name", nearest.name);
			data.addProperty("distance", StateSnapshot.round(nearest.distance));
			EventLog.record("food_nearby", "info", data);
		}
		if (++foodWarnTimer >= 80) {
			foodWarnTimer = 0;
			player.sendOverlayMessage(net.minecraft.network.chat.Component.literal(
					"Food: " + nearest.name + " " + StateSnapshot.round(nearest.distance) + "m"));
		}
	}

	public static JsonObject snapshot() {
		JsonObject json = new JsonObject();
		Minecraft minecraft = ClientBridge.mc();
		LocalPlayer player = minecraft == null ? null : minecraft.player;
		if (player == null || minecraft.level == null) {
			json.addProperty("count", 0);
			json.add("creatures", new JsonArray());
			json.add("food_sources", new JsonArray());
			return json;
		}
		LLMinecraftConfig config = LLMinecraftConfig.get();
		List<Creature> creatures = scan(minecraft, player, config.creatureRadius);
		json.addProperty("count", creatures.size());
		JsonArray all = new JsonArray();
		JsonArray food = new JsonArray();
		for (int i = 0; i < Math.min(creatures.size(), 16); i++) {
			Creature creature = creatures.get(i);
			JsonObject item = creature.toJson();
			all.add(item);
			if (creature.food) {
				food.add(item.deepCopy());
			}
		}
		json.add("creatures", all);
		json.add("food_sources", food);
		return json;
	}

	public static List<Creature> scan(Minecraft minecraft, LocalPlayer player, double radius) {
		AABB box = player.getBoundingBox().inflate(radius);
		List<Creature> result = new ArrayList<>();
		for (Entity entity : minecraft.level.getEntities(player, box, Entity::isAlive)) {
			if (entity instanceof Player || !(entity instanceof LivingEntity living)) {
				continue;
			}
			String type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
			boolean hostile = entity instanceof Enemy;
			boolean food = FOOD_TYPES.contains(type);
			result.add(new Creature(entity, living, type, entity.getName().getString(),
					Math.sqrt(entity.distanceToSqr(player)), hostile, food));
		}
		result.sort(Comparator.comparingDouble(Creature::distance));
		return result;
	}

	public static List<Creature> foodSources(Minecraft minecraft, LocalPlayer player, double radius) {
		List<Creature> food = new ArrayList<>();
		for (Creature creature : scan(minecraft, player, radius)) {
			if (creature.food) {
				food.add(creature);
			}
		}
		return food;
	}

	public record Creature(Entity entity, LivingEntity living, String type, String name,
			double distance, boolean hostile, boolean food) {
		public JsonObject toJson() {
			JsonObject json = new JsonObject();
			json.addProperty("entity_id", entity.getId());
			json.addProperty("type", type);
			json.addProperty("name", name);
			json.addProperty("distance", StateSnapshot.round(distance));
			json.addProperty("health", StateSnapshot.round(living.getHealth()));
			json.addProperty("max_health", StateSnapshot.round(living.getMaxHealth()));
			json.addProperty("hostile", hostile);
			json.addProperty("food", food);
			json.addProperty("x", StateSnapshot.round(entity.getX()));
			json.addProperty("y", StateSnapshot.round(entity.getY()));
			json.addProperty("z", StateSnapshot.round(entity.getZ()));
			return json;
		}
	}
}
