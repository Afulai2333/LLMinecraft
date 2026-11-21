package dev.llmcraft.client.actions;

import com.google.gson.JsonObject;
import dev.llmcraft.client.ClientAction;
import dev.llmcraft.client.tools.StateSnapshot;
import dev.llmcraft.mcp.ToolResult;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/** Selects one of the nine hotbar slots. */
public final class SelectItemAction implements ClientAction {
	private final int slot;
	private int age;

	public SelectItemAction(int slot) {
		this.slot = slot;
	}

	@Override
	public ToolResult start(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (player == null) {
			return ToolResult.error("No world is loaded.");
		}
		if (slot < 0 || slot > 8) {
			return ToolResult.error("slot must be between 0 and 8.");
		}
		player.getInventory().setSelectedSlot(slot);
		return null;
	}

	@Override
	public ToolResult tick(Minecraft minecraft) {
		if (++age < 2) {
			return null;
		}
		LocalPlayer player = minecraft.player;
		JsonObject result = new JsonObject();
		result.addProperty("ok", true);
		result.addProperty("message", "Selected hotbar slot " + slot + ".");
		result.add("held", StateSnapshot.item(player.getMainHandItem(), slot));
		return ToolResult.text(result.toString());
	}

	@Override
	public String name() {
		return "select_item";
	}
}
