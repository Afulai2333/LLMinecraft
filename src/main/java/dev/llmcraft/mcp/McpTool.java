package dev.llmcraft.mcp;

import com.google.gson.JsonObject;

import java.util.concurrent.CompletableFuture;

/** One MCP tool exposed by the mod. */
public interface McpTool {
	String name();

	String title();

	String description();

	JsonObject inputSchema();

	default boolean readOnly() {
		return false;
	}

	default boolean destructive() {
		return false;
	}

	CompletableFuture<ToolResult> call(JsonObject arguments);
}
