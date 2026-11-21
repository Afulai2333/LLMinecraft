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
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Sleeps in a nearby bed, skipping the night. This is intentionally a real bed interaction:
 * it refuses during the day and when no bed is in reach, instead of silently advancing time.
 * For a plain delay use the {@code wait} tool.
 */
public final class SleepAction implements ClientAction {
	private final int searchRadius;
	private int age;
	private int phase;
	private BlockPos bed;
	private int waitTicks;

	public SleepAction(int searchRadius) {
		this.searchRadius = Math.clamp(searchRadius, 2, 24);
	}

	@Override
	public ToolResult start(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		Level level = minecraft.level;
		if (player == null || level == null || minecraft.gameMode == null) {
			return ToolResult.error("No world is loaded.");
		}
		if (minecraft.gui.screen() != null) {
			return ToolResult.error("A GUI screen is open; close it before sleeping.");
		}
		long time = level.getOverworldClockTime();
		long dayTime = Math.floorMod(time, 24000L);
		boolean night = dayTime >= 12542L && dayTime <= 23460L;
		if (!night && !level.isThundering()) {
			return ToolResult.error("It is daytime (time " + dayTime
					+ "); beds only work at night or during a thunderstorm. Use wait to pass time.");
		}
		bed = findBed(level, player);
		if (bed == null) {
			return ToolResult.error("No bed within " + searchRadius
					+ " blocks. Craft/place a bed first, or use wait if you only need to pass time.");
		}
		double distance = Math.sqrt(player.getEyePosition().distanceToSqr(Vec3.atCenterOf(bed)));
		if (distance > player.blockInteractionRange() + 1.0) {
			return ToolResult.error("The nearest bed is " + StateSnapshot.round(distance)
					+ " blocks away; use goto to get within reach first.");
		}
		Vec3 center = Vec3.atCenterOf(bed);
		InputController.lookAt(player, center);
		Direction face = Direction.getApproximateNearest(player.getEyePosition().subtract(center));
		Vec3 hitVec = center.add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
		BlockHitResult hit = new BlockHitResult(hitVec, face, bed, false);
		InteractionResult result = minecraft.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
		if (result == InteractionResult.FAIL) {
			return ToolResult.error("The bed rejected the interaction; it may be obstructed.");
		}
		phase = 1;
		return null;
	}

	@Override
	public ToolResult tick(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (player == null || minecraft.level == null) {
			return ToolResult.error("The world was closed while sleeping.");
		}
		if (player.isDeadOrDying()) {
			return ToolResult.error("The player died while trying to sleep.");
		}
		age++;
		if (phase == 1) {
			if (player.isSleeping()) {
				phase = 2;
				waitTicks = 0;
				return null;
			}
			if (age > 80) {
				return ToolResult.error("Could not fall asleep. A monster may be nearby, or the bed is obstructed.");
			}
			return null;
		}
		if (phase == 2) {
			waitTicks++;
			if (!player.isSleeping()) {
				return done(player);
			}
			if (waitTicks > 1200) {
				return ToolResult.error("Still sleeping after 60 seconds; the night may be longer than expected.");
			}
			return null;
		}
		return done(player);
	}

	private ToolResult done(LocalPlayer player) {
		long time = minecraftTime(player);
		JsonObject json = new JsonObject();
		json.addProperty("ok", true);
		json.addProperty("message", "Slept in the bed; it is now "
				+ StateSnapshot.phase(Math.floorMod(time, 24000L)) + ".");
		json.addProperty("time_of_day", Math.floorMod(time, 24000L));
		json.addProperty("health", StateSnapshot.round(player.getHealth()));
		json.addProperty("x", StateSnapshot.round(player.getX()));
		json.addProperty("y", StateSnapshot.round(player.getY()));
		json.addProperty("z", StateSnapshot.round(player.getZ()));
		return ToolResult.text(json.toString());
	}

	private long minecraftTime(LocalPlayer player) {
		return player.level().getOverworldClockTime();
	}

	private BlockPos findBed(Level level, LocalPlayer player) {
		BlockPos center = player.blockPosition();
		BlockPos best = null;
		double bestDistance = Double.MAX_VALUE;
		for (int dx = -searchRadius; dx <= searchRadius; dx++) {
			for (int dz = -searchRadius; dz <= searchRadius; dz++) {
				for (int dy = -4; dy <= 4; dy++) {
					BlockPos pos = center.offset(dx, dy, dz);
					if (!level.isLoaded(pos)) {
						continue;
					}
					BlockState state = level.getBlockState(pos);
					if (state.isAir()) {
						continue;
					}
					String id = BlockUtil.id(state);
					if (!id.endsWith("_bed")) {
						continue;
					}
					double distance = player.distanceToSqr(Vec3.atCenterOf(pos));
					if (distance < bestDistance) {
						bestDistance = distance;
						best = pos.immutable();
					}
				}
			}
		}
		return best;
	}

	@Override
	public String name() {
		return "sleep";
	}
}
