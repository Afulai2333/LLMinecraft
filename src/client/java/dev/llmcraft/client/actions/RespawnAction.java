package dev.llmcraft.client.actions;

import com.google.gson.JsonObject;
import dev.llmcraft.client.ClientAction;
import dev.llmcraft.client.tools.StateSnapshot;
import dev.llmcraft.mcp.ToolResult;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/** Respawns a dead player. */
public final class RespawnAction implements ClientAction {
	private int age;

	@Override
	public ToolResult start(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (player == null) {
			return ToolResult.error("No world is loaded.");
		}
		if (!player.isDeadOrDying()) {
			return ToolResult.error("The player is not dead.");
		}
		player.respawn();
		return null;
	}

	@Override
	public ToolResult tick(Minecraft minecraft) {
		if (++age < 20) {
			return null;
		}
		// The death screen normally closes itself; a pause screen may appear when the window
		// is not focused. Do not leave the agent stuck behind it.
		if (minecraft.gui.screen() != null) {
			minecraft.gui.setScreen(null);
		}
		LocalPlayer player = minecraft.player;
		JsonObject json = new JsonObject();
		json.addProperty("ok", true);
		json.addProperty("message", "Respawned.");
		if (player != null) {
			json.addProperty("x", StateSnapshot.round(player.getX()));
			json.addProperty("y", StateSnapshot.round(player.getY()));
			json.addProperty("z", StateSnapshot.round(player.getZ()));
			json.addProperty("health", StateSnapshot.round(player.getHealth()));
		}
		return ToolResult.text(json.toString());
	}

	@Override
	public String name() {
		return "respawn";
	}
}
