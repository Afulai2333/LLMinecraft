package dev.llmcraft.client.actions;

import com.google.gson.JsonObject;
import dev.llmcraft.client.ClientAction;
import dev.llmcraft.client.tools.StateSnapshot;
import dev.llmcraft.client.util.ItemUtil;
import dev.llmcraft.mcp.ToolResult;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Uses the vanilla recipe book to place a recipe and shift-click the result. Works with the
 * 2x2 inventory grid, or with whatever crafting menu is currently open (for example after
 * right-clicking a crafting table with {@code use}).
 */
public final class CraftAction implements ClientAction {
	private final String query;
	private final int times;
	private final boolean closeAfter;
	private RecipeDisplayId recipe;
	private String resultName = "item";
	private int age;
	private int phase;
	private int wait;
	private int crafted;
	private int failures;

	public CraftAction(String query, int times, boolean closeAfter) {
		this.query = query;
		this.times = Math.clamp(times, 1, 64);
		this.closeAfter = closeAfter;
	}

	@Override
	public ToolResult start(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (player == null || minecraft.level == null || minecraft.gameMode == null) {
			return ToolResult.error("No world is loaded.");
		}
		if (minecraft.gui.screen() != null
				&& !(minecraft.gui.screen() instanceof CraftingScreen)
				&& !(minecraft.gui.screen() instanceof InventoryScreen)) {
			return ToolResult.error("A different container is open (" + minecraft.gui.screen().getClass().getSimpleName()
					+ "); close it and open a crafting table or the inventory.");
		}
		RecipeDisplayEntry entry = findRecipe(minecraft, player);
		if (entry == null) {
			return ToolResult.error("No known recipe produces '" + query
					+ "'. Check the item name, or obtain the recipe first.");
		}
		recipe = entry.id();
		ItemStack output = entry.display().result().resolveForFirstStack(
				SlotDisplayContext.fromLevel(minecraft.level));
		if (!output.isEmpty()) {
			resultName = output.getHoverName().getString();
		}
		return null;
	}

	@Override
	public ToolResult tick(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (player == null || minecraft.level == null || minecraft.gameMode == null) {
			return ToolResult.error("The world was closed while crafting.");
		}
		age++;
		if (wait > 0) {
			wait--;
			return null;
		}
		AbstractContainerMenu menu = player.containerMenu;
		if (menu == null) {
			return ToolResult.error("No container menu is available.");
		}

		switch (phase) {
			case 0 -> {
				minecraft.gameMode.handlePlaceRecipe(menu.containerId, recipe, false);
				wait = 4;
				phase = 1;
				return null;
			}
			case 1 -> {
				ItemStack output = menu.getSlot(0).getItem();
				if (output.isEmpty()) {
					if (++failures >= 2) {
						String hint = menu == player.inventoryMenu
								? " Missing ingredients, or this recipe needs a 3x3 crafting table: use the table first, then call craft again."
								: " Missing ingredients.";
						return ToolResult.error("The recipe book could not place " + resultName + "." + hint);
					}
					phase = 0;
					wait = 3;
					return null;
				}
				resultName = output.getHoverName().getString();
				minecraft.gameMode.handleContainerInput(menu.containerId, 0, 0,
						ContainerInput.QUICK_MOVE, player);
				crafted++;
				wait = 4;
				if (crafted >= times) {
					phase = 3;
				} else {
					phase = 2;
				}
				return null;
			}
			case 2 -> {
				if (crafted >= times) {
					phase = 3;
					return null;
				}
				minecraft.gameMode.handlePlaceRecipe(menu.containerId, recipe, false);
				wait = 4;
				phase = 1;
				return null;
			}
			case 3 -> {
				if (closeAfter) {
					player.closeContainer();
					minecraft.gui.setScreen(null);
				}
				JsonObject json = new JsonObject();
				json.addProperty("ok", true);
				json.addProperty("message", "Crafted " + crafted + "x " + resultName + ".");
				json.addProperty("item", resultName);
				json.addProperty("crafted", crafted);
				json.add("held", StateSnapshot.item(player.getMainHandItem(),
						player.getInventory().getSelectedSlot()));
				return ToolResult.text(json.toString());
			}
			default -> {
				return ToolResult.error("Crafting entered an invalid state.");
			}
		}
	}

	private RecipeDisplayEntry findRecipe(Minecraft minecraft, LocalPlayer player) {
		List<RecipeDisplayEntry> entries = new ArrayList<>();
		for (RecipeCollection collection : player.getRecipeBook().getCollections()) {
			entries.addAll(collection.getRecipes());
		}
		RecipeDisplayEntry best = null;
		for (RecipeDisplayEntry entry : entries) {
			ItemStack output = entry.display().result().resolveForFirstStack(
					SlotDisplayContext.fromLevel(minecraft.level));
			if (!output.isEmpty() && ItemUtil.matches(output, query)) {
				best = entry;
				break;
			}
		}
		return best;
	}

	@Override
	public void cleanup(Minecraft minecraft) {
		if (closeAfter && minecraft.player != null) {
			minecraft.player.closeContainer();
			minecraft.gui.setScreen(null);
		}
	}

	@Override
	public String name() {
		return "craft";
	}
}
