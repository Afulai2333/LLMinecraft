package dev.llmcraft.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Base64;

/** The MCP {@code tools/call} result payload. */
public final class ToolResult {
	private final JsonArray content = new JsonArray();
	private boolean error;

	private ToolResult() {
	}

	public static ToolResult text(String text) {
		ToolResult result = new ToolResult();
		result.addText(text);
		return result;
	}

	public static ToolResult error(String text) {
		ToolResult result = text(text);
		result.error = true;
		return result;
	}

	public ToolResult addText(String text) {
		JsonObject item = new JsonObject();
		item.addProperty("type", "text");
		item.addProperty("text", text);
		content.add(item);
		return this;
	}

	public ToolResult addImage(byte[] png) {
		JsonObject item = new JsonObject();
		item.addProperty("type", "image");
		item.addProperty("data", Base64.getEncoder().encodeToString(png));
		item.addProperty("mimeType", "image/png");
		content.add(item);
		return this;
	}

	public ToolResult asError() {
		this.error = true;
		return this;
	}

	public boolean isError() {
		return error;
	}

	public JsonObject toJson() {
		JsonObject json = new JsonObject();
		json.add("content", content);
		if (error) {
			json.addProperty("isError", true);
		}
		return json;
	}
}
