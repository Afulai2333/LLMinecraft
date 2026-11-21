package dev.llmcraft.client.actions;

import com.google.gson.JsonObject;
import dev.llmcraft.client.ClientAction;
import dev.llmcraft.mcp.ToolResult;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/** Sends a chat message or a slash command. */
public final class ChatAction implements ClientAction {
	private final String message;
	private final boolean command;
	private int age;

	public ChatAction(String message, boolean command) {
		this.message = message;
		this.command = command;
	}

	@Override
	public ToolResult start(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (player == null || player.connection == null) {
			return ToolResult.error("No world is loaded.");
		}
		if (message == null || message.isBlank()) {
			return ToolResult.error("Message is empty.");
		}
		String text = message.strip();
		if (text.length() > 256) {
			return ToolResult.error("Message is too long (max 256 characters).");
		}
		if (command) {
			if (text.startsWith("/")) {
				text = text.substring(1);
			}
			player.connection.sendCommand(text);
		} else {
			if (text.startsWith("/")) {
				return ToolResult.error("Use command=true for slash commands.");
			}
			player.connection.sendChat(text);
		}
		return null;
	}

	@Override
	public ToolResult tick(Minecraft minecraft) {
		if (++age < 2) {
			return null;
		}
		JsonObject json = new JsonObject();
		json.addProperty("ok", true);
		json.addProperty("message", command ? "Command sent." : "Chat message sent.");
		json.addProperty("text", command ? "/" + message.strip().replaceFirst("^/", "") : message.strip());
		return ToolResult.text(json.toString());
	}

	@Override
	public String name() {
		return command ? "command" : "chat";
	}
}
