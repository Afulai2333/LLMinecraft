package dev.llmcraft.client.actions;

import dev.llmcraft.client.ClientAction;
import dev.llmcraft.client.tools.GuiTools;
import dev.llmcraft.mcp.ToolResult;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** Scrolls a list or screen, useful for world lists, resource packs and options. */
public final class GuiScrollAction implements ClientAction {
	private final double x;
	private final double y;
	private final double amount;
	private int age;

	public GuiScrollAction(double x, double y, double amount) {
		this.x = x;
		this.y = y;
		this.amount = amount;
	}

	@Override
	public ToolResult start(Minecraft minecraft) {
		Screen screen = minecraft.gui.screen();
		if (screen == null) {
			return ToolResult.error("No GUI is open.");
		}
		double px = x < 0 ? screen.width / 2.0 : x;
		double py = y < 0 ? screen.height / 2.0 : y;
		screen.mouseScrolled(px, py, 0.0, amount);
		return null;
	}

	@Override
	public ToolResult tick(Minecraft minecraft) {
		if (++age < 3) {
			return null;
		}
		return ToolResult.text(GuiTools.state().toString());
	}

	@Override
	public String name() {
		return "gui_scroll";
	}
}
