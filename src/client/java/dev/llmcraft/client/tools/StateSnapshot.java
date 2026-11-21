package dev.llmcraft.client.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.llmcraft.client.ClientBridge;
import dev.llmcraft.client.util.BlockUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Builds the JSON returned by {@code get_state} and related inspection tools. */
public final class StateSnapshot {
	private StateSnapshot() {
	}

	public static JsonObject capture() {
		Minecraft minecraft = ClientBridge.mc();
		LocalPlayer player = minecraft.player;
		Level level = minecraft.level;
		if (player == null || level == null) {
			throw new IllegalStateException("No world is loaded. Join a singleplayer world or a server first.");
		}

		JsonObject root = new JsonObject();
		root.addProperty("ok", true);

		JsonObject p = new JsonObject();
		p.addProperty("name", player.getName().getString());
		p.addProperty("uuid", player.getUUID().toString());
		p.addProperty("dimension", level.dimension().identifier().toString());
		p.addProperty("x", round(player.getX()));
		p.addProperty("y", round(player.getY()));
		p.addProperty("z", round(player.getZ()));
		p.addProperty("block_x", player.blockPosition().getX());
		p.addProperty("block_y", player.blockPosition().getY());
		p.addProperty("block_z", player.blockPosition().getZ());
		p.addProperty("yaw", round(player.getYRot()));
		p.addProperty("pitch", round(player.getXRot()));
		p.addProperty("health", round(player.getHealth()));
		p.addProperty("max_health", round(player.getMaxHealth()));
		p.addProperty("absorption", round(player.getAbsorptionAmount()));
		p.addProperty("food", player.getFoodData().getFoodLevel());
		p.addProperty("saturation", round(player.getFoodData().getSaturationLevel()));
		p.addProperty("air", player.getAirSupply());
		p.addProperty("max_air", player.getMaxAirSupply());
		p.addProperty("xp_level", player.experienceLevel);
		p.addProperty("xp_progress", round(player.experienceProgress));
		p.addProperty("game_mode", minecraft.gameMode == null ? "unknown" : minecraft.gameMode.getPlayerMode().getName());
		p.addProperty("on_ground", player.onGround());
		p.addProperty("in_water", player.isInWater());
		p.addProperty("in_lava", player.isInLava());
		p.addProperty("sprinting", player.isSprinting());
		p.addProperty("sneaking", player.isShiftKeyDown());
		p.addProperty("sleeping", player.isSleeping());
		p.addProperty("using_item", player.isUsingItem());
		p.addProperty("dead", player.isDeadOrDying());
		p.addProperty("velocity_x", round(player.getDeltaMovement().x));
		p.addProperty("velocity_y", round(player.getDeltaMovement().y));
		p.addProperty("velocity_z", round(player.getDeltaMovement().z));
		root.add("player", p);

		JsonObject world = new JsonObject();
		long time = level.getOverworldClockTime();
		long dayTime = Math.floorMod(time, 24000L);
		world.addProperty("time_of_day", dayTime);
		world.addProperty("day", time / 24000L);
		world.addProperty("phase", phase(dayTime));
		world.addProperty("raining", level.isRaining());
		world.addProperty("thundering", level.isThundering());
		world.addProperty("rain_level", round(level.getRainLevel(1.0f)));
		world.addProperty("difficulty", level.getDifficulty().getSerializedName());
		world.addProperty("light", level.getMaxLocalRawBrightness(player.blockPosition()));
		String biome = level.getBiome(player.blockPosition()).unwrapKey()
				.map(key -> key.identifier().toString()).orElse("unknown");
		world.addProperty("biome", biome);
		world.addProperty("standing_on", BlockUtil.id(level.getBlockState(player.blockPosition().below())));
		world.addProperty("feet_block", BlockUtil.id(level.getBlockState(player.blockPosition())));
		world.addProperty("head_block", BlockUtil.id(level.getBlockState(player.blockPosition().above())));
		root.add("world", world);

		root.add("held", item(player.getMainHandItem(), player.getInventory().getSelectedSlot()));
		root.add("offhand", item(player.getOffhandItem(), Inventory.SLOT_OFFHAND));
		root.add("inventory", inventory(player.getInventory()));
		root.add("view", view(minecraft, level, player));
		root.add("nearby_entities", nearbyEntities(level, player, 24.0, 16));
		root.add("threats", dev.llmcraft.client.ThreatMonitor.snapshot());
		root.add("creatures", dev.llmcraft.client.CreatureMonitor.snapshot());
		root.add("effects", effects(player));
		root.addProperty("screen", minecraft.gui.screen() == null ? "" : minecraft.gui.screen().getClass().getSimpleName());
		root.addProperty("mcp_queued_actions", ClientBridge.queueSize());
		return root;
	}

