package dev.llmcraft.client.actions;

import com.google.gson.JsonObject;
import dev.llmcraft.client.ClientAction;
import dev.llmcraft.client.tools.StateSnapshot;
import dev.llmcraft.client.util.BlockUtil;
import dev.llmcraft.client.util.InputController;
import dev.llmcraft.mcp.ToolResult;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Jumps onto the top of a specific adjacent block. The destination must be one block above
 * the current feet level, with clear headroom; this is a deliberate jump, not pathfinding.
 */
public final class JumpOnAction implements ClientAction {
	private final BlockPos target;
	private final boolean sprint;
	private int age;
	private int jumpTicks;
	private int preTicks;
	private int forwardTicks;
	private boolean jumpStarted;
	private boolean sprinting;
	private boolean forwardStarted;

	public JumpOnAction(BlockPos target, boolean sprint) {
		this.target = target;
		this.sprint = sprint;
	}

	@Override
	public ToolResult start(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (player == null || minecraft.level == null || minecraft.gameMode == null) {
			return ToolResult.error("No world is loaded.");
		}
		if (minecraft.gui.screen() != null) {
			return ToolResult.error("A GUI screen is open; close it before jumping.");
		}
		if (!minecraft.level.isLoaded(target) || !minecraft.level.isLoaded(target.above())) {
			return ToolResult.error("Target block is not loaded.");
		}
		if (minecraft.level.getBlockState(target).getCollisionShape(minecraft.level, target).isEmpty()) {
			return ToolResult.error("There is no solid block at " + target.toShortString() + ".");
		}
		BlockPos destination = target.above();
		if (!BlockUtil.standable(minecraft.level, destination)) {
			return ToolResult.error("There is no room to stand on top of " + target.toShortString() + ".");
		}
		double deltaY = destination.getY() - player.getY();
		if (deltaY > 1.3) {
			return ToolResult.error("The target is more than one block above; jump_on only handles a one-block step.");
		}
		if (deltaY < -2.5) {
			return ToolResult.error("The target is too far below; walk or drop down to it instead.");
		}
		double horizontal = Math.hypot(target.getX() + 0.5 - player.getX(),
				target.getZ() + 0.5 - player.getZ());
		if (horizontal > 4.5) {
			return ToolResult.error("Target block is " + StateSnapshot.round(horizontal)
					+ " blocks away horizontally; move closer (max sprint-jump range is about 4.5).");
		}
		if (horizontal < 0.25 && player.getY() >= destination.getY() - 0.05) {
			JsonObject json = new JsonObject();
			json.addProperty("ok", true);
			json.addProperty("message", "Already standing on " + target.toShortString() + ".");
			json.addProperty("x", StateSnapshot.round(player.getX()));
			json.addProperty("y", StateSnapshot.round(player.getY()));
			json.addProperty("z", StateSnapshot.round(player.getZ()));
			return ToolResult.text(json.toString());
		}
		InputController.lookAt(player, new Vec3(target.getX() + 0.5, destination.getY(), target.getZ() + 0.5));
		if (horizontal > 0.35) {
			InputController.set(minecraft.options.keyUp, true);
			forwardStarted = true;
			forwardTicks = Math.clamp((int) Math.round(9 + horizontal * 4), 10, 28);
			sprinting = sprint || horizontal > 1.8;
			if (sprinting) {
				InputController.set(minecraft.options.keySprint, true);
			}
		}
		// Long jumps need sprint speed before leaving the ground. A short run-up helps,
		// but jump immediately when the next block is already the gap edge.
		preTicks = horizontal > 2.2 ? 2 : 0;
		if (preTicks == 0 || !hasSupportAhead(player)) {
			startJump();
		}
		return null;
	}

	private void startJump() {
		jumpStarted = true;
		InputController.set(net.minecraft.client.Minecraft.getInstance().options.keyJump, true);
		jumpTicks = 3;
	}

	private boolean hasSupportAhead(LocalPlayer player) {
		int sx = Integer.signum(target.getX() - player.blockPosition().getX());
		int sz = Integer.signum(target.getZ() - player.blockPosition().getZ());
		BlockPos ahead = player.blockPosition().offset(sx, 0, sz);
		return BlockUtil.standable(player.level(), ahead);
	}

	@Override
	public ToolResult tick(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (player == null || minecraft.level == null) {
			return ToolResult.error("The world was closed while jumping.");
		}
		age++;
		if (!jumpStarted) {
			preTicks--;
			if (preTicks <= 0 || !hasSupportAhead(player)) {
				startJump();
			}
			return null;
		}
		if (jumpTicks > 0) {
			jumpTicks--;
			if (jumpTicks == 0) {
				InputController.set(minecraft.options.keyJump, false);
			}
		}
		if (forwardTicks > 0) {
			forwardTicks--;
			if (forwardTicks == 0) {
				InputController.set(minecraft.options.keyUp, false);
				InputController.set(minecraft.options.keySprint, false);
			}
		}
		double dx = Math.abs(player.getX() - (target.getX() + 0.5));
		double dz = Math.abs(player.getZ() - (target.getZ() + 0.5));
		if (forwardStarted && dx < 0.62 && dz < 0.62) {
			InputController.set(minecraft.options.keyUp, false);
			InputController.set(minecraft.options.keySprint, false);
		}
		boolean onDestination = player.getY() >= target.getY() + 0.85
				&& dx < 0.9 && dz < 0.9;
		if (player.onGround() && onDestination) {
			InputController.set(minecraft.options.keyUp, false);
			InputController.set(minecraft.options.keySprint, false);
			InputController.set(minecraft.options.keyJump, false);
			JsonObject json = new JsonObject();
			json.addProperty("ok", true);
			json.addProperty("message", "Jumped onto " + target.toShortString()
					+ " (feet at " + StateSnapshot.round(player.getY()) + ").");
			json.addProperty("x", StateSnapshot.round(player.getX()));
			json.addProperty("y", StateSnapshot.round(player.getY()));
			json.addProperty("z", StateSnapshot.round(player.getZ()));
			json.addProperty("distance", StateSnapshot.round(Math.hypot(
					target.getX() + 0.5 - player.getX(),
					target.getZ() + 0.5 - player.getZ())));
			json.addProperty("sprint", sprinting);
			json.addProperty("on_ground", true);
			return ToolResult.text(json.toString());
		}
		if (player.getY() < target.getY() - 1.5) {
			return ToolResult.error("Fell below " + target.toShortString()
					+ "; the gap is too wide or the landing was missed.");
		}
		if (age > 55) {
			return ToolResult.error("Jump did not land on " + target.toShortString()
					+ "; walk closer and try again.");
		}
		return null;
	}

	@Override
	public void cleanup(Minecraft minecraft) {
		InputController.releaseMovement(minecraft);
	}

	@Override
	public String name() {
		return "jump_on";
	}
}
