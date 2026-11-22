package dev.llmcraft.client.actions;

import com.google.gson.JsonObject;
import dev.llmcraft.client.ClientAction;
import dev.llmcraft.client.tools.StateSnapshot;
import dev.llmcraft.mcp.ToolResult;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/** Lets game time pass (furnaces, crop growth, mob movement). */
public final class WaitAction implements ClientAction {
	private final int ticks;
	private final boolean force;
	private int age;

	public WaitAction(int ticks, boolean force) {
		this.ticks = ticks;
		this.force = force;
	}

	@Override
	public boolean forced() {
		return force;
	}

	@Override
	public ToolResult tick(Minecraft minecraft) {
		if (++age < ticks) {
			return null;
		}
		LocalPlayer player = minecraft.player;
		JsonObject result = new JsonObject();
		result.addProperty("ok", true);
		result.addProperty("message", "Waited " + String.format("%.1f", ticks / 20.0) + " seconds.");
		result.addProperty("forced", force);
		if (player != null) {
			result.addProperty("health", StateSnapshot.round(player.getHealth()));
			result.addProperty("food", player.getFoodData().getFoodLevel());
			result.addProperty("x", StateSnapshot.round(player.getX()));
			result.addProperty("y", StateSnapshot.round(player.getY()));
			result.addProperty("z", StateSnapshot.round(player.getZ()));
		}
		return ToolResult.text(result.toString());
	}

	@Override
	public String name() {
		return "wait";
	}
}
