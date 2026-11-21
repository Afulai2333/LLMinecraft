package dev.llmcraft.client;

import dev.llmcraft.LLMinecraft;
import dev.llmcraft.config.LLMinecraftConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;

/**
 * Freezes the integrated server while the agent is idle (thinking), so mobs, hunger, day/night
 * and weather do not advance between tool calls. Any tool call unfreezes it for the duration of
 * the work; it freezes again after a short quiet period.
 *
 * <p>This only works in singleplayer/LAN-hosted worlds, where the client owns the server. On a
 * remote multiplayer server there is nothing legitimate the client can freeze, so the feature
 * is a no-op there.</p>
 */
public final class TickFreezer {
	private static boolean frozen;
	private static int idleTicks;
	private static boolean announced;

	private TickFreezer() {
	}

	public static void tick(Minecraft minecraft) {
		LLMinecraftConfig config = LLMinecraftConfig.get();
		if (!config.freezeWhenIdle || !McpServerManager.isRunning() || !InputLock.agentCanAct()) {
			unfreeze(minecraft, "not agent-controlled");
			return;
		}
		if (!canFreeze(minecraft)) {
			unfreeze(minecraft, "not a local world");
			return;
		}
		if (ClientBridge.queueSize() > 0) {
			idleTicks = 0;
			return;
		}
		if (frozen) {
			return;
		}
		if (++idleTicks < Math.max(2, config.freezeDelayTicks)) {
			return;
		}
		IntegratedServer server = minecraft.getSingleplayerServer();
		if (server == null) {
			return;
		}
		server.tickRateManager().setFrozen(true);
		frozen = true;
		idleTicks = 0;
		LLMinecraft.LOGGER.debug("MCP idle: froze the integrated server");
		if (!announced && config.showChatFeedback && minecraft.player != null) {
			announced = true;
			minecraft.player.sendOverlayMessage(
					net.minecraft.network.chat.Component.literal("LLMinecraft: game frozen while the agent thinks"));
		}
	}

	/** Call on the client thread before any read or action. */
	public static void unfreezeForWork(Minecraft minecraft) {
		idleTicks = 0;
		unfreeze(minecraft, "work");
	}

	public static void unfreeze(Minecraft minecraft, String reason) {
		if (!frozen) {
			return;
		}
		frozen = false;
		if (minecraft != null) {
			IntegratedServer server = minecraft.getSingleplayerServer();
			if (server != null) {
				server.tickRateManager().setFrozen(false);
			}
		}
		LLMinecraft.LOGGER.debug("MCP resumed ticks ({})", reason);
	}

	public static boolean isFrozen() {
		return frozen;
	}

	public static void reset() {
		frozen = false;
		idleTicks = 0;
		announced = false;
	}

	private static boolean canFreeze(Minecraft minecraft) {
		if (minecraft == null || minecraft.level == null || minecraft.player == null) {
			return false;
		}
		if (minecraft.gui.screen() != null || minecraft.isPaused()) {
			return false;
		}
		IntegratedServer server = minecraft.getSingleplayerServer();
		if (server == null || server.isPublished()) {
			return false;
		}
		return server.tickRateManager() != null;
	}
}
