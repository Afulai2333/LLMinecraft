package dev.llmcraft.client.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.llmcraft.client.ClientBridge;
import dev.llmcraft.client.util.BlockUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Block and entity scanning helpers. */
public final class ScanTools {
	private ScanTools() {
	}

	public static JsonObject scanBlocks(int radius, String query, int limit) {
		Minecraft minecraft = ClientBridge.mc();
		LocalPlayer player = minecraft.player;
		Level level = minecraft.level;
		if (player == null || level == null) {
			throw new IllegalStateException("No world is loaded.");
		}
		int r = Math.clamp(radius, 1, 64);
		int max = Math.clamp(limit, 1, 128);
		BlockPos center = player.blockPosition();
		TagKey<Block> tag = null;
		if (query != null && query.startsWith("#")) {
			try {
				tag = TagKey.create(Registries.BLOCK,
						Identifier.parse(query.substring(1)));
			} catch (Exception ignored) {
			}
		}

		List<Match> matches = new ArrayList<>();
		Map<String, Count> counts = new HashMap<>();
		int scanned = 0;
		int vertical = Math.min(r, 16);
		for (int dx = -r; dx <= r; dx++) {
			for (int dz = -r; dz <= r; dz++) {
				if (dx * dx + dz * dz > r * r) {
					continue;
				}
				for (int dy = -vertical; dy <= vertical; dy++) {
					BlockPos pos = center.offset(dx, dy, dz);
					if (!level.isLoaded(pos)) {
						continue;
					}
					BlockState state = level.getBlockState(pos);
					if (state.isAir()) {
						continue;
					}
					scanned++;
					String id = BlockUtil.id(state);
					double distance = Math.sqrt(player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)));
					Count count = counts.computeIfAbsent(id, key -> new Count(pos.immutable()));
					count.amount++;
					if (query != null && !query.isBlank() && matches(state, id, tag, query)) {
						matches.add(new Match(pos.immutable(), id, distance));
					}
				}
			}
		}
		matches.sort(Comparator.comparingDouble(Match::distance));

		JsonObject result = new JsonObject();
		result.addProperty("ok", true);
		result.addProperty("radius", r);
		result.addProperty("scanned", scanned);
		result.addProperty("query", query == null ? "" : query);
		JsonArray found = new JsonArray();
		for (int i = 0; i < Math.min(max, matches.size()); i++) {
			Match match = matches.get(i);
			JsonObject json = new JsonObject();
			json.addProperty("block", match.id);
			json.addProperty("x", match.pos.getX());
			json.addProperty("y", match.pos.getY());
			json.addProperty("z", match.pos.getZ());
			json.addProperty("distance", StateSnapshot.round(match.distance));
			json.add("properties", properties(level.getBlockState(match.pos)));
			found.add(json);
		}
		result.addProperty("match_count", matches.size());
		result.add("matches", found);

		if (query == null || query.isBlank()) {
			List<Map.Entry<String, Count>> sorted = new ArrayList<>(counts.entrySet());
			sorted.sort((a, b) -> Integer.compare(b.getValue().amount, a.getValue().amount));
			JsonArray common = new JsonArray();
			for (int i = 0; i < Math.min(30, sorted.size()); i++) {
				Map.Entry<String, Count> entry = sorted.get(i);
				JsonObject json = new JsonObject();
				json.addProperty("block", entry.getKey());
				json.addProperty("count", entry.getValue().amount);
				json.addProperty("x", entry.getValue().sample.getX());
				json.addProperty("y", entry.getValue().sample.getY());
				json.addProperty("z", entry.getValue().sample.getZ());
				common.add(json);
			}
			result.add("common_blocks", common);
		}
		return result;
	}

	public static JsonObject blockAt(int x, int y, int z) {
		Minecraft minecraft = ClientBridge.mc();
		Level level = minecraft.level;
		LocalPlayer player = minecraft.player;
		if (level == null || player == null) {
			throw new IllegalStateException("No world is loaded.");
		}
		BlockPos pos = new BlockPos(x, y, z);
		if (!level.isLoaded(pos)) {
			throw new IllegalStateException("Position is not loaded.");
		}
		BlockState state = level.getBlockState(pos);
		JsonObject json = new JsonObject();
		json.addProperty("ok", true);
		json.addProperty("block", BlockUtil.id(state));
		json.addProperty("x", x);
		json.addProperty("y", y);
		json.addProperty("z", z);
		json.addProperty("air", state.isAir());
		json.addProperty("solid", state.isSolid());
		json.addProperty("replaceable", state.canBeReplaced());
		json.addProperty("hardness", state.getDestroySpeed(level, pos));
		json.addProperty("requires_correct_tool", state.requiresCorrectToolForDrops());
		json.addProperty("light", level.getMaxLocalRawBrightness(pos));
		json.addProperty("distance", StateSnapshot.round(Math.sqrt(
				player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)))));
		json.add("properties", properties(state));
		BlockEntity blockEntity = level.getBlockEntity(pos);
		if (blockEntity != null) {
			json.addProperty("block_entity", BuiltInRegistries.BLOCK_ENTITY_TYPE
					.getKey(blockEntity.getType()).toString());
		}
		return json;
	}

	private static boolean matches(BlockState state, String id, TagKey<Block> tag, String query) {
		if (tag != null && state.is(tag)) {
			return true;
		}
		String needle = query.toLowerCase(Locale.ROOT).trim();
		if (needle.startsWith("#")) {
			needle = needle.substring(1);
		}
		if (needle.startsWith("minecraft:")) {
			needle = needle.substring("minecraft:".length());
		}
		String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
		return path.equals(needle) || path.contains(needle)
				|| state.getBlock().getName().getString().toLowerCase(Locale.ROOT).contains(needle);
	}

	private static JsonObject properties(BlockState state) {
		JsonObject json = new JsonObject();
		for (Property<?> property : state.getProperties()) {
			json.addProperty(property.getName(), state.getValue(property).toString());
		}
		return json;
	}

	private record Match(BlockPos pos, String id, double distance) {
	}

	private static final class Count {
		final BlockPos sample;
		int amount;

		Count(BlockPos sample) {
			this.sample = sample;
		}
	}
}
