package dev.llmcraft.client;

import com.google.gson.JsonObject;
import dev.llmcraft.LLMinecraft;
import dev.llmcraft.client.util.InputController;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/**
 * Blocks physical keyboard/mouse input while the agent owns the controls.
 *
 * <p>The human cannot take over silently. The agent calls {@code request_user_takeover},
 * which opens an Agree/Refuse dialog. Only Agree unlocks input and gives the human control;
 * Refuse keeps the agent in control. While the human plays, Ctrl+C ends the takeover and
 * locks input again. Locking starts on the first MCP call, never on the main menu.</p>
 */
public final class InputLock {
	public static final String TAKEOVER_END_KEY = "Ctrl+C";
	private static final int GLFW_PRESS = 1;

	public enum Mode {
		AGENT,
		REQUESTED,
		USER
	}

	private static volatile Mode mode = Mode.AGENT;
	private static volatile boolean active;
	private static volatile boolean promptOpen;
	private static volatile boolean mining;
	private static volatile String reason = "";
	private static boolean activationAnnounced;

	private InputLock() {
	}

	/** First agent activity (any MCP call) claims the controls. */
	public static synchronized void activate(Minecraft minecraft) {
		if (active) {
			return;
		}
		active = true;
		activationAnnounced = false;
		if (mode == Mode.USER) {
			return;
		}
		mode = Mode.AGENT;
		reason = "";
		releaseCursor(minecraft);
		controlEvent("agent", "Agent took control");
		if (minecraft != null && minecraft.player != null && !activationAnnounced) {
			activationAnnounced = true;
			minecraft.player.sendOverlayMessage(Component.literal(
					"LLMinecraft: agent control - ask the agent to hand over"));
		}
	}

	/** Agent-initiated handover: show an Agree/Refuse dialog instead of unlocking directly. */
	public static synchronized void requestByAgent(Minecraft minecraft, String why) {
		if (minecraft == null) {
			return;
		}
		activate(minecraft);
		if (mode == Mode.USER || mode == Mode.REQUESTED) {
			return;
		}
		mode = Mode.REQUESTED;
		promptOpen = true;
		reason = why == null ? "" : why;
		releaseCursor(minecraft);
		InputController.releaseMovement(minecraft);
		KeyMapping.releaseAll();
		ClientBridge.cancelAll("Agent requested user takeover.");
		Component title = Component.literal("Control handover");
		String shortReason = reason.length() > 70 ? reason.substring(0, 70) + "..." : reason;
		Component message = Component.literal(
				"The LLM agent requests control."
						+ (shortReason.isBlank() ? "" : "\n\nReason: " + shortReason)
						+ "\n\nAgree: you play until Ctrl+C."
						+ "\nRefuse: the agent keeps control.");
		minecraft.gui.setScreen(new ConfirmScreen(
				accepted -> decide(minecraft, accepted),
				title,
				message,
				Component.literal("Agree"),
				Component.literal("Refuse")));
		show(minecraft, "Takeover requested - choose Agree or Refuse");
		controlEvent("requested", "Agent requested user takeover: " + reason);
		LLMinecraft.LOGGER.info("Agent requested user takeover: {}", reason);
	}

	private static synchronized void decide(Minecraft minecraft, boolean accepted) {
		promptOpen = false;
		if (minecraft.gui.screen() instanceof ConfirmScreen) {
			minecraft.gui.setScreen(null);
		}
		if (accepted) {
			mode = Mode.USER;
			reason = "";
			InputController.releaseMovement(minecraft);
			KeyMapping.releaseAll();
			if (minecraft.mouseHandler != null) {
				minecraft.mouseHandler.resyncMousePosition();
				if (minecraft.gui.screen() == null) {
					minecraft.mouseHandler.grabMouse();
				}
			}
			show(minecraft, "User control - press Ctrl+C to end takeover");
			controlEvent("user", "User accepted control");
		} else {
			mode = Mode.AGENT;
			reason = "";
			show(minecraft, "Takeover refused - agent control continues");
			controlEvent("agent", "User refused takeover");
		}
	}

	private static synchronized void endUserTakeover(Minecraft minecraft) {
		mode = Mode.AGENT;
		reason = "";
		releaseCursor(minecraft);
		show(minecraft, "Agent control resumed - ask the agent to hand over again");
		controlEvent("agent", "User ended takeover with Ctrl+C");
	}

