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
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Places a block from the hotbar against a neighbouring support block. */
public final class PlaceAction implements ClientAction {
	private final BlockPos target;
	private final String blockQuery;
	private final String faceName;
	private final boolean jump;
	private int age;
	private int delay;
	private int jumpTicks;
	private boolean needJump;
	private boolean jumped;
	private boolean placementAttempted;
	private int placementAge;
	private Direction supportDirection;
	private String expectedBlock;
	private String placedBlock;

	public PlaceAction(BlockPos target, String blockQuery, String faceName, boolean jump) {
		this.target = target;
		this.blockQuery = blockQuery;
		this.faceName = faceName;
		this.jump = jump;
	}

	@Override
	public ToolResult start(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (player == null || minecraft.level == null || minecraft.gameMode == null) {
			return ToolResult.error("No world is loaded.");
		}
		if (minecraft.gui.screen() != null) {
			return ToolResult.error("A GUI screen is open; close it before placing blocks.");
		}
		if (!minecraft.level.isLoaded(target)) {
			return ToolResult.error("Target position is not loaded.");
		}
		BlockState existing = minecraft.level.getBlockState(target);
		if (!existing.isAir() && !existing.canBeReplaced()) {
			return ToolResult.error("Position " + target.toShortString() + " is occupied by "
					+ BlockUtil.id(existing) + ".");
		}
		AABB targetBox = targetBox();
		boolean intersects = targetBox.intersects(player.getBoundingBox());
		if (intersects && !jump) {
			return ToolResult.error("The player is standing in that space. "
					+ "Pass jump=true to jump and place the block under your feet.");
		}
		this.needJump = intersects;
		if (!withinReach(player)) {
			return ToolResult.error("Target is too far away; use goto to get within "
					+ StateSnapshot.round(player.blockInteractionRange()) + " blocks.");
		}

		if (blockQuery != null && !blockQuery.isBlank()) {
			int slot = ItemUtil.findInHotbar(player, blockQuery);
			if (slot < 0) {
				return ToolResult.error("No hotbar item matches '" + blockQuery + "'. Select a block item first.");
			}
			if (slot != player.getInventory().getSelectedSlot()) {
				player.getInventory().setSelectedSlot(slot);
				delay = 2;
			}
		}

		ItemStack held = player.getMainHandItem();
		if (!(held.getItem() instanceof BlockItem blockItem)) {
			return ToolResult.error("The held item is not a placeable block: " + ItemUtil.id(held) + ".");
		}
		expectedBlock = net.minecraft.core.registries.BuiltInRegistries.BLOCK
				.getKey(blockItem.getBlock()).toString();

		if (faceName != null && !faceName.isBlank()) {
			supportDirection = Direction.byName(faceName.toLowerCase());
			if (supportDirection == null) {
				return ToolResult.error("face must be one of up, down, north, south, east, west.");
			}
			BlockPos support = target.relative(supportDirection);
			if (minecraft.level.getBlockState(support).isAir()) {
				return ToolResult.error("No support block at " + support.toShortString() + ".");
			}
		} else {
			supportDirection = BlockUtil.findSupport(minecraft.level, target);
			if (supportDirection == null) {
				return ToolResult.error("No neighbouring block to place against.");
			}
		}
		Vec3 hit = Vec3.atCenterOf(target).add(
				supportDirection.getStepX() * 0.5,
				supportDirection.getStepY() * 0.5,
				supportDirection.getStepZ() * 0.5
		);
		InputController.lookAt(player, hit);
		if (needJump) {
			InputController.set(minecraft.options.keyJump, true);
			jumpTicks = 3;
		}
		return null;
	}

	@Override
	public ToolResult tick(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (player == null || minecraft.level == null || minecraft.gameMode == null) {
			return ToolResult.error("The world was closed while placing.");
		}
		age++;
		if (needJump) {
			if (jumpTicks > 0) {
				jumpTicks--;
				if (jumpTicks == 0) {
					InputController.set(minecraft.options.keyJump, false);
				}
			}
			InputController.lookAt(player, hitPoint());
			if (targetBox().intersects(player.getBoundingBox())) {
				if (age > 25) {
					return ToolResult.error("Could not jump clear of the target block "
							+ target.toShortString() + " (ceiling or too little headroom).");
				}
				return null;
			}
			needJump = false;
			jumped = true;
		}
		if (delay > 0) {
			delay--;
			return null;
		}
		if (!placementAttempted && age >= 2) {
			placementAttempted = true;
			placementAge = age;
			BlockPos support = target.relative(supportDirection);
			Vec3 hit = hitPoint();
			InputController.lookAt(player, hit);
			BlockHitResult blockHit = new BlockHitResult(hit, supportDirection.getOpposite(), support, false);
			InteractionResult result = minecraft.gameMode.useItemOn(player,
					net.minecraft.world.InteractionHand.MAIN_HAND, blockHit);
			if (result == InteractionResult.FAIL) {
				return ToolResult.error("The game rejected the placement (face may be obstructed).");
			}
		}
		if (!placementAttempted || age - placementAge < 4) {
			return null;
		}
		BlockState state = minecraft.level.getBlockState(target);
		placedBlock = BlockUtil.id(state);
		if (placedBlock.equals(expectedBlock)) {
			JsonObject json = new JsonObject();
			json.addProperty("ok", true);
			json.addProperty("message", "Placed " + placedBlock + " at " + target.toShortString() + ".");
			json.addProperty("block", placedBlock);
			json.addProperty("x", target.getX());
			json.addProperty("y", target.getY());
			json.addProperty("z", target.getZ());
			json.addProperty("jumped", jumped);
			return ToolResult.text(json.toString());
		}
		return ToolResult.error("Placement did not stick; target now contains " + placedBlock + ".");
	}

	private AABB targetBox() {
		return new AABB(target.getX(), target.getY(), target.getZ(),
				target.getX() + 1, target.getY() + 1, target.getZ() + 1);
	}

	private Vec3 hitPoint() {
		return Vec3.atCenterOf(target).add(
				supportDirection.getStepX() * 0.5,
				supportDirection.getStepY() * 0.5,
				supportDirection.getStepZ() * 0.5
		);
	}

	private boolean withinReach(LocalPlayer player) {
		double range = player.blockInteractionRange() + 0.8;
		return player.getEyePosition().distanceToSqr(Vec3.atCenterOf(target)) <= range * range;
	}

	@Override
	public void cleanup(Minecraft minecraft) {
		InputController.set(minecraft.options.keyJump, false);
		InputController.releaseMovement(minecraft);
	}

	@Override
	public String name() {
		return "place";
	}
}