	public static JsonObject inventory(Inventory inventory) {
		JsonObject json = new JsonObject();
		json.addProperty("selected_hotbar_slot", inventory.getSelectedSlot());
		JsonArray items = new JsonArray();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (!stack.isEmpty()) {
				JsonObject entry = item(stack, i);
				if (inventory.isHotbarSlot(i)) {
					entry.addProperty("hotbar", true);
					entry.addProperty("hotbar_slot", i);
				}
				items.add(entry);
			}
		}
		json.add("items", items);
		json.add("hotbar", hotbar(inventory));
		json.add("armor", armor(inventory));
		return json;
	}

	public static JsonObject item(ItemStack stack, int slot) {
		JsonObject json = new JsonObject();
		json.addProperty("slot", slot);
		if (stack.isEmpty()) {
			json.addProperty("item", "minecraft:air");
			json.addProperty("count", 0);
			return json;
		}
		json.addProperty("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
		json.addProperty("name", stack.getHoverName().getString());
		json.addProperty("count", stack.getCount());
		if (stack.isDamageableItem()) {
			json.addProperty("damage", stack.getDamageValue());
			json.addProperty("max_damage", stack.getMaxDamage());
			json.addProperty("remaining", stack.getMaxDamage() - stack.getDamageValue());
		}
		return json;
	}

	public static JsonArray hotbar(Inventory inventory) {
		JsonArray array = new JsonArray();
		for (int i = 0; i < 9; i++) {
			array.add(item(inventory.getItem(i), i));
		}
		return array;
	}

	public static JsonArray armor(Inventory inventory) {
		JsonArray array = new JsonArray();
		for (EquipmentSlot slot : new EquipmentSlot[]{
				EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
		}) {
			array.add(item(inventory.player.getItemBySlot(slot), -1));
		}
		return array;
	}

	public static JsonObject view(Minecraft minecraft, Level level, LocalPlayer player) {
		JsonObject view = new JsonObject();
		HitResult hit = minecraft.hitResult;
		HitResult.Type type = hit == null ? null : hit.getType();
		view.addProperty("type", type == null ? "none" : type.name().toLowerCase());
		if (type == HitResult.Type.BLOCK && hit instanceof BlockHitResult blockHit) {
			BlockPos pos = blockHit.getBlockPos();
			BlockState state = level.getBlockState(pos);
			if (!state.isAir()) {
				JsonObject block = new JsonObject();
				block.addProperty("block", BlockUtil.id(state));
				block.addProperty("x", pos.getX());
				block.addProperty("y", pos.getY());
				block.addProperty("z", pos.getZ());
				block.addProperty("face", blockHit.getDirection().getName());
				block.addProperty("distance", round(Math.sqrt(player.getEyePosition().distanceToSqr(
						Vec3.atCenterOf(pos)))));
				view.add("block", block);
			}
		} else if (type == HitResult.Type.ENTITY && hit instanceof EntityHitResult entityHit) {
			view.add("entity", entity(entityHit.getEntity(), player));
		}
		return view;
	}

	public static JsonArray nearbyEntities(Level level, LocalPlayer player, double radius, int limit) {
		AABB box = player.getBoundingBox().inflate(radius);
		List<Entity> entities = new ArrayList<>(level.getEntities(player, box, Entity::isAlive));
		entities.sort(Comparator.comparingDouble(entity -> entity.distanceToSqr(player)));
		JsonArray array = new JsonArray();
		int count = 0;
		for (Entity entity : entities) {
			if (entity == player || count++ >= limit) {
				continue;
			}
			array.add(entity(entity, player));
		}
		return array;
	}

	public static JsonObject entity(Entity entity, LocalPlayer player) {
		JsonObject json = new JsonObject();
		json.addProperty("entity_id", entity.getId());
		json.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
		json.addProperty("name", entity.getName().getString());
		json.addProperty("x", round(entity.getX()));
		json.addProperty("y", round(entity.getY()));
		json.addProperty("z", round(entity.getZ()));
		json.addProperty("distance", round(Math.sqrt(entity.distanceToSqr(player))));
		json.addProperty("hostile", entity instanceof Enemy);
		json.addProperty("player", entity instanceof net.minecraft.world.entity.player.Player);
		if (entity instanceof LivingEntity living) {
			json.addProperty("health", round(living.getHealth()));
			json.addProperty("max_health", round(living.getMaxHealth()));
		}
		return json;
	}

	private static JsonArray effects(LocalPlayer player) {
		JsonArray array = new JsonArray();
		for (MobEffectInstance effect : player.getActiveEffects()) {
			JsonObject json = new JsonObject();
			String id = effect.getEffect().unwrapKey()
					.map(key -> key.identifier().toString()).orElse("unknown");
			json.addProperty("effect", id);
			json.addProperty("amplifier", effect.getAmplifier());
			json.addProperty("duration_ticks", effect.getDuration());
			array.add(json);
		}
		return array;
	}

	public static String phase(long timeOfDay) {
		if (timeOfDay < 1000 || timeOfDay >= 23000) {
			return "dawn";
		}
		if (timeOfDay < 12000) {
			return "day";
		}
		if (timeOfDay < 13800) {
			return "dusk";
		}
		return "night";
	}

	public static double round(double value) {
		return Math.round(value * 100.0) / 100.0;
	}

	public static BlockPos targetBlock(Minecraft minecraft) {
		if (minecraft.hitResult instanceof BlockHitResult hit) {
			return hit.getBlockPos();
		}
		return null;
	}

	public static Entity targetEntity(Minecraft minecraft) {
		if (minecraft.hitResult instanceof EntityHitResult hit) {
			return hit.getEntity();
		}
		return null;
	}

	public static Direction horizontalFacing(LocalPlayer player) {
		return Direction.fromYRot(player.getYRot());
	}
}