	public static synchronized boolean resumeByAgent(Minecraft minecraft) {
		if (mode == Mode.USER) {
			return false;
		}
		promptOpen = false;
		if (minecraft != null && minecraft.gui.screen() instanceof ConfirmScreen) {
			minecraft.gui.setScreen(null);
		}
		mode = Mode.AGENT;
		reason = "";
		show(minecraft, "Takeover request cancelled - agent control");
		return true;
	}

	public static void tick(Minecraft minecraft) {
		if (minecraft == null) {
			active = false;
			mode = Mode.AGENT;
			promptOpen = false;
			return;
		}
		if (!McpServerManager.isRunning()) {
			active = false;
			mode = Mode.AGENT;
			promptOpen = false;
			return;
		}
		if (promptOpen && !(minecraft.gui.screen() instanceof ConfirmScreen)) {
			promptOpen = false;
			mode = Mode.AGENT;
		}
		if (shouldReleaseCursor()) {
			releaseCursor(minecraft);
		}
	}

	public static void shutdown(Minecraft minecraft) {
		active = false;
		mode = Mode.AGENT;
		promptOpen = false;
		reason = "";
		if (minecraft != null) {
			InputController.releaseMovement(minecraft);
			if (minecraft.mouseHandler != null) {
				minecraft.mouseHandler.releaseMouse();
			}
		}
	}

	public static boolean blocksInput() {
		if (!active || promptOpen || mode == Mode.USER) {
			return false;
		}
		return McpServerManager.isRunning();
	}

	public static boolean agentCanAct() {
		return mode == Mode.AGENT;
	}

	/** True while the agent (or a pending prompt) owns the game; the OS cursor must stay free. */
	public static boolean shouldReleaseCursor() {
		return active && mode != Mode.USER && McpServerManager.isRunning();
	}

	/** Agent control must keep ticking even when the window loses focus. */
	public static boolean shouldPreventPause() {
		return active && mode != Mode.USER && McpServerManager.isRunning();
	}

	/** While a mine action holds attack, vanilla checks isMouseGrabbed() before continuing. */
	public static void setMining(boolean value) {
		mining = value;
	}

	public static boolean simulateMouseGrab() {
		return mining && active && mode != Mode.USER && McpServerManager.isRunning();
	}

	private static void releaseCursor(Minecraft minecraft) {
		if (minecraft != null && minecraft.mouseHandler != null
				&& minecraft.mouseHandler.isMouseGrabbed()) {
			minecraft.mouseHandler.releaseMouse();
		}
	}

	public static boolean userHasControl() {
		return mode == Mode.USER;
	}

	public static boolean isActive() {
		return active;
	}

	public static Mode mode() {
		return mode;
	}

	public static String reason() {
		return reason;
	}

	/** Called from the keyboard mixin. Returns true when the event must be cancelled. */
	public static boolean handleKey(int action, KeyEvent event) {
		if (!active) {
			return false;
		}
		if (mode == Mode.USER) {
			// Minecraft exposes layout-aware Ctrl+C as isCopy(); raw keycodes vary by layout.
			if (action == GLFW_PRESS && event != null && event.isCopy()) {
				endUserTakeover(ClientBridge.mc());
				return true;
			}
			return false;
		}
		if (promptOpen) {
			return false;
		}
		return blocksInput();
	}

	public static JsonObject status() {
		Minecraft minecraft = ClientBridge.mc();
		JsonObject json = new JsonObject();
		json.addProperty("ok", true);
		json.addProperty("mode", mode.name().toLowerCase());
		json.addProperty("active", active);
		json.addProperty("prompt_open", promptOpen);
		json.addProperty("agent_can_act", mode == Mode.AGENT);
		json.addProperty("user_has_control", mode == Mode.USER);
		json.addProperty("input_locked", blocksInput());
		json.addProperty("mouse_grabbed", minecraft != null && minecraft.mouseHandler != null
				&& minecraft.mouseHandler.isMouseGrabbed());
		json.addProperty("takeover_end_key", TAKEOVER_END_KEY);
		json.addProperty("reason", reason);
		json.addProperty("in_world", minecraft != null && minecraft.player != null && minecraft.level != null);
		json.addProperty("mcp_running", McpServerManager.isRunning());
		return json;
	}

	private static void controlEvent(String mode, String message) {
		JsonObject data = new JsonObject();
		data.addProperty("mode", mode);
		data.addProperty("message", message);
		EventLog.record("control", "info", data);
	}

	private static void show(Minecraft minecraft, String text) {
		if (minecraft != null && minecraft.player != null) {
			minecraft.player.sendOverlayMessage(Component.literal(text));
		} else {
			LLMinecraft.LOGGER.info("LLMinecraft: {}", text);
		}
	}
}
