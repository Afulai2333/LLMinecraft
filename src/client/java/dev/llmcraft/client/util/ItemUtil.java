package dev.llmcraft.client.util;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Locale;

/** Item-name matching and hotbar helpers. */
public final class ItemUtil {
	private ItemUtil() {
	}

	public static String id(ItemStack stack) {
		return stack.isEmpty() ? "minecraft:air" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
	}

	public static boolean matches(ItemStack stack, String query) {
		if (stack.isEmpty() || query == null || query.isBlank()) {
			return false;
		}
		String needle = query.toLowerCase(Locale.ROOT).trim();
		if (needle.startsWith("#")) {
			needle = needle.substring(1);
		}
		if (needle.startsWith("minecraft:")) {
			needle = needle.substring("minecraft:".length());
		}
		String id = id(stack);
		String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
		String name = stack.getHoverName().getString().toLowerCase(Locale.ROOT);
		return path.equals(needle) || path.contains(needle) || name.contains(needle);
	}

	public static int findInHotbar(LocalPlayer player, String query) {
		for (int i = 0; i < 9; i++) {
			if (matches(player.getInventory().getItem(i), query)) {
				return i;
			}
		}
		return -1;
	}

	public static int count(LocalPlayer player, String query) {
		int total = 0;
		for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
			ItemStack stack = player.getInventory().getItem(i);
			if (matches(stack, query)) {
				total += stack.getCount();
			}
		}
		return total;
	}

	/** Chooses the best hotbar tool for a block, preferring correct tools. */
	public static int bestToolSlot(LocalPlayer player, BlockState state) {
		int best = -1;
		float bestScore = -1.0f;
		boolean bestCorrect = false;
		for (int i = 0; i < 9; i++) {
			ItemStack stack = player.getInventory().getItem(i);
			if (stack.isEmpty()) {
				continue;
			}
			boolean correct = stack.isCorrectToolForDrops(state);
			float speed = stack.getDestroySpeed(state);
			float score = speed + (correct ? 100.0f : 0.0f);
			if (score > bestScore || (correct && !bestCorrect)) {
				best = i;
				bestScore = score;
				bestCorrect = correct;
			}
		}
		return best;
	}

	public static boolean isBlockItem(ItemStack stack) {
		return !stack.isEmpty() && stack.getItem() instanceof BlockItem;
	}

	public static String blockItemId(ItemStack stack) {
		if (stack.getItem() instanceof BlockItem blockItem) {
			return BuiltInRegistries.BLOCK.getKey(blockItem.getBlock()).toString();
		}
		return "minecraft:air";
	}
}
