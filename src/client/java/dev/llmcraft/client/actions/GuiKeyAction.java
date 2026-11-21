package dev.llmcraft.client.actions;

import dev.llmcraft.client.ClientAction;
import dev.llmcraft.client.tools.GuiTools;
import dev.llmcraft.mcp.ToolResult;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;

/** Presses a named key in the current screen (Escape, Enter, Tab, Backspace, arrows). */
public final class GuiKeyAction implements ClientAction {
	private final String key;
	private int age;

	public GuiKeyAction(String key) {
		this.key = key == null ? "" : key.toLowerCase();
	}

	@Override
	public ToolResult start(Minecraft minecraft) {
		Screen screen = minecraft.gui.screen();
		if (screen == null) {
			return ToolResult.error("No GUI is open.");
		}
		int code = switch (key) {
			case "escape", "esc" -> 256;
			case "enter", "return" -> 257;
			case "tab" -> 258;
			case "backspace" -> 259;
			case "space" -> 32;
			case "right" -> 262;
			case "left" -> 263;
			case "down" -> 264;
			case "up" -> 265;
			default -> -1;
		};
		if (code < 0) {
			return ToolResult.error("key must be escape, enter, tab, backspace, space, up, down, left or right.");
		}
		screen.keyPressed(new KeyEvent(code, code, 0));
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
		return "gui_key";
	}
}
