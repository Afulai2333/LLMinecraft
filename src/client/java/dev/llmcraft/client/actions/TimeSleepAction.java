package dev.llmcraft.client.actions;

import com.google.gson.JsonObject;
import dev.llmcraft.client.ClientAction;
import dev.llmcraft.client.tools.StateSnapshot;
import dev.llmcraft.mcp.ToolResult;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * Runs the world for a requested duration without the idle freezer interfering. This is the
 * explicit "time sleep" primitive: use it to let furnaces smelt, crops grow, mobs move or the
 * sun set, then continue.
 */
public final class TimeSleepAction implements ClientAction {
	private final int ticks;
	private int age;
	private long startWorldTime;

	public TimeSleepAction(int ticks) {
		this.ticks = Math.max(1, ticks);
	}

	@Override
	public ToolResult start(Minecraft minecraft) {
		if (minecraft.player == null || minecraft.level == null) {
			return ToolResult.error("No world is loaded.");
		}
		startWorldTime = minecraft.level.getOverworldClockTime();
		return null;
	}

	@Override
	public ToolResult tick(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (player == null || minecraft.level == null) {
			return ToolResult.error("The world was closed while waiting.");
		}
		if (player.isDeadOrDying()) {
			return ToolResult.error("The player died while waiting.");
		}
		if (++age < ticks) {
			return null;
		}
		long worldTicks = minecraft.level.getOverworldClockTime() - startWorldTime;
		long dayTime = Math.floorMod(minecraft.level.getOverworldClockTime(), 24000L);
		JsonObject json = new JsonObject();
		json.addProperty("ok", true);
		json.addProperty("message", "Slept for " + String.format("%.1f", ticks / 20.0)
				+ "s with the world running.");
		json.addProperty("seconds", StateSnapshot.round(ticks / 20.0));
		json.addProperty("requested_ticks", ticks);
		json.addProperty("world_ticks", worldTicks);
		json.addProperty("time_of_day", dayTime);
		json.addProperty("phase", StateSnapshot.phase(dayTime));
		json.addProperty("health", StateSnapshot.round(player.getHealth()));
		json.addProperty("food", player.getFoodData().getFoodLevel());
		json.addProperty("x", StateSnapshot.round(player.getX()));
		json.addProperty("y", StateSnapshot.round(player.getY()));
		json.addProperty("z", StateSnapshot.round(player.getZ()));
		return ToolResult.text(json.toString());
	}

	@Override
	public String name() {
		return "sleep";
	}
}
