package dev.llmcraft.client.actions;

import dev.llmcraft.client.ClientAction;
import dev.llmcraft.client.tools.GuiTools;
import dev.llmcraft.mcp.ToolResult;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;

/** Types text into the focused field of the current GUI (anvil name, sign, search box, etc.). */
public final class GuiTypeAction implements ClientAction {
	private final String text;
	private final boolean replace;
	private int age;

	public GuiTypeAction(String text, boolean replace) {
		this.text = text;
		this.replace = replace;
	}

	@Override
	public ToolResult start(Minecraft minecraft) {
		Screen screen = minecraft.gui.screen();
		if (screen == null) {
			return ToolResult.error("No GUI is open.");
		}
		if (replace && screen.getFocused() instanceof EditBox editBox) {
			editBox.setValue("");
		}
		for (int i = 0; i < text.length(); ) {
			int cp = text.codePointAt(i);
			screen.charTyped(new CharacterEvent(cp));
			i += Character.charCount(cp);
		}
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
		return "gui_type";
	}
}
