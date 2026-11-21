package dev.llmcraft.client.actions;

import com.google.gson.JsonObject;
import dev.llmcraft.client.ClientAction;
import dev.llmcraft.mcp.ToolResult;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/** Closes an open container or GUI without dropping items. */
public final class CloseScreenAction implements ClientAction {
	private int age;

	@Override
	public ToolResult start(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (player == null) {
			return ToolResult.error("No world is loaded.");
		}
		String screen = minecraft.gui.screen() == null ? "" : minecraft.gui.screen().getClass().getSimpleName();
		player.closeContainer();
		minecraft.gui.setScreen(null);
		JsonObject json = new JsonObject();
		json.addProperty("ok", true);
		json.addProperty("message", screen.isEmpty() ? "No screen was open." : "Closed " + screen + ".");
		return ToolResult.text(json.toString());
	}

	@Override
	public ToolResult tick(Minecraft minecraft) {
		return ToolResult.text("{\"ok\":true}");
	}

	@Override
	public String name() {
		return "close_screen";
	}
}
