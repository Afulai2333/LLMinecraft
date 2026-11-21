package dev.llmcraft.client;

import dev.llmcraft.mcp.ToolResult;
import net.minecraft.client.Minecraft;

/**
 * A client-thread action. Return {@code null} from {@link #tick} to keep running, or a
 * {@link ToolResult} to finish. {@link #cleanup} always runs, exactly once.
 */
public interface ClientAction {
	default ToolResult start(Minecraft minecraft) throws Exception {
		return null;
	}

	ToolResult tick(Minecraft minecraft) throws Exception;

	default void cleanup(Minecraft minecraft) {
	}

	default String name() {
		return getClass().getSimpleName();
	}
}
