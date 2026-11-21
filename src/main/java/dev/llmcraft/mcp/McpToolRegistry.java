package dev.llmcraft.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Ordered registry of tools, rendered for {@code tools/list}. */
public final class McpToolRegistry {
	private final Map<String, McpTool> tools = new LinkedHashMap<>();

	public McpToolRegistry register(McpTool tool) {
		McpTool previous = tools.putIfAbsent(tool.name(), tool);
		if (previous != null) {
			throw new IllegalArgumentException("Duplicate MCP tool: " + tool.name());
		}
		return this;
	}

	public McpTool get(String name) {
		return tools.get(name);
	}

	public Collection<McpTool> all() {
		return tools.values();
	}

	public int size() {
		return tools.size();
	}

	public CompletableFuture<ToolResult> call(String name, JsonObject arguments) {
		McpTool tool = tools.get(name);
		if (tool == null) {
			return CompletableFuture.failedFuture(new UnknownToolException(name));
		}
		try {
			return tool.call(arguments == null ? new JsonObject() : arguments);
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	public JsonArray toJson() {
		JsonArray array = new JsonArray();
		for (McpTool tool : tools.values()) {
			JsonObject json = new JsonObject();
			json.addProperty("name", tool.name());
			json.addProperty("title", tool.title());
			json.addProperty("description", tool.description());
			json.add("inputSchema", tool.inputSchema());

			JsonObject annotations = new JsonObject();
			annotations.addProperty("title", tool.title());
			annotations.addProperty("readOnlyHint", tool.readOnly());
			annotations.addProperty("destructiveHint", tool.destructive());
			annotations.addProperty("idempotentHint", tool.readOnly());
			annotations.addProperty("openWorldHint", true);
			json.add("annotations", annotations);
			array.add(json);
		}
		return array;
	}

	public static final class UnknownToolException extends RuntimeException {
		public UnknownToolException(String name) {
			super("Unknown tool: " + name);
		}
	}
}
