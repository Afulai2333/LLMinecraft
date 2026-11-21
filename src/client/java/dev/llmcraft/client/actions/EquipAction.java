package dev.llmcraft.client.actions;

import com.google.gson.JsonObject;
import dev.llmcraft.client.ClientAction;
import dev.llmcraft.client.tools.StateSnapshot;
import dev.llmcraft.client.util.ItemUtil;
import dev.llmcraft.mcp.ToolResult;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

/** Moves a matching item from the main inventory into the selected hotbar slot. */
public final class EquipAction implements ClientAction {
	private final String query;
	private int age;
	private int sourceSlot = -1;
	private int hotbarSlot = -1;

	public EquipAction(String query) {
		this.query = query;
	}

	@Override
	public ToolResult start(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (player == null) {
			return ToolResult.error("No world is loaded.");
		}
		hotbarSlot = player.getInventory().getSelectedSlot();
		int selectedHotbar = ItemUtil.findInHotbar(player, query);
		if (selectedHotbar >= 0 && selectedHotbar == hotbarSlot) {
			return immediate(player);
		}
		if (selectedHotbar >= 0) {
			player.getInventory().setSelectedSlot(selectedHotbar);
			hotbarSlot = selectedHotbar;
			return null;
		}
		AbstractContainerMenu menu = player.inventoryMenu;
		for (int i = 9; i < 36; i++) {
			ItemStack stack = menu.getSlot(i).getItem();
			if (ItemUtil.matches(stack, query)) {
				sourceSlot = i;
				break;
			}
		}
		if (sourceSlot < 0) {
			return ToolResult.error("No item matching '" + query + "' was found in the inventory.");
		}
		minecraft.gameMode.handleContainerInput(menu.containerId, sourceSlot, hotbarSlot,
				ContainerInput.SWAP, player);
		return null;
	}

	@Override
	public ToolResult tick(Minecraft minecraft) {
		if (++age < 3) {
			return null;
		}
		LocalPlayer player = minecraft.player;
		player.getInventory().setSelectedSlot(hotbarSlot);
		return immediate(player);
	}

	private ToolResult immediate(LocalPlayer player) {
		JsonObject json = new JsonObject();
		json.addProperty("ok", true);
		json.addProperty("message", "Equipped " + query + " in hotbar slot " + hotbarSlot + ".");
		json.addProperty("hotbar_slot", hotbarSlot);
		json.add("held", StateSnapshot.item(player.getMainHandItem(), hotbarSlot));
		return ToolResult.text(json.toString());
	}

	@Override
	public String name() {
		return "equip";
	}
}
