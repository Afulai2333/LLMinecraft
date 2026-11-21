package dev.llmcraft.client;

import com.mojang.brigadier.CommandDispatcher;
import dev.llmcraft.LLMinecraft;
import dev.llmcraft.client.tools.ClientTools;
import dev.llmcraft.config.LLMinecraftConfig;
import dev.llmcraft.mcp.McpToolRegistry;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.network.chat.Component;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

public final class LLMinecraftClient implements ClientModInitializer {
	private static LLMinecraftConfig config;

	@Override
	public void onInitializeClient() {
		config = LLMinecraftConfig.get();
		McpToolRegistry registry = ClientTools.create(config);
		McpServerManager.init(config, registry);

		ClientTickEvents.START_CLIENT_TICK.register(ClientBridge::tick);
		ClientTickEvents.END_CLIENT_TICK.register(TickFreezer::tick);
		ClientTickEvents.END_CLIENT_TICK.register(ThreatMonitor::tick);
		ClientTickEvents.END_CLIENT_TICK.register(CreatureMonitor::tick);
		ClientTickEvents.END_CLIENT_TICK.register(GameEventMonitor::tick);
		ClientTickEvents.END_CLIENT_TICK.register(InputLock::tick);
		ClientLifecycleEvents.CLIENT_STARTED.register(minecraft -> {
			if (config.enabled) {
				McpServerManager.start();
			}
		});
		ClientLifecycleEvents.CLIENT_STOPPING.register(minecraft -> {
			McpServerManager.stop();
			TickFreezer.reset();
			InputLock.shutdown(minecraft);
		});

		ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, timestamp) ->
				ChatLog.add("chat", message.getString()));
		ClientReceiveMessageEvents.GAME.register((message, overlay) ->
				ChatLog.add(overlay ? "actionbar" : "system", message.getString()));

		ClientCommandRegistrationCallback.EVENT.register(this::registerCommands);
		LLMinecraft.LOGGER.info("LLMinecraft client ready ({} tools)", registry.size());
	}

	private void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher,
			CommandBuildContext buildContext) {
		dispatcher.register(literal("llmcraft")
				.then(literal("status").executes(context -> {
					context.getSource().sendFeedback(Component.literal(McpServerManager.status()));
					return 1;
				}))
				.then(literal("start").executes(context -> {
					boolean started = McpServerManager.start();
					context.getSource().sendFeedback(Component.literal(
							started ? McpServerManager.status() : "Could not start the MCP server; see the log."));
					return started ? 1 : 0;
				}))
				.then(literal("stop").executes(context -> {
					McpServerManager.stop();
					context.getSource().sendFeedback(Component.literal("MCP server stopped."));
					return 1;
				}))
				.then(literal("restart").executes(context -> {
					McpServerManager.stop();
					config = LLMinecraftConfig.load();
					McpServerManager.init(config, ClientTools.create(config));
					boolean started = config.enabled && McpServerManager.start();
					context.getSource().sendFeedback(Component.literal(McpServerManager.status()));
					return started ? 1 : 0;
				}))
				.then(literal("freeze").executes(context -> {
					config.freezeWhenIdle = !config.freezeWhenIdle;
					if (!config.freezeWhenIdle) {
						TickFreezer.unfreeze(ClientBridge.mc(), "disabled");
					}
					config.save();
					context.getSource().sendFeedback(Component.literal(
							"Idle tick freeze: " + (config.freezeWhenIdle ? "on" : "off")));
					return 1;
				}))
				.then(literal("token").executes(context -> {
					context.getSource().sendFeedback(Component.literal(
							"Bearer token: " + McpServerManager.token()));
					return 1;
				}))
				.then(literal("tools").executes(context -> {
					context.getSource().sendFeedback(Component.literal(McpServerManager.toolNames()));
					return 1;
				})));
	}
}
