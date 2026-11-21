package dev.llmcraft.client.actions;

import com.google.gson.JsonObject;
import dev.llmcraft.client.ClientAction;
import dev.llmcraft.client.tools.StateSnapshot;
import dev.llmcraft.client.util.InputController;
import dev.llmcraft.mcp.ToolResult;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/** Presses jump for a few ticks, optionally while moving forward. */
public final class JumpAction implements ClientAction {
	private final boolean forward;
	private int age;

	public JumpAction(boolean forward) {
		this.forward = forward;
	}

	@Override
	public ToolResult start(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (player == null || minecraft.level == null) {
			return ToolResult.error("No world is loaded.");
		}
		if (minecraft.gui.screen() != null) {
			return ToolResult.error("A GUI screen is open; close it before jumping.");
		}
		if (player.isPassenger()) {
			return ToolResult.error("The player is riding a vehicle.");
		}
		InputController.set(minecraft.options.keyJump, true);
		if (forward) {
			InputController.set(minecraft.options.keyUp, true);
		}
		return null;
	}

	@Override
	public ToolResult tick(Minecraft minecraft) {
		if (++age == 3) {
			InputController.set(minecraft.options.keyJump, false);
		}
		if (age < 8) {
			return null;
		}
		LocalPlayer player = minecraft.player;
		JsonObject result = new JsonObject();
		result.addProperty("ok", true);
		result.addProperty("message", "Jumped.");
		result.addProperty("x", StateSnapshot.round(player.getX()));
		result.addProperty("y", StateSnapshot.round(player.getY()));
		result.addProperty("z", StateSnapshot.round(player.getZ()));
		result.addProperty("on_ground", player.onGround());
		return ToolResult.text(result.toString());
	}

	@Override
	public void cleanup(Minecraft minecraft) {
		InputController.releaseMovement(minecraft);
	}

	@Override
	public String name() {
		return "jump";
	}
}
