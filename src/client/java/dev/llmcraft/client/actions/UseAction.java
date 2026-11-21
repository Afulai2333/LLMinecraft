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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/** Right-clicks a block, an entity, or the air with the held item. */
public final class UseAction implements ClientAction {
	private final BlockPos block;
	private final Integer entityId;
	private final String handName;
	private final int holdTicks;
	private int age;
	private String used = "held item";

	public UseAction(BlockPos block, Integer entityId, String handName, int holdTicks) {
		this.block = block;
		this.entityId = entityId;
		this.handName = handName;
		this.holdTicks = Math.max(1, holdTicks);
	}

	@Override
	public ToolResult start(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (player == null || minecraft.level == null || minecraft.gameMode == null) {
			return ToolResult.error("No world is loaded.");
		}
		InteractionHand hand = "offhand".equalsIgnoreCase(handName)
				? InteractionHand.OFF_HAND
				: InteractionHand.MAIN_HAND;

		if (entityId != null) {
			Entity entity = minecraft.level.getEntity(entityId);
			if (entity == null) {
				return ToolResult.error("No loaded entity with id " + entityId + ".");
			}
			if (!player.isWithinEntityInteractionRange(entity, 0.5)) {
				return ToolResult.error("Entity " + entityId + " is out of interaction range.");
			}
			InputController.lookAt(player, entity.getBoundingBox().getCenter());
			EntityHitResult hit = new EntityHitResult(entity, entity.getBoundingBox().getCenter());
			InteractionResult result = minecraft.gameMode.interact(player, entity, hit, hand);
			used = entity.getName().getString();
			if (result == InteractionResult.FAIL) {
				return ToolResult.error("Interaction with " + used + " failed.");
			}
			return null;
		}

		if (block != null) {
			if (!minecraft.level.isLoaded(block)) {
				return ToolResult.error("Block is not loaded.");
			}
			if (player.getEyePosition().distanceToSqr(Vec3.atCenterOf(block))
					> Math.pow(player.blockInteractionRange() + 0.8, 2)) {
				return ToolResult.error("Block is out of interaction range; use goto first.");
			}
			Vec3 center = Vec3.atCenterOf(block);
			Direction face = Direction.getApproximateNearest(player.getEyePosition().subtract(center));
			Vec3 hitVec = center.add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
			InputController.lookAt(player, hitVec);
			BlockHitResult hit = new BlockHitResult(hitVec, face, block, false);
			InteractionResult result = minecraft.gameMode.useItemOn(player, hand, hit);
			used = BlockUtil.id(minecraft.level.getBlockState(block));
			if (result == InteractionResult.FAIL) {
				return ToolResult.error("Using " + used + " failed.");
			}
			return null;
		}

		InputController.set(minecraft.options.keyUse, true);
		return null;
	}

	@Override
	public ToolResult tick(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (entityId != null || block != null) {
			if (++age < 4) {
				return null;
			}
			return result(minecraft, player);
		}
		age++;
		if (age >= holdTicks) {
			InputController.set(minecraft.options.keyUse, false);
			return result(minecraft, player);
		}
		return null;
	}

	private ToolResult result(Minecraft minecraft, LocalPlayer player) {
		JsonObject json = new JsonObject();
		json.addProperty("ok", true);
		json.addProperty("message", "Used " + used + ".");
		json.addProperty("screen", minecraft.gui.screen() == null ? "" : minecraft.gui.screen().getClass().getSimpleName());
		json.addProperty("holding", player == null ? "none" : dev.llmcraft.client.util.ItemUtil.id(player.getMainHandItem()));
		if (block != null && minecraft.level != null) {
			json.addProperty("block", BlockUtil.id(minecraft.level.getBlockState(block)));
		}
		json.add("held", StateSnapshot.item(player.getMainHandItem(), player.getInventory().getSelectedSlot()));
		return ToolResult.text(json.toString());
	}

	@Override
	public void cleanup(Minecraft minecraft) {
		InputController.releaseMovement(minecraft);
	}

	@Override
	public String name() {
		return "use";
	}
}
