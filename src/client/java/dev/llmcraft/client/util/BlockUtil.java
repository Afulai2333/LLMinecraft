package dev.llmcraft.client.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Set;

/** Block lookups and simple walkability checks. */
public final class BlockUtil {
	private static final Set<String> HAZARDS = Set.of(
			"minecraft:lava", "minecraft:fire", "minecraft:soul_fire", "minecraft:magma_block",
			"minecraft:cactus", "minecraft:powder_snow", "minecraft:sweet_berry_bush",
			"minecraft:campfire", "minecraft:soul_campfire"
	);

	private BlockUtil() {
	}

	public static String id(BlockState state) {
		return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
	}

	public static BlockState state(Level level, BlockPos pos) {
		return level.getBlockState(pos);
	}

	public static boolean isAir(Level level, BlockPos pos) {
		return level.getBlockState(pos).isAir();
	}

	public static boolean isHazard(BlockState state) {
		return HAZARDS.contains(id(state));
	}

	public static boolean isHazard(Level level, BlockPos pos) {
		return isHazard(level.getBlockState(pos));
	}

	public static boolean isWater(Level level, BlockPos pos) {
		var fluid = level.getFluidState(pos);
		if (fluid.isEmpty()) {
			return false;
		}
		return net.minecraft.core.registries.BuiltInRegistries.FLUID
				.getKey(fluid.getType()).getPath().contains("water");
	}

	public static boolean shapeEmpty(Level level, BlockPos pos) {
		VoxelShape shape = level.getBlockState(pos).getCollisionShape(level, pos);
		return shape.isEmpty();
	}

	/** True when the player's feet and head can occupy this block. */
	public static boolean passable(Level level, BlockPos pos) {
		if (!level.isLoaded(pos)) {
			return false;
		}
		BlockState feet = level.getBlockState(pos);
		if (!feet.getCollisionShape(level, pos).isEmpty()) {
			return false;
		}
		BlockPos head = pos.above();
		if (!level.isLoaded(head)) {
			return false;
		}
		BlockState headState = level.getBlockState(head);
		return headState.getCollisionShape(level, head).isEmpty();
	}

	/** True when a player can stand at {@code pos}. */
	public static boolean standable(Level level, BlockPos pos) {
		if (!passable(level, pos)) {
			return false;
		}
		BlockPos below = pos.below();
		if (!level.isLoaded(below)) {
			return false;
		}
		BlockState belowState = level.getBlockState(below);
		if (!belowState.getCollisionShape(level, below).isEmpty()) {
			return true;
		}
		// Swimming / floating in water.
		return isWater(level, pos) || isWater(level, below);
	}

	public static boolean support(Level level, BlockPos pos) {
		BlockPos below = pos.below();
		return level.isLoaded(below) && !level.getBlockState(below).getCollisionShape(level, below).isEmpty();
	}

	/** Preferred direction from a block to the support that should be clicked. */
	public static Direction findSupport(Level level, BlockPos target) {
		Direction[] order = {
				Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.UP
		};
		for (Direction direction : order) {
			BlockPos support = target.relative(direction);
			if (!level.isLoaded(support)) {
				continue;
			}
			BlockState state = level.getBlockState(support);
			if (!state.isAir() && !state.getCollisionShape(level, support).isEmpty()) {
				return direction;
			}
		}
		return null;
	}
}
