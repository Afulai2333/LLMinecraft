package dev.llmcraft.client.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.llmcraft.client.ChatLog;
import dev.llmcraft.client.ClientBridge;
import dev.llmcraft.client.EventLog;
import dev.llmcraft.client.InputLock;
import dev.llmcraft.client.actions.AttackAction;
import dev.llmcraft.client.actions.ChatAction;
import dev.llmcraft.client.actions.CloseScreenAction;
import dev.llmcraft.client.actions.CraftAction;
import dev.llmcraft.client.actions.DropAction;
import dev.llmcraft.client.actions.EquipAction;
import dev.llmcraft.client.actions.GoToAction;
import dev.llmcraft.client.actions.GuiClickAction;
import dev.llmcraft.client.actions.GuiKeyAction;
import dev.llmcraft.client.actions.GuiScrollAction;
import dev.llmcraft.client.actions.GuiTypeAction;
import dev.llmcraft.client.actions.JumpAction;
import dev.llmcraft.client.actions.JumpOnAction;
import dev.llmcraft.client.actions.LookAction;
import dev.llmcraft.client.actions.MineAction;
import dev.llmcraft.client.actions.MoveAction;
import dev.llmcraft.client.actions.PlaceAction;
import dev.llmcraft.client.actions.RespawnAction;
import dev.llmcraft.client.actions.ScreenshotAction;
import dev.llmcraft.client.actions.SelectItemAction;
import dev.llmcraft.client.actions.SleepAction;
import dev.llmcraft.client.actions.TimeSleepAction;
import dev.llmcraft.client.actions.UseAction;
import dev.llmcraft.client.actions.WaitAction;
import dev.llmcraft.config.LLMinecraftConfig;
import dev.llmcraft.mcp.McpTool;
import dev.llmcraft.mcp.McpToolRegistry;
import dev.llmcraft.mcp.ToolResult;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** Definitions for every MCP tool exposed by the client mod. */
public final class ClientTools {
	private ClientTools() {
	}

