package dev.llmcraft.client.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.llmcraft.client.ClientBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Reads the current GUI or menu, including widgets and container slots. */
public final class GuiTools {
	private GuiTools() {
	}

	public static JsonObject state() {
		Minecraft minecraft = ClientBridge.mc();
		if (minecraft == null) {
			throw new IllegalStateException("Minecraft is not running.");
		}
		LocalPlayer player = minecraft.player;
		Screen screen = minecraft.gui.screen();

		JsonObject root = new JsonObject();
		root.addProperty("ok", true);
		if (screen != null) {
			JsonObject screenJson = new JsonObject();
			screenJson.addProperty("class", screen.getClass().getSimpleName());
			screenJson.addProperty("title", screen.getTitle().getString());
			screenJson.addProperty("width", screen.width);
			screenJson.addProperty("height", screen.height);
			root.add("screen", screenJson);

			JsonArray widgets = new JsonArray();
			List<AbstractWidget> found = widgets(screen);
			for (int i = 0; i < found.size(); i++) {
				AbstractWidget widget = found.get(i);
				JsonObject w = new JsonObject();
				w.addProperty("index", i);
				w.addProperty("class", widget.getClass().getSimpleName());
				w.addProperty("message", widget.getMessage().getString());
				w.addProperty("x", widget.getX());
				w.addProperty("y", widget.getY());
				w.addProperty("width", widget.getWidth());
				w.addProperty("height", widget.getHeight());
				w.addProperty("active", widget.active);
				w.addProperty("visible", widget.visible);
				if (widget instanceof EditBox editBox) {
					w.addProperty("value", editBox.getValue());
				}
				widgets.add(w);
			}
			root.add("widgets", widgets);
		} else {
			root.addProperty("screen", "");
			root.add("widgets", new JsonArray());
		}

		if (player != null && minecraft.level != null) {
			AbstractContainerMenu menu = player.containerMenu;
			if (menu != null) {
				JsonObject menuJson = new JsonObject();
				menuJson.addProperty("class", menu.getClass().getSimpleName());
				menuJson.addProperty("container_id", menu.containerId);
				String menuType;
				try {
					menuType = menu.getType().toString();
				} catch (RuntimeException e) {
					// Some client-only menus (notably InventoryMenu) do not expose a MenuType.
					menuType = menu.getClass().getSimpleName();
				}
				menuJson.addProperty("menu_type", menuType);
				menuJson.addProperty("slot_count", menu.slots.size());
				menuJson.add("carried", StateSnapshot.item(menu.getCarried(), -1));
				root.add("menu", menuJson);

				JsonArray slots = new JsonArray();
				for (int i = 0; i < menu.slots.size(); i++) {
					Slot slot = menu.getSlot(i);
					JsonObject s = new JsonObject();
					s.addProperty("index", i);
					s.addProperty("x", slot.x);
					s.addProperty("y", slot.y);
					s.addProperty("container_slot", slot.getContainerSlot());
					s.addProperty("container", slot.container.getClass().getSimpleName());
					s.addProperty("active", slot.isActive());
					ItemStack stack = slot.getItem();
					if (stack.isEmpty()) {
						s.addProperty("item", "minecraft:air");
						s.addProperty("count", 0);
					} else {
						JsonObject item = StateSnapshot.item(stack, i);
						for (String key : item.keySet()) {
							s.add(key, item.get(key));
						}
					}
					slots.add(s);
				}
				root.add("slots", slots);
			} else {
				root.addProperty("menu", "");
			}
		} else {
			root.addProperty("menu", "");
		}
		return root;
	}

	/** Stable depth-first widget order shared by gui_state and gui_click. */
	public static List<AbstractWidget> widgets(Screen screen) {
		List<AbstractWidget> result = new ArrayList<>();
		Set<GuiEventListener> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		if (screen != null) {
			for (GuiEventListener child : screen.children()) {
				collect(child, result, seen);
			}
		}
		return result;
	}

	private static void collect(GuiEventListener listener, List<AbstractWidget> out, Set<GuiEventListener> seen) {
		if (listener == null || !seen.add(listener)) {
			return;
		}
		if (listener instanceof AbstractWidget widget) {
			out.add(widget);
		}
		if (listener instanceof ContainerEventHandler container) {
			for (GuiEventListener child : container.children()) {
				collect(child, out, seen);
			}
		}
	}
}
