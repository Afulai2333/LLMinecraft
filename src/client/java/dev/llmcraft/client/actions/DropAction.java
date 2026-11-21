package dev.llmcraft.client.actions;

import com.google.gson.JsonObject;
import dev.llmcraft.client.ClientAction;
import dev.llmcraft.client.tools.StateSnapshot;
import dev.llmcraft.mcp.ToolResult;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/** Drops the held stack (or one item from it). */
public final class DropAction implements ClientAction {
	private final boolean wholeStack;
	private int age;

	public DropAction(boolean wholeStack) {
		this.wholeStack = wholeStack;
	}

	@Override
	public ToolResult start(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (player == null) {
			return ToolResult.error("No world is loaded.");
		}
		if (minecraft.gameMode == null) {
			return ToolResult.error("No game mode is available.");
		}
		if (player.getMainHandItem().isEmpty()) {
			return ToolResult.error("The main hand is empty.");
		}
		minecraft.gameMode.dropItem(player, wholeStack);
		return null;
	}

	@Override
	public ToolResult tick(Minecraft minecraft) {
		if (++age < 3) {
			return null;
		}
		LocalPlayer player = minecraft.player;
		JsonObject result = new JsonObject();
		result.addProperty("ok", true);
		result.addProperty("message", wholeStack ? "Dropped the held stack." : "Dropped one item.");
		result.add("held", StateSnapshot.item(player.getMainHandItem(), player.getInventory().getSelectedSlot()));
		return ToolResult.text(result.toString());
	}

	@Override
	public String name() {
		return "drop";
	}
}
