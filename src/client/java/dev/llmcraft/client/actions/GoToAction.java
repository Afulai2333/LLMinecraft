package dev.llmcraft.client.actions;

import com.google.gson.JsonObject;
import dev.llmcraft.client.ClientAction;
import dev.llmcraft.client.tools.StateSnapshot;
import dev.llmcraft.client.util.InputController;
import dev.llmcraft.client.util.Pathfinder;
import dev.llmcraft.mcp.ToolResult;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Walks to a block position using an A* path and simple steering. The path is recomputed a
 * couple of times if the player gets stuck, then the action reports failure instead of
 * thrashing forever.
 */
public final class GoToAction implements ClientAction {
	private final int targetX;
	private final int targetZ;
	private final Integer targetY;
	private final int maxNodes;
	private BlockPos goal;
	private BlockPos requestedGoal;
	private List<BlockPos> path;
	private int index;
	private int age;
	private int jumpTicks;
	private int stuckTicks;
	private int recoveries;
	private Vec3 lastCheck;
	private int checkAge;

	public GoToAction(int targetX, int targetZ, Integer targetY, int maxNodes) {
		this.targetX = targetX;
		this.targetZ = targetZ;
		this.targetY = targetY;
		this.maxNodes = maxNodes;
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
			return ToolResult.error("The player is riding a vehicle; dismount first.");
		}
		BlockPos requested = targetY != null
				? new BlockPos(targetX, targetY, targetZ)
				: new BlockPos(targetX, player.blockPosition().getY(), targetZ);
		if (targetY == null) {
			BlockPos surface = Pathfinder.findNearbyStandable(minecraft.level, requested, 1, 12);
			if (surface == null) {
				return ToolResult.error("Could not find a walkable surface at x=" + targetX + ", z=" + targetZ + ".");
			}
			goal = surface;
		} else {
			goal = requested;
		}
		requestedGoal = goal;
		Pathfinder.Result result = Pathfinder.find(minecraft.level, player.blockPosition(), goal, maxNodes, 128);
		if (!result.ok()) {
			return ToolResult.error(result.error());
		}
		path = result.path();
		index = 0;
		lastCheck = player.position();
		if (path.isEmpty()) {
			return done(player, 0);
		}
		// The pathfinder may snap the destination to the nearest walkable block. Use that
		// as the completion target so we do not claim to have reached an unreachable point.
		goal = path.getLast();
		return null;
	}

	@Override
	public ToolResult tick(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (player == null || minecraft.level == null) {
			return ToolResult.error("The world was closed while walking.");
		}
		if (player.isDeadOrDying()) {
			return ToolResult.error("The player died while walking.");
		}
		if (player.isInLava()) {
			return ToolResult.error("The player walked into lava. Move to safety and try a different route.");
		}
		age++;

		if (atGoal(player)) {
			return done(player, age);
		}
		if (path == null || index >= path.size()) {
			return atGoal(player) ? done(player, age)
					: ToolResult.error("Stopped short of " + goal.toShortString()
					+ "; the last walkable point is blocked or unreachable.");
		}

		while (index < path.size() && waypointReached(player, path.get(index))) {
			index++;
		}
		if (index >= path.size()) {
			return atGoal(player) ? done(player, age)
					: ToolResult.error("Stopped short of " + goal.toShortString()
					+ "; the last walkable point is blocked or unreachable.");
		}

		BlockPos waypoint = path.get(index);
		Vec3 aim = new Vec3(waypoint.getX() + 0.5, waypoint.getY() + 0.85, waypoint.getZ() + 0.5);
		InputController.lookAt(player, aim);
		InputController.set(minecraft.options.keyUp, true);

		double remaining = horizontalDistance(player.position(), Vec3.atCenterOf(goal));
		InputController.set(minecraft.options.keySprint,
				remaining > 3.5 && player.getFoodData().getFoodLevel() > 6);

		boolean needsJump = waypoint.getY() > player.getY() + 0.5
				|| (player.horizontalCollision && age % 7 == 0)
				|| (player.isInWater() && waypoint.getY() >= player.getY());
		if (needsJump) {
			InputController.set(minecraft.options.keyJump, true);
			jumpTicks = 5;
		} else if (jumpTicks > 0 && --jumpTicks == 0) {
			InputController.set(minecraft.options.keyJump, false);
		}

		if (++checkAge >= 20) {
			checkAge = 0;
			double moved = player.position().distanceTo(lastCheck);
			if (moved < 0.45) {
				stuckTicks++;
				if (stuckTicks >= 3) {
					recoveries++;
					if (recoveries > 2) {
						return ToolResult.error("Could not make progress toward " + goal.toShortString()
								+ ". The route is blocked; mine a path or choose another target.");
					}
					Pathfinder.Result retry = Pathfinder.find(minecraft.level, player.blockPosition(), goal,
							maxNodes, 128);
					if (!retry.ok()) {
						return ToolResult.error("Path became blocked and no alternative was found: " + retry.error());
					}
					path = retry.path();
					index = 0;
					stuckTicks = 0;
					InputController.set(minecraft.options.keyJump, true);
					jumpTicks = 6;
				}
			} else {
				stuckTicks = 0;
			}
			lastCheck = player.position();
		}
		return null;
	}

	private boolean atGoal(LocalPlayer player) {
		double horizontal = horizontalDistance(player.position(), Vec3.atCenterOf(goal));
		double vertical = Math.abs(player.getY() - goal.getY());
		return horizontal < 1.0 && vertical < 1.4;
	}

	private boolean waypointReached(LocalPlayer player, BlockPos waypoint) {
		double horizontal = horizontalDistance(player.position(), Vec3.atCenterOf(waypoint));
		double vertical = Math.abs(player.getY() - waypoint.getY());
		return horizontal < 0.8 && vertical < 1.3;
	}

	private static double horizontalDistance(Vec3 a, Vec3 b) {
		double dx = a.x - b.x;
		double dz = a.z - b.z;
		return Math.sqrt(dx * dx + dz * dz);
	}

	private ToolResult done(LocalPlayer player, int ticks) {
		JsonObject result = new JsonObject();
		result.addProperty("ok", true);
		result.addProperty("message", "Reached " + goal.toShortString() + " (x,y,z).");
		result.addProperty("x", StateSnapshot.round(player.getX()));
		result.addProperty("y", StateSnapshot.round(player.getY()));
		result.addProperty("z", StateSnapshot.round(player.getZ()));
		result.addProperty("ticks", ticks);
		result.addProperty("path_remaining", path == null ? 0 : Math.max(0, path.size() - index));
		if (requestedGoal != null && !requestedGoal.equals(goal)) {
			result.addProperty("requested", requestedGoal.toShortString());
			result.addProperty("note", "Snapped to the nearest walkable block.");
		}
		return ToolResult.text(result.toString());
	}

	@Override
	public void cleanup(Minecraft minecraft) {
		InputController.releaseMovement(minecraft);
	}

	@Override
	public String name() {
		return "goto";
	}
}
