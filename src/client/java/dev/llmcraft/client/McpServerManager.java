package dev.llmcraft.client;

import dev.llmcraft.LLMinecraft;
import dev.llmcraft.config.LLMinecraftConfig;
import dev.llmcraft.mcp.McpServer;
import dev.llmcraft.mcp.McpToolRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Owns the lifecycle of the single MCP listener. */
public final class McpServerManager {
	private static LLMinecraftConfig config;
	private static McpToolRegistry registry;
	private static McpServer server;

	private McpServerManager() {
	}

	public static void init(LLMinecraftConfig config, McpToolRegistry registry) {
		McpServerManager.config = config;
		McpServerManager.registry = registry;
	}

	public static synchronized boolean start() {
		if (server != null && server.isRunning()) {
			return true;
		}
		if (config == null || registry == null) {
			LLMinecraft.LOGGER.warn("MCP server cannot start before the client is initialised");
			return false;
		}
		String version = FabricLoader.getInstance().getModContainer(LLMinecraft.MOD_ID)
				.map(container -> container.getMetadata().getVersion().getFriendlyString())
				.orElse("dev");
		server = new McpServer(config, registry, version);
		try {
			server.start();
			notifyPlayer("MCP listening on " + server.endpoint() + " (" + registry.size() + " tools)");
			return true;
		} catch (Exception e) {
			LLMinecraft.LOGGER.error("Could not start MCP server on {}:{}", config.host, config.port, e);
			notifyPlayer("MCP failed to start: " + e.getMessage());
			server = null;
			return false;
		}
	}

	public static synchronized void stop() {
		ClientBridge.cancelAll("MCP server stopped.");
		TickFreezer.unfreeze(ClientBridge.mc(), "MCP stopped");
		InputLock.shutdown(ClientBridge.mc());
		if (server != null) {
			server.stop();
			server = null;
			notifyPlayer("MCP server stopped.");
		}
	}

	public static synchronized void restart(LLMinecraftConfig newConfig, McpToolRegistry newRegistry) {
		stop();
		config = newConfig;
		registry = newRegistry;
		start();
	}

	public static synchronized McpServer server() {
		return server;
	}

	public static synchronized boolean isRunning() {
		return server != null && server.isRunning();
	}

	public static synchronized String status() {
		if (server == null || !server.isRunning()) {
			return "MCP: stopped (tools=" + (registry == null ? 0 : registry.size())
					+ ", queued=" + ClientBridge.queueSize()
					+ (TickFreezer.isFrozen() ? ", frozen" : "") + ")";
		}
		return "MCP: " + server.endpoint() + " (tools=" + registry.size()
				+ ", queued=" + ClientBridge.queueSize()
				+ (TickFreezer.isFrozen() ? ", frozen" : "") + ")";
	}

	public static synchronized String token() {
		return config == null ? "" : config.authToken;
	}

	public static void broadcastEvent(String type, String level, com.google.gson.JsonObject event) {
		McpServer current = server;
		if (current != null && current.isRunning()) {
			current.broadcastEvent(type, level, event);
		}
	}

	public static synchronized String toolNames() {
		if (registry == null) {
			return "";
		}
		return String.join(", ", registry.all().stream().map(tool -> tool.name()).toList());
	}

	private static void notifyPlayer(String message) {
		Minecraft minecraft = ClientBridge.mc();
		if (minecraft != null && minecraft.player != null && config != null && config.showChatFeedback) {
			minecraft.player.sendSystemMessage(Component.literal("[LLMinecraft] " + message));
		}
	}
}
