package dev.llmcraft.client;

import com.google.gson.JsonObject;
import dev.llmcraft.client.tools.StateSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;

/**
 * Pushes state changes the model must know about without polling: world join, damage,
 * low health, death, respawn, and day/night transitions.
 */
public final class GameEventMonitor {
	private static int timer;
	private static ClientLevel lastLevel;
	private static float lastHealth = -1;
	private static boolean lastDead;
	private static String lastPhase = "";

	private GameEventMonitor() {
	}

	public static void tick(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (player == null || minecraft.level == null) {
			lastLevel = null;
			lastHealth = -1;
			lastDead = false;
			lastPhase = "";
			return;
		}
		if (++timer < 5) {
			return;
		}
		timer = 0;
		ClientLevel level = minecraft.level;
		if (level != lastLevel) {
			lastLevel = level;
			lastHealth = player.getHealth();
			lastDead = player.isDeadOrDying();
			lastPhase = StateSnapshot.phase(Math.floorMod(level.getOverworldClockTime(), 24000L));
			JsonObject data = new JsonObject();
			data.addProperty("dimension", level.dimension().identifier().toString());
			data.addProperty("x", StateSnapshot.round(player.getX()));
			data.addProperty("y", StateSnapshot.round(player.getY()));
			data.addProperty("z", StateSnapshot.round(player.getZ()));
			data.addProperty("health", StateSnapshot.round(player.getHealth()));
			data.addProperty("game_mode", minecraft.gameMode == null
					? "unknown" : minecraft.gameMode.getPlayerMode().getName());
			EventLog.record("world_joined", "info", data);
			return;
		}

		boolean dead = player.isDeadOrDying();
		float health = player.getHealth();
		if (dead && !lastDead) {
			JsonObject data = new JsonObject();
			data.addProperty("message", "Player died");
			data.addProperty("x", StateSnapshot.round(player.getX()));
			data.addProperty("y", StateSnapshot.round(player.getY()));
			data.addProperty("z", StateSnapshot.round(player.getZ()));
			EventLog.record("death", "error", data);
		} else if (!dead && lastDead) {
			JsonObject data = new JsonObject();
			data.addProperty("message", "Player respawned");
			data.addProperty("health", StateSnapshot.round(health));
			EventLog.record("respawn", "info", data);
		}

		if (!dead && lastHealth > 0 && health < lastHealth - 1.5f) {
			JsonObject data = new JsonObject();
			data.addProperty("message", "Player took damage");
			data.addProperty("health", StateSnapshot.round(health));
			data.addProperty("lost", StateSnapshot.round(lastHealth - health));
			EventLog.record("damage", health <= 6 ? "error" : "warning", data);
		}
		if (!dead && health <= 6 && lastHealth > 6) {
			JsonObject data = new JsonObject();
			data.addProperty("message", "Low health");
			data.addProperty("health", StateSnapshot.round(health));
			data.addProperty("food", player.getFoodData().getFoodLevel());
			EventLog.record("health_low", "error", data);
		}

		String phase = StateSnapshot.phase(Math.floorMod(level.getOverworldClockTime(), 24000L));
		if (!phase.equals(lastPhase)) {
			JsonObject data = new JsonObject();
			data.addProperty("phase", phase);
			data.addProperty("time_of_day", Math.floorMod(level.getOverworldClockTime(), 24000L));
			EventLog.record("time_phase", "info", data);
			lastPhase = phase;
		}
		lastHealth = health;
		lastDead = dead;
	}
}
