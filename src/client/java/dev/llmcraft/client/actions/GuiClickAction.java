package dev.llmcraft.client.actions;

import dev.llmcraft.client.ClientAction;
import dev.llmcraft.client.ClientBridge;
import dev.llmcraft.client.tools.GuiTools;
import dev.llmcraft.mcp.ToolResult;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;

import java.util.List;

/**
 * Clicks a container slot by menu index, or a screen coordinate for a generic GUI.
 *
 * <p>Slot actions map to vanilla container clicks, so chests, furnaces, crafting tables,
 * villager trades and the inventory all behave exactly as if the player clicked them.</p>
 */
public final class GuiClickAction implements ClientAction {
	private final Integer slot;
	private final Integer widget;
	private final Double x;
	private final Double y;
	private final int button;
	private final String action;
	private final Integer hotbar;
	private int age;
	private int wait;

	public GuiClickAction(Integer slot, Integer widget, Double x, Double y,
			int button, String action, Integer hotbar) {
		this.slot = slot;
		this.widget = widget;
		this.x = x;
		this.y = y;
		this.button = button <= 0 ? 1 : button;
		this.action = action == null ? "left" : action.toLowerCase();
		this.hotbar = hotbar;
	}

	@Override
	public ToolResult start(Minecraft minecraft) {
		Screen screen = minecraft.gui.screen();
		if (widget != null) {
			if (screen == null) {
				return ToolResult.error("No GUI is open.");
			}
			List<AbstractWidget> widgets = GuiTools.widgets(screen);
			if (widget < 0 || widget >= widgets.size()) {
				return ToolResult.error("Widget " + widget + " is out of range (0-"
						+ (widgets.size() - 1) + ").");
			}
			AbstractWidget target = widgets.get(widget);
			MouseButtonEvent event = new MouseButtonEvent(
					target.getX() + target.getWidth() / 2.0,
					target.getY() + target.getHeight() / 2.0,
					new MouseButtonInfo(button, 0));
			if (target instanceof AbstractButton button) {
				// Trigger the button's action directly; mouse coordinate validation is fragile
				// across menus and GUI scales, and this is the same callback a real click uses.
				button.onPress(event);
			} else {
				target.mouseClicked(event, false);
				target.mouseReleased(event);
			}
			wait = 3;
			return null;
		}
		if (x != null && y != null) {
			if (screen == null) {
				return ToolResult.error("No GUI is open. Use gui_state to see the current screen.");
			}
			return click(minecraft, screen, x, y);
		}
		LocalPlayer player = minecraft.player;
		if (player == null || minecraft.level == null) {
			return ToolResult.error("No world is loaded; use widget or x/y to click the current menu.");
		}
		if (slot == null) {
			return ToolResult.error("Provide widget, x/y, or slot (container slot index).");
		}
		AbstractContainerMenu menu = player.containerMenu;
		if (menu == null) {
			return ToolResult.error("No container menu is open.");
		}
		if (slot != -999 && !menu.isValidSlotIndex(slot)) {
			return ToolResult.error("Slot " + slot + " is out of range (0-"
					+ (menu.slots.size() - 1) + ").");
		}

		ContainerInput input;
		int clickButton;
		switch (action) {
			case "left", "pickup" -> {
				input = ContainerInput.PICKUP;
				clickButton = 0;
			}
			case "right" -> {
				input = ContainerInput.PICKUP;
				clickButton = 1;
			}
			case "shift", "quick_move" -> {
				input = ContainerInput.QUICK_MOVE;
				clickButton = 0;
			}
			case "throw" -> {
				input = ContainerInput.THROW;
				clickButton = 0;
			}
			case "throw_all" -> {
				input = ContainerInput.THROW;
				clickButton = 1;
			}
			case "swap" -> {
				input = ContainerInput.SWAP;
				clickButton = hotbar == null ? player.getInventory().getSelectedSlot() : hotbar;
				if (clickButton < 0 || clickButton > 8) {
					return ToolResult.error("hotbar must be 0-8 for swap.");
				}
			}
			case "pickup_all" -> {
				input = ContainerInput.PICKUP_ALL;
				clickButton = 0;
			}
			default -> {
				return ToolResult.error("action must be left, right, shift, throw, throw_all, swap or pickup_all.");
			}
		}
		minecraft.gameMode.handleContainerInput(menu.containerId, slot, clickButton, input, player);
		wait = 3;
		return null;
	}

	@Override
	public ToolResult tick(Minecraft minecraft) {
		if (++age < wait) {
			return null;
		}
		return ToolResult.text(GuiTools.state().toString());
	}

	private ToolResult click(Minecraft minecraft, Screen screen, double px, double py) {
		MouseButtonEvent event = new MouseButtonEvent(px, py, new MouseButtonInfo(button, 0));
		screen.mouseClicked(event, false);
		screen.mouseReleased(event);
		wait = 3;
		return null;
	}

	@Override
	public String name() {
		return "gui_click";
	}
}
