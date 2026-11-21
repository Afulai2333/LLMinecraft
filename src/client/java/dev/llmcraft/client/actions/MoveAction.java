package dev.llmcraft.client.actions;

import com.google.gson.JsonObject;
import dev.llmcraft.client.ClientAction;
import dev.llmcraft.client.tools.StateSnapshot;
import dev.llmcraft.client.util.InputController;
import dev.llmcraft.mcp.ToolResult;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

/** Holds one or more movement keys for a fixed number of ticks. */
public final class MoveAction implements ClientAction {
	private final String direction;
	private final int ticks;
	private final boolean sprint;
	private final boolean sneak;
	private final boolean jump;
	private int age;
	private Vec3 start;

	public MoveAction(String direction, int ticks, boolean sprint, boolean sneak, boolean jump) {
		this.direction = direction;
		this.ticks = ticks;
		this.sprint = sprint;
		this.sneak = sneak;
		this.jump = jump;
	}

	@Override
	public ToolResult start(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (player == null || minecraft.level == null) {
			return ToolResult.error("No world is loaded.");
		}
		if (minecraft.gui.screen() != null) {
			return ToolResult.error("A GUI screen is open (" + minecraft.gui.screen().getClass().getSimpleName()
					+ "). Close it before moving.");
		}
		if (player.isPassenger()) {
			return ToolResult.error("The player is riding a vehicle; dismount before moving.");
		}
		KeyMapping key = switch (direction) {
			case "forward" -> minecraft.options.keyUp;
			case "back", "backward" -> minecraft.options.keyDown;
			case "left" -> minecraft.options.keyLeft;
			case "right" -> minecraft.options.keyRight;
			default -> null;
		};
		if (key == null) {
			return ToolResult.error("direction must be forward, back, left or right.");
		}
		InputController.set(key, true);
		if (sprint) {
			InputController.set(minecraft.options.keySprint, true);
		}
		if (sneak) {
			InputController.set(minecraft.options.keyShift, true);
		}
		if (jump) {
			InputController.set(minecraft.options.keyJump, true);
		}
		start = player.position();
		return null;
	}

	@Override
	public ToolResult tick(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (player == null || minecraft.level == null) {
			return ToolResult.error("The world was closed while moving.");
		}
		if (player.isDeadOrDying()) {
			return ToolResult.error("The player died while moving.");
		}
		if (++age >= ticks) {
			JsonObject result = new JsonObject();
			result.addProperty("ok", true);
			result.addProperty("message", "Moved " + direction + ".");
			result.addProperty("ticks", ticks);
			result.addProperty("x", StateSnapshot.round(player.getX()));
			result.addProperty("y", StateSnapshot.round(player.getY()));
			result.addProperty("z", StateSnapshot.round(player.getZ()));
			result.addProperty("moved_blocks", StateSnapshot.round(player.position().distanceTo(start)));
			result.addProperty("on_ground", player.onGround());
			return ToolResult.text(result.toString());
		}
		return null;
	}

	@Override
	public void cleanup(Minecraft minecraft) {
		InputController.releaseMovement(minecraft);
	}

	@Override
	public String name() {
		return "move";
	}
}