	public static McpToolRegistry create(LLMinecraftConfig config) {
		McpToolRegistry registry = new McpToolRegistry();
		int maxActionTicks = config.maxActionSeconds * 20;

		registry.register(tool("get_state", "Get game state",
				"""
				Return the player's full situation: position, rotation, health, hunger, dimension, time,
				weather, biome, held item, inventory, the block/entity in the crosshair, nearby entities,
				status effects and whether a GUI is open. Call this first, and after important actions.
				""",
				"""
				{"type":"object","properties":{},"additionalProperties":false}
				""", true, false, args -> ClientBridge.supply(StateSnapshot::capture)
				.thenApply(json -> ToolResult.text(json.toString()))));

		registry.register(tool("request_user_takeover", "Ask the human to take over",
				"""
				Ask the human to take control. A modal dialog with Agree and Refuse buttons
				appears; only Agree unlocks physical keyboard/mouse input. The agent pauses
				while the request is pending. If the human agrees, they play until they press
				Ctrl+C, which hands control back to the agent. Poll control_status for the result.
				""",
				"""
				{"type":"object","properties":{
				  "reason":{"type":"string","maxLength":200,
				    "description":"Short explanation shown to the human"}
				},"additionalProperties":false}
				""", false, false, args -> ClientBridge.supply(() -> {
			Minecraft minecraft = ClientBridge.mc();
			InputLock.requestByAgent(minecraft, Args.stringOr(args, "reason", ""));
			JsonObject json = InputLock.status();
			json.addProperty("message", "Takeover dialog opened. The human must choose Agree or Refuse; poll control_status.");
			return json;
		}).thenApply(json -> ToolResult.text(json.toString()))));

		registry.register(tool("get_events", "Read pushed game events",
				"""
				The mod pushes world_joined, damage, health_low, death, respawn,
				time_phase, threat, chat and control events automatically over the MCP SSE
				stream. This tool returns the same events from a bounded history, for clients
				that cannot keep an SSE connection open. Pass the last seen id as since_id.
				""",
				"""
				{"type":"object","properties":{
				  "since_id":{"type":"integer","minimum":0,"default":0},
				  "limit":{"type":"integer","minimum":1,"maximum":100,"default":30}
				},"additionalProperties":false}
				""", true, false, args -> {
			long since = Args.optInt(args, "since_id") == null ? 0 : Args.optInt(args, "since_id");
			int limit = Math.clamp(Args.intOr(args, "limit", 30), 1, 100);
			JsonObject result = new JsonObject();
			result.addProperty("ok", true);
			result.addProperty("latest_id", EventLog.latestId());
			result.add("events", EventLog.since(since, limit));
			return CompletableFuture.completedFuture(ToolResult.text(result.toString()));
		}));

		registry.register(tool("control_status", "Read control ownership",
				"""
				Return who controls the player: mode (agent/requested/user), whether the
				Agree/Refuse dialog is open, whether input is locked, whether the agent may act,
				the key that ends a human takeover (Ctrl+C) and the pending reason.
				""",
				"""
				{"type":"object","properties":{},"additionalProperties":false}
				""", true, false, args -> ClientBridge.supply(InputLock::status)
				.thenApply(json -> ToolResult.text(json.toString()))));

		registry.register(tool("resume_control", "Cancel a takeover request",
				"""
				Cancel a pending takeover request and close the Agree/Refuse dialog. Does
				nothing if the human has already taken control; only the human can end that
				takeover, with Ctrl+C.
				""",
				"""
				{"type":"object","properties":{},"additionalProperties":false}
				""", false, false, args -> ClientBridge.supply(() -> {
			Minecraft minecraft = ClientBridge.mc();
			if (InputLock.userHasControl()) {
				throw new IllegalStateException("The user has control; wait for them to press Ctrl+C.");
			}
			InputLock.resumeByAgent(minecraft);
			return InputLock.status();
		}).thenApply(json -> ToolResult.text(json.toString()))));

		registry.register(tool("look", "Look at direction or target",
				"""
				Rotate the camera. Give either absolute yaw/pitch in degrees, or x/y/z to look at a point,
				or entity_id to look at an entity. Minecraft yaw: 0 = south (+Z), 90 = west (-X),
				-90 = east (+X), +/-180 = north. Pitch: -90 = straight up, 90 = straight down.
				""",
				"""
				{"type":"object","properties":{
				  "yaw":{"type":"number","description":"Absolute yaw in degrees"},
				  "pitch":{"type":"number","description":"Absolute pitch in degrees"},
				  "x":{"type":"number"},"y":{"type":"number"},"z":{"type":"number","description":"Point to look at"},
				  "entity_id":{"type":"integer","description":"Entity id from get_state"}
				},"additionalProperties":false}
				""", false, false, args -> {
			Integer entityId = Args.optInt(args, "entity_id");
			Vec3 point = null;
			if (args.has("x") && args.has("y") && args.has("z")) {
				point = new Vec3(Args.requireDouble(args, "x"), Args.requireDouble(args, "y"),
						Args.requireDouble(args, "z"));
			}
			Float yaw = Args.optDouble(args, "yaw") == null ? null : Args.optDouble(args, "yaw").floatValue();
			Float pitch = Args.optDouble(args, "pitch") == null ? null : Args.optDouble(args, "pitch").floatValue();
			if (entityId == null && point == null && yaw == null && pitch == null) {
				throw new IllegalArgumentException("Provide yaw/pitch, x/y/z, or entity_id.");
			}
			return ClientBridge.submit(new LookAction(yaw, pitch, point, entityId), 40);
		}));

		registry.register(tool("move", "Move in a direction",
				"""
				Hold a movement key for a number of seconds. This is real movement, not a teleport.
				Use short bursts (0.5-2s) and check get_state. Use jump=true to hop while moving.
				For longer journeys use goto; for precise placement use goto then place.
				""",
				"""
				{"type":"object","properties":{
				  "direction":{"type":"string","enum":["forward","back","left","right"]},
				  "seconds":{"type":"number","minimum":0.05,"maximum":15},
				  "sprint":{"type":"boolean","default":false},
				  "sneak":{"type":"boolean","default":false},
				  "jump":{"type":"boolean","default":false,"description":"Hold jump while moving"}
				},"required":["direction"],"additionalProperties":false}
				""", false, false, args -> {
			String direction = Args.requireString(args, "direction").toLowerCase();
			double seconds = Args.optDouble(args, "seconds") == null ? 1.0 : Args.optDouble(args, "seconds");
			int ticks = (int) Math.round(seconds * 20.0);
			ticks = Math.clamp(ticks, 1, maxActionTicks);
			return ClientBridge.submit(new MoveAction(direction, ticks,
					Args.optBoolean(args, "sprint", false),
					Args.optBoolean(args, "sneak", false),
					Args.optBoolean(args, "jump", false)), ticks + 30);
		}));

		registry.register(tool("jump", "Jump",
				"Jump in place. Set forward=true to jump while moving forward.",
				"""
				{"type":"object","properties":{"forward":{"type":"boolean","default":false}},"additionalProperties":false}
				""", false, false, args ->
				ClientBridge.submit(new JumpAction(Args.optBoolean(args, "forward", false)), 40)));

		registry.register(tool("jump_on", "Jump onto a block",
				"""
				Jump onto the top of a specific block, including sprint-jumps across gaps.
				The block top may be up to one block above the player (not a fence/wall) and
				up to ~4.5 blocks horizontally. Long jumps automatically sprint; set sprint=true
				to force sprinting on a medium jump. Use goto for ordinary long routes.
				""",
				"""
				{"type":"object","properties":{
				  "x":{"type":"integer"},"y":{"type":"integer"},"z":{"type":"integer"},
				  "sprint":{"type":"boolean","default":false,
				    "description":"Force sprint for a parkour jump"}
				},"required":["x","y","z"],"additionalProperties":false}
				""", false, false, args -> ClientBridge.submit(
				new JumpOnAction(new BlockPos(Args.requireInt(args, "x"),
						Args.requireInt(args, "y"), Args.requireInt(args, "z")),
						Args.optBoolean(args, "sprint", false)), 90)));

		registry.register(tool("goto", "Walk to coordinates",
				"""
				Pathfind and walk to a block position (x,z required, y optional). The pathfinder only walks,
				jumps one block up and drops up to three blocks; it will not mine, swim long distances or
				open doors. It returns an error with the reason if the route is blocked. Use this before
				mine/place/use when the target is farther than about 4 blocks.
				""",
				"""
				{"type":"object","properties":{
				  "x":{"type":"integer"},"z":{"type":"integer"},
				  "y":{"type":"integer","description":"Optional; omit to find the surface"}
				},"required":["x","z"],"additionalProperties":false}
				""", false, false, args -> {
			int x = Args.requireInt(args, "x");
			int z = Args.requireInt(args, "z");
			Integer y = Args.optInt(args, "y");
			return ClientBridge.submit(new GoToAction(x, z, y, 9000), maxActionTicks + 100);
		}));

		registry.register(tool("mine", "Mine a block",
				"""
				Break one block at the given coordinates. The player must be within reach (about 4.5 blocks)
				and should be looking at it; the mod looks automatically. By default auto_tool=true
				selects the fastest appropriate tool from the hotbar. To use a specific tool, set
				tool (id/name) or tool_slot (0-8); an explicit tool overrides auto_tool. The selected
				item is returned as "tool". The action runs in real time and returns when the block is gone.
				Mine logs first, then craft tools/planks.
				""",
				"""
				{"type":"object","properties":{
				  "x":{"type":"integer"},"y":{"type":"integer"},"z":{"type":"integer"},
				  "auto_tool":{"type":"boolean","default":true},
				  "tool":{"type":"string","description":"Hotbar item id/name to use instead of the best tool"},
				  "tool_slot":{"type":"integer","minimum":0,"maximum":8,
				    "description":"Exact hotbar slot to use; overrides tool and auto_tool"}
				},"required":["x","y","z"],"additionalProperties":false}
				""", false, true, args -> ClientBridge.submit(
				new MineAction(new BlockPos(Args.requireInt(args, "x"),
						Args.requireInt(args, "y"), Args.requireInt(args, "z")),
						Args.optBoolean(args, "auto_tool", true),
						Args.optString(args, "tool"), Args.optInt(args, "tool_slot")),
				maxActionTicks + 40)));

		registry.register(tool("place", "Place a block",
				"""
				Place a block into the empty position x/y/z. It is placed against a neighbouring solid block;
				specify face as the direction from the target to the support (the block being clicked).
				Example: target is air above grass, support is below, face="down".
				The item is taken from the selected hotbar slot, or from a hotbar item matching 'block'.
				The player must be within reach and not standing in the target cell, unless
				jump=true: then the player jumps first and the block is placed under the feet.
				""",
				"""
				{"type":"object","properties":{
				  "x":{"type":"integer"},"y":{"type":"integer"},"z":{"type":"integer"},
				  "block":{"type":"string","description":"Optional hotbar item id or name"},
				  "face":{"type":"string","enum":["up","down","north","south","east","west"],
				    "description":"Direction from target to support; omitted = auto"},
				  "jump":{"type":"boolean","default":false,
				    "description":"Jump first, then place under the feet (pillar up)"}
				},"required":["x","y","z"],"additionalProperties":false}
				""", false, true, args -> ClientBridge.submit(
				new PlaceAction(new BlockPos(Args.requireInt(args, "x"),
						Args.requireInt(args, "y"), Args.requireInt(args, "z")),
						Args.optString(args, "block"), Args.optString(args, "face"),
						Args.optBoolean(args, "jump", false)), 140)));

		registry.register(tool("use", "Use / interact",
				"""
				Right-click: use the held item, interact with a block (coordinates), or interact with an
				entity (entity_id). With no target it right-clicks the air with the held item. This opens
				chests, crafting tables, furnaces and doors; use close_screen afterwards. To eat, hold food
				and call use (the mod holds the button briefly).
				""",
				"""
				{"type":"object","properties":{
				  "x":{"type":"integer"},"y":{"type":"integer"},"z":{"type":"integer"},
				  "entity_id":{"type":"integer"},
				  "hand":{"type":"string","enum":["main","offhand"],"default":"main"},
				  "seconds":{"type":"number","minimum":0.05,"maximum":10,"default":2.0,
				    "description":"How long to hold right-click when using the held item (food needs ~1.6s)"}
				},"additionalProperties":false}
				""", false, false, args -> {
			Integer entity = Args.optInt(args, "entity_id");
			BlockPos pos = args.has("x") && args.has("y") && args.has("z")
					? new BlockPos(Args.requireInt(args, "x"), Args.requireInt(args, "y"), Args.requireInt(args, "z"))
					: null;
			double seconds = Args.optDouble(args, "seconds") == null ? 2.0 : Args.optDouble(args, "seconds");
			int holdTicks = Math.clamp((int) Math.round(seconds * 20.0), 1, 200);
			return ClientBridge.submit(new UseAction(pos, entity, Args.stringOr(args, "hand", "main"), holdTicks),
					holdTicks + 60);
		}));

		registry.register(tool("attack", "Attack an entity",
				"""
				Attack one entity, or the nearest matching entity within 24 blocks. Provide entity_id from
				get_state, or a type name such as "zombie"/"minecraft:cow". Repeats respect the vanilla
				attack cooldown. The target must be in reach; use goto first for distant mobs.
				""",
				"""
				{"type":"object","properties":{
				  "entity_id":{"type":"integer"},
				  "entity":{"type":"string","description":"Type/name filter when entity_id is omitted"},
				  "times":{"type":"integer","minimum":1,"maximum":50,"default":1}
				},"additionalProperties":false}
				""", false, true, args -> {
			Integer id = Args.optInt(args, "entity_id");
			String query = Args.optString(args, "entity");
			if (id == null && (query == null || query.isBlank())) {
				throw new IllegalArgumentException("Provide entity_id or entity.");
			}
			int times = Args.intOr(args, "times", 1);
			return ClientBridge.submit(new AttackAction(id, query, times), 40 + times * 20);
		}));

		registry.register(tool("scan_blocks", "Scan nearby blocks",
				"""
				Find blocks around the player. query may be an item/block id or a name fragment, or a
				tag such as "#minecraft:logs". With no query it returns the most common block types and
				one example position for each. Use this to find trees, ores, water, chests or a safe spot.
				""",
				"""
				{"type":"object","properties":{
				  "radius":{"type":"integer","minimum":1,"maximum":64,"default":16},
				  "query":{"type":"string","description":"Block id, name fragment, or #tag"},
				  "limit":{"type":"integer","minimum":1,"maximum":128,"default":32}
				},"additionalProperties":false}
				""", true, false, args -> {
			int radius = Args.intOr(args, "radius", 16);
			radius = Math.clamp(radius, 1, config.maxScanRadius);
			String query = Args.optString(args, "query");
			int limit = Args.intOr(args, "limit", 32);
			final int finalRadius = radius;
			return ClientBridge.supply(() -> ScanTools.scanBlocks(finalRadius, query, limit))
					.thenApply(json -> ToolResult.text(json.toString()));
		}));

		registry.register(tool("scan_entities", "Scan nearby entities",
				"""
				List living entities within radius, nearest first. Optionally filter by type/name.
				Useful for finding animals to hunt, mobs to avoid, or villagers.
				""",
				"""
				{"type":"object","properties":{
				  "radius":{"type":"number","minimum":1,"maximum":64,"default":24},
				  "entity":{"type":"string","description":"Optional type/name filter"},
				  "limit":{"type":"integer","minimum":1,"maximum":64,"default":24}
				},"additionalProperties":false}
				""", true, false, args -> ClientBridge.supply(() -> {
			Minecraft minecraft = ClientBridge.mc();
			LocalPlayer player = minecraft.player;
			if (player == null || minecraft.level == null) {
				throw new IllegalStateException("No world is loaded.");
			}
			double radius = Args.optDouble(args, "radius") == null ? 24.0
					: Math.clamp(Args.optDouble(args, "radius"), 1.0, 64.0);
			String query = Args.optString(args, "entity");
			int limit = Math.clamp(Args.intOr(args, "limit", 24), 1, 64);
			JsonArray entities = StateSnapshot.nearbyEntities(minecraft.level, player, radius, limit * 2);
			JsonArray filtered = new JsonArray();
			for (var element : entities) {
				JsonObject entity = element.getAsJsonObject();
				if (query != null && !query.isBlank()) {
					String needle = query.toLowerCase();
					String type = entity.get("type").getAsString();
					String name = entity.get("name").getAsString().toLowerCase();
					if (!type.contains(needle) && !name.contains(needle)) {
						continue;
					}
				}
				if (filtered.size() >= limit) {
					break;
				}
				filtered.add(entity);
			}
			JsonObject result = new JsonObject();
			result.addProperty("ok", true);
			result.addProperty("count", filtered.size());
			result.add("entities", filtered);
			return result;
		}).thenApply(json -> ToolResult.text(json.toString()))));

		registry.register(tool("get_block", "Inspect one block",
				"Return the block id, properties, hardness, light, distance, replaceability and block-entity type at x/y/z.",
				"""
				{"type":"object","properties":{
				  "x":{"type":"integer"},"y":{"type":"integer"},"z":{"type":"integer"}
				},"required":["x","y","z"],"additionalProperties":false}
				""", true, false, args -> ClientBridge.supply(() ->
				ScanTools.blockAt(Args.requireInt(args, "x"), Args.requireInt(args, "y"),
						Args.requireInt(args, "z"))).thenApply(json -> ToolResult.text(json.toString()))));

		registry.register(tool("inventory", "Read inventory",
				"Return every inventory slot, the selected hotbar slot, hotbar contents and armor.",
				"""
				{"type":"object","properties":{},"additionalProperties":false}
				""", true, false, args -> ClientBridge.supply(() -> {
			Minecraft minecraft = ClientBridge.mc();
			LocalPlayer player = minecraft.player;
			if (player == null) {
				throw new IllegalStateException("No world is loaded.");
			}
			JsonObject result = StateSnapshot.inventory(player.getInventory());
			result.addProperty("ok", true);
			return result;
		}).thenApply(json -> ToolResult.text(json.toString()))));

		registry.register(tool("gui_state", "Read the open GUI",
				"""
				Read the current screen and container: screen title/size, clickable widgets with
				bounds, every menu slot (index, x/y, item, count), the carried stack and the menu id.
				Use slot indexes with gui_click. If no GUI is open, open a chest/table/furnace with use.
				""",
				"""
				{"type":"object","properties":{},"additionalProperties":false}
				""", true, false, args -> ClientBridge.supply(GuiTools::state)
				.thenApply(json -> ToolResult.text(json.toString()))));

		registry.register(tool("gui_click", "Click a GUI slot or widget",
				"""
				Click the current GUI. This works on the main menu and every other screen, not just
				containers. Preferred: widget = index from gui_state (works for buttons, sliders,
				text fields, checkboxes). Alternative: x/y in screen GUI coordinates. For container
				screens, use slot = menu slot index and the action enum.
				Slot actions: left (pick up/place), right (split/place one), shift (quick move),
				throw, throw_all, swap (with hotbar 0-8), pickup_all.
				After the click the new GUI state is returned, including widgets and carried stack.
				""",
				"""
				{"type":"object","properties":{
				  "widget":{"type":"integer","description":"Widget index from gui_state"},
				  "slot":{"type":"integer","description":"Menu slot index from gui_state; -999 = outside"},
				  "x":{"type":"number"},"y":{"type":"number","description":"GUI coordinates"},
				  "button":{"type":"integer","minimum":1,"maximum":2,"default":1,
				    "description":"1 = left, 2 = right (Minecraft MouseButtonInfo convention)"},
				  "action":{"type":"string","enum":["left","right","shift","throw","throw_all","swap","pickup_all"],"default":"left"},
				  "hotbar":{"type":"integer","minimum":0,"maximum":8}
				},"additionalProperties":false}
				""", false, true, args -> {
			Integer slot = Args.optInt(args, "slot");
			Integer widget = Args.optInt(args, "widget");
			Double x = Args.optDouble(args, "x");
			Double y = Args.optDouble(args, "y");
			if (widget == null && slot == null && (x == null || y == null)) {
				throw new IllegalArgumentException("Provide widget, slot, or both x and y.");
			}
			return ClientBridge.submit(new GuiClickAction(slot, widget, x, y,
					Args.intOr(args, "button", 1), Args.stringOr(args, "action", "left"),
					Args.optInt(args, "hotbar")), 60);
		}));

		registry.register(tool("gui_key", "Press a key in the GUI",
				"""
				Press a named key in the current screen: escape, enter, tab, backspace, space,
				up, down, left or right. Use it to close screens, confirm dialogs or move focus
				on the main menu and other menus.
				""",
				"""
				{"type":"object","properties":{"key":{"type":"string"}},
				"required":["key"],"additionalProperties":false}
				""", false, false, args -> ClientBridge.submit(
				new GuiKeyAction(Args.requireString(args, "key")), 30)));

		registry.register(tool("gui_scroll", "Scroll the current GUI",
				"""
				Scroll a list or menu (world list, resource packs, options). x/y are GUI
				coordinates from gui_state; omit them to scroll at the screen center.
				Positive amount scrolls up, negative down.
				""",
				"""
				{"type":"object","properties":{
				  "x":{"type":"number"},"y":{"type":"number"},
				  "amount":{"type":"number","minimum":-20,"maximum":20}
				},"required":["amount"],"additionalProperties":false}
				""", false, false, args -> {
			double amount = Args.requireDouble(args, "amount");
			double x = Args.optDouble(args, "x") == null ? -1 : Args.optDouble(args, "x");
			double y = Args.optDouble(args, "y") == null ? -1 : Args.optDouble(args, "y");
			return ClientBridge.submit(new GuiScrollAction(x, y, amount), 30);
		}));

		registry.register(tool("gui_type", "Type into the focused GUI field",
				"""
				Type text into the focused text field of the current GUI (world name/seed, anvil
				name, sign editor, search box, book). Click the field with gui_click first if it is
				not focused. replace=true clears the field before typing.
				""",
				"""
				{"type":"object","properties":{
				  "text":{"type":"string","maxLength":256},
				  "replace":{"type":"boolean","default":false}
				},"required":["text"],"additionalProperties":false}
				""", false, false, args -> ClientBridge.submit(
				new GuiTypeAction(Args.requireString(args, "text"),
						Args.optBoolean(args, "replace", false)), 40)));

		registry.register(tool("select_item", "Select hotbar slot",
				"Select hotbar slot 0-8 (0 is the leftmost slot). This is instant and does not move items.",
				"""
				{"type":"object","properties":{"slot":{"type":"integer","minimum":0,"maximum":8}},
				"required":["slot"],"additionalProperties":false}
				""", false, false, args ->
				ClientBridge.submit(new SelectItemAction(Args.requireInt(args, "slot")), 30)));

		registry.register(tool("equip", "Equip an item",
				"""
				Find an item by id/name in the hotbar or main inventory and put it in the selected hotbar
				slot. If the item is already in the hotbar, merely selects it. Use this before mine/place/use.
				""",
				"""
				{"type":"object","properties":{"item":{"type":"string"}},
				"required":["item"],"additionalProperties":false}
				""", false, false, args ->
				ClientBridge.submit(new EquipAction(Args.requireString(args, "item")), 40)));

		registry.register(tool("craft", "Craft from recipe book",
				"""
				Craft an item using known recipes. With no container open this uses the 2x2 inventory grid.
				For a 3x3 recipe, first use a nearby crafting table (use with its coordinates), then call
				craft while the crafting screen is open. close=true closes the screen afterwards.
				Recipes must be known to the player. The tool places ingredients and shift-clicks the result.
				""",
				"""
				{"type":"object","properties":{
				  "item":{"type":"string","description":"Output item id or name"},
				  "times":{"type":"integer","minimum":1,"maximum":64,"default":1},
				  "close":{"type":"boolean","default":false}
				},"required":["item"],"additionalProperties":false}
				""", false, false, args -> ClientBridge.submit(
				new CraftAction(Args.requireString(args, "item"), Args.intOr(args, "times", 1),
						Args.optBoolean(args, "close", false)), 300)));

		registry.register(tool("drop", "Drop held item",
				"Drop the held stack, or a single item from it.",
				"""
				{"type":"object","properties":{"all":{"type":"boolean","default":true}},
				"additionalProperties":false}
				""", false, true, args ->
				ClientBridge.submit(new DropAction(Args.optBoolean(args, "all", true)), 40)));

		registry.register(tool("chat", "Send chat or command",
				"""
				Send a public chat message, or a server command when command=true. Commands omit the
				leading slash. Use this to ask players for help, coordinate, or run simple commands if
				the server permits them. Chat is visible to other players.
				""",
				"""
				{"type":"object","properties":{
				  "message":{"type":"string","maxLength":256},
				  "command":{"type":"boolean","default":false}
				},"required":["message"],"additionalProperties":false}
				""", false, false, args -> {
			if (!config.allowChat) {
				return CompletableFuture.completedFuture(ToolResult.error("Chat is disabled in config/llmcraft.json."));
			}
			return ClientBridge.submit(new ChatAction(Args.requireString(args, "message"),
					Args.optBoolean(args, "command", false)), 40);
		}));

		registry.register(tool("read_chat", "Read recent chat",
				"Return recent chat, system and action-bar messages. Optional kind filter: chat, system, actionbar.",
				"""
				{"type":"object","properties":{
				  "limit":{"type":"integer","minimum":1,"maximum":200,"default":30},
				  "kind":{"type":"string"}
				},"additionalProperties":false}
				""", true, false, args -> {
			int limit = Math.clamp(Args.intOr(args, "limit", 30), 1, 200);
			String kind = Args.optString(args, "kind");
			JsonObject result = new JsonObject();
			result.addProperty("ok", true);
			result.add("messages", ChatLog.snapshot(limit, kind));
			return CompletableFuture.completedFuture(ToolResult.text(result.toString()));
		}));

		registry.register(tool("screenshot", "Capture the screen",
				"""
				Capture the current frame and return it as an image (plus the saved PNG path).
				Use it to read the world or any menu visually: terrain, mobs, HUD, main menu,
				world list, buttons and text fields. The screenshot includes the HUD/GUI.
				""",
				"""
				{"type":"object","properties":{},"additionalProperties":false}
				""", true, false, args -> {
			if (!config.screenshots) {
				return CompletableFuture.completedFuture(ToolResult.error("Screenshots are disabled in config/llmcraft.json."));
			}
			return ClientBridge.submit(new ScreenshotAction(config.screenshotScale), 240);
		}));

		registry.register(tool("wait", "Wait",
				"""
				Let game time pass without moving. Useful for furnaces, crop growth, or letting mobs move.
				By default a hostile within 8 blocks interrupts the wait. Set force=true for a
				known-safe wait such as a mob farm, where you do not want the wait cancelled.
				""",
				"""
				{"type":"object","properties":{
				  "seconds":{"type":"number","minimum":0.1,"maximum":30,"default":1},
				  "force":{"type":"boolean","default":false}
				},"additionalProperties":false}
				""", false, false, args -> {
			double seconds = Args.optDouble(args, "seconds") == null ? 1.0 : Args.optDouble(args, "seconds");
			int ticks = Math.clamp((int) Math.round(seconds * 20.0), 1, maxActionTicks);
			return ClientBridge.submit(new WaitAction(ticks,
					Args.optBoolean(args, "force", false)), ticks + 20);
		}));

		registry.register(tool("close_screen", "Close GUI",
				"Close any open container or GUI. Items are not dropped. Use this before moving after opening a chest or crafting table.",
				"""
				{"type":"object","properties":{},"additionalProperties":false}
				""", false, false, args ->
				ClientBridge.submit(new CloseScreenAction(), 30)));

		registry.register(tool("sleep", "Time sleep (world runs)",
				"""
				Run the world for a specific duration, with the idle tick freeze suspended.
				Use this when you need time to actually pass: furnace smelting, crop growth,
				mob movement, daylight, or waiting out an effect. The call returns after the
				duration with the new time of day and player status. This is not sleeping in a
				bed; use sleep_in_bed for that.
				""",
				"""
				{"type":"object","properties":{
				  "seconds":{"type":"number","minimum":0.1,"maximum":60,"default":5},
				  "force":{"type":"boolean","default":false,
				    "description":"Do not interrupt for nearby hostiles (mob farms)."}
				},"additionalProperties":false}
				""", false, false, args -> {
			double seconds = Args.optDouble(args, "seconds") == null ? 5.0 : Args.optDouble(args, "seconds");
			int ticks = Math.clamp((int) Math.round(seconds * 20.0), 1, 1200);
			return ClientBridge.submit(new TimeSleepAction(ticks,
					Args.optBoolean(args, "force", false)), ticks + 40);
		}));

		registry.register(tool("sleep_in_bed", "Sleep in a bed",
				"""
				Sleep in a nearby bed to skip the night. The bed must be within reach (or use
				goto first) and it must be night or a thunderstorm. This is a real bed
				interaction: monsters can prevent sleep. For a timed wait, use sleep.
				""",
				"""
				{"type":"object","properties":{
				  "search_radius":{"type":"integer","minimum":2,"maximum":24,"default":8}
				},"additionalProperties":false}
				""", false, false, args ->
				ClientBridge.submit(new SleepAction(Args.intOr(args, "search_radius", 8)), 1300)));

		registry.register(tool("respawn", "Respawn",
				"Respawn after death. Returns the new position and health.",
				"""
				{"type":"object","properties":{},"additionalProperties":false}
				""", false, false, args ->
				ClientBridge.submit(new RespawnAction(), 100)));

		return registry;
	}

	private static McpTool tool(String name, String title, String description, String schemaJson,
			boolean readOnly, boolean destructive,
			Function<JsonObject, CompletableFuture<ToolResult>> function) {
		JsonObject schema = Args.schema(schemaJson);
		return new McpTool() {
			@Override
			public String name() {
				return name;
			}

			@Override
			public String title() {
				return title;
			}

			@Override
			public String description() {
				return description.strip();
			}

			@Override
			public JsonObject inputSchema() {
				return schema;
			}

			@Override
			public boolean readOnly() {
				return readOnly;
			}

			@Override
			public boolean destructive() {
				return destructive;
			}

			@Override
			public CompletableFuture<ToolResult> call(JsonObject arguments) {
				try {
					return function.apply(arguments == null ? new JsonObject() : arguments);
				} catch (Throwable t) {
					String message = t.getMessage() == null || t.getMessage().isBlank()
							? t.getClass().getSimpleName()
							: t.getMessage();
					return CompletableFuture.completedFuture(ToolResult.error(message));
				}
			}
		};
	}
}
