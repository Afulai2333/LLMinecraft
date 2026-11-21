package dev.llmcraft.client.actions;

import com.google.gson.JsonObject;
import dev.llmcraft.client.ClientAction;
import dev.llmcraft.client.tools.StateSnapshot;
import dev.llmcraft.client.util.BlockUtil;
import dev.llmcraft.client.util.InputController;
import dev.llmcraft.client.util.ItemUtil;
import dev.llmcraft.mcp.ToolResult;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Mines one block by holding the vanilla attack key, not by firing one click per tick.
 * Holding is what lets survival block-breaking accumulate progress; a click every tick
 * would reset the destroy stage.
 */
public final class MineAction implements ClientAction {
	private final BlockPos pos;
	private final boolean autoTool;
	private final String toolQuery;
	private final Integer toolSlot;
	private boolean attackStarted;
	private boolean losChecked;
	private int age;
	private int startDelay;
	private String blockId = "minecraft:air";
	private String toolUsed = "minecraft:air";

	public MineAction(BlockPos pos, boolean autoTool, String toolQuery, Integer toolSlot) {
		this.pos = pos;
		this.autoTool = autoTool;
		this.toolQuery = toolQuery;
		this.toolSlot = toolSlot;
	}

	@Override
	public ToolResult start(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (player == null || minecraft.level == null || minecraft.gameMode == null) {
			return ToolResult.error("No world is loaded.");
		}
		if (minecraft.gui.screen() != null) {
			return ToolResult.error("A GUI screen is open; close it before mining.");
		}
		if (!minecraft.level.isLoaded(pos)) {
			return ToolResult.error("Block " + pos.toShortString() + " is not loaded.");
		}
		BlockState state = minecraft.level.getBlockState(pos);
		this.blockId = BlockUtil.id(state);
		if (state.isAir()) {
			return ToolResult.error("There is no block at " + pos.toShortString() + ".");
		}
		if (state.getDestroySpeed(minecraft.level, pos) < 0) {
			return ToolResult.error(blockId + " is unbreakable.");
		}
		if (!withinReach(player)) {
			double distance = Math.sqrt(player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)));
			return ToolResult.error("Block is " + StateSnapshot.round(distance)
					+ " blocks away; reach is " + StateSnapshot.round(player.blockInteractionRange())
					+ ". Use goto to get closer.");
		}
		InputController.lookAt(player, Vec3.atCenterOf(pos));

		int slot = -1;
		if (toolSlot != null) {
			if (toolSlot < 0 || toolSlot > 8) {
				return ToolResult.error("tool_slot must be between 0 and 8.");
			}
			if (player.getInventory().getItem(toolSlot).isEmpty()) {
				return ToolResult.error("Hotbar slot " + toolSlot + " is empty.");
			}
			slot = toolSlot;
		} else if (toolQuery != null && !toolQuery.isBlank()) {
			slot = ItemUtil.findInHotbar(player, toolQuery);
			if (slot < 0) {
				return ToolResult.error("No hotbar item matches tool '" + toolQuery
						+ "'. Use equip first, or pass tool_slot.");
			}
		} else if (autoTool) {
			slot = ItemUtil.bestToolSlot(player, state);
		}
		if (slot >= 0) {
			int previous = player.getInventory().getSelectedSlot();
			boolean changed = slot != previous;
			player.getInventory().setSelectedSlot(slot);
			toolUsed = ItemUtil.id(player.getInventory().getSelectedItem());
			if (changed) {
				startDelay = 2;
			}
		} else {
			toolUsed = ItemUtil.id(player.getMainHandItem());
		}
		dev.llmcraft.client.InputLock.setMining(true);
		return null;
	}

	@Override
	public ToolResult tick(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (player == null || minecraft.level == null || minecraft.gameMode == null) {
			return ToolResult.error("The world was closed while mining.");
		}
		if (player.isDeadOrDying()) {
			return ToolResult.error("The player died while mining.");
		}
		age++;
		if (minecraft.level.getBlockState(pos).isAir()) {
			return done(minecraft, player);
		}
		if (startDelay > 0) {
			startDelay--;
			return null;
		}
		if (!withinReach(player)) {
			return ToolResult.error("The block moved out of reach; walk closer with goto.");
		}

		// Hold the crosshair on the target and keep the real attack key down. Vanilla then
		// accumulates destroy progress, exactly like a human holding left-click.
		InputController.lookAt(player, Vec3.atCenterOf(pos));
		if (!attackStarted) {
			InputController.set(minecraft.options.keyAttack, true);
			attackStarted = true;
			age = 0;
			return null;
		}

		if (!losChecked && age >= 2) {
			losChecked = true;
			if (minecraft.hitResult instanceof BlockHitResult hit
					&& !hit.getBlockPos().equals(pos)) {
				BlockState blocker = minecraft.level.getBlockState(hit.getBlockPos());
				if (!blocker.getCollisionShape(minecraft.level, hit.getBlockPos()).isEmpty()) {
					return ToolResult.error("Line of sight to " + blockId + " at " + pos.toShortString()
							+ " is blocked by " + BlockUtil.id(blocker) + " at "
							+ hit.getBlockPos().toShortString()
							+ ". Mine that block first, or move to a clear angle.");
				}
			}
		}
		if (age > 600) {
			return ToolResult.error("Mining " + blockId + " timed out after 30 seconds.");
		}
		return null;
	}

	private ToolResult done(Minecraft minecraft, LocalPlayer player) {
		JsonObject result = new JsonObject();
		result.addProperty("ok", true);
		result.addProperty("message", "Mined " + blockId + " at " + pos.toShortString() + ".");
		result.addProperty("block", blockId);
		result.addProperty("x", pos.getX());
		result.addProperty("y", pos.getY());
		result.addProperty("z", pos.getZ());
		result.addProperty("ticks", age);
		result.addProperty("tool", toolUsed);
		result.addProperty("tool_slot", player.getInventory().getSelectedSlot());
		result.add("held", StateSnapshot.item(player.getMainHandItem(),
				player.getInventory().getSelectedSlot()));
		return ToolResult.text(result.toString());
	}

	private boolean withinReach(LocalPlayer player) {
		double range = player.blockInteractionRange() + 0.6;
		return player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) <= range * range;
	}

	@Override
	public void cleanup(Minecraft minecraft) {
		dev.llmcraft.client.InputLock.setMining(false);
		InputController.set(minecraft.options.keyAttack, false);
		if (minecraft.gameMode != null) {
			minecraft.gameMode.stopDestroyBlock();
		}
		InputController.releaseMovement(minecraft);
	}

	@Override
	public String name() {
		return "mine";
	}
}
