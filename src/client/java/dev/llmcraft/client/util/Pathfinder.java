package dev.llmcraft.client.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * A small A* pathfinder over walkable block positions. It understands one-block step-ups,
 * drops of up to three blocks, and rejects lava/fire/cactus. It deliberately does not mine,
 * open doors, or swim long distances; the agent can combine {@code goto} with other tools.
 */
public final class Pathfinder {
	private static final int MAX_FALL = 3;

	private Pathfinder() {
	}

	public record Result(List<BlockPos> path, String error) {
		public boolean ok() {
			return error == null;
		}
	}

	public static Result find(Level level, BlockPos start, BlockPos goal, int maxNodes, int maxDistance) {
		if (level == null) {
			return new Result(List.of(), "No world is loaded.");
		}
		if (start.distSqr(goal) > (long) maxDistance * maxDistance) {
			return new Result(List.of(), "Target is too far away (limit " + maxDistance + " blocks).");
		}
		BlockPos usableStart = findNearbyStandable(level, start, 1, 2);
		if (usableStart == null) {
			return new Result(List.of(), "The player is not standing somewhere walkable.");
		}
		BlockPos usableGoal = findNearbyStandable(level, goal, 2, 3);
		if (usableGoal == null) {
			return new Result(List.of(), "No walkable position near " + goal.toShortString() + " (x,y,z).");
		}
		if (usableStart.equals(usableGoal)) {
			return new Result(List.of(usableGoal), null);
		}

		Map<Long, Node> best = new HashMap<>();
		PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> {
			int compare = Double.compare(a.f, b.f);
			return compare != 0 ? compare : Double.compare(a.g, b.g);
		});

		Node startNode = new Node(usableStart, 0.0, heuristic(usableStart, usableGoal), null);
		best.put(usableStart.asLong(), startNode);
		open.add(startNode);
		int expanded = 0;

		while (!open.isEmpty()) {
			Node current = open.poll();
			if (current.closed) {
				continue;
			}
			current.closed = true;
			if (++expanded > maxNodes) {
				return new Result(List.of(), "Path search gave up after " + maxNodes + " nodes; try a closer target.");
			}
			if (current.pos.equals(usableGoal)) {
				return new Result(reconstruct(current), null);
			}
			for (Edge edge : edges(level, current.pos)) {
				Node known = best.get(edge.pos.asLong());
				double g = current.g + edge.cost;
				if (known != null && known.g <= g) {
					continue;
				}
				Node next = new Node(edge.pos, g, g + heuristic(edge.pos, usableGoal), current);
				best.put(edge.pos.asLong(), next);
				open.add(next);
			}
		}
		return new Result(List.of(), "No route found to " + usableGoal.toShortString()
				+ ". Try a closer point, or clear a path with mine.");
	}

	private static List<BlockPos> reconstruct(Node end) {
		List<BlockPos> path = new ArrayList<>();
		for (Node node = end; node != null; node = node.parent) {
			path.add(node.pos);
		}
		Collections.reverse(path);
		if (!path.isEmpty()) {
			path.removeFirst();
		}
		return path;
	}

	private static List<Edge> edges(Level level, BlockPos pos) {
		List<Edge> edges = new ArrayList<>(8);
		for (Direction direction : Direction.Plane.HORIZONTAL) {
			BlockPos side = pos.relative(direction);
			if (hazardAt(level, side)) {
				continue;
			}
			if (standable(level, side)) {
				edges.add(new Edge(side, 1.0));
			}
			BlockPos up = side.above();
			// A fence/wall is 1.5 blocks tall: the player cannot jump from the ground
			// onto its top. Only accept step-ups whose support top is within jump height.
			if (canJumpOnto(level, side)
					&& standable(level, up)
					&& BlockUtil.passable(level, pos.above(2))) {
				edges.add(new Edge(up, 1.9));
			}
			BlockPos down = side;
			for (int fall = 1; fall <= MAX_FALL; fall++) {
				down = down.below();
				if (!BlockUtil.passable(level, down)) {
					break;
				}
				if (standable(level, down)) {
					edges.add(new Edge(down, 1.0 + fall * 0.45));
					break;
				}
			}
		}
		return edges;
	}

	/**
	 * True when the player can jump from the current feet level onto the top of
	 * {@code supportBlock}. Fences and walls have a 1.5-block collision top, which is above
	 * vanilla jump height (about 1.25 blocks); treating them as one-block steps produces
	 * routes that fail in game.
	 */
	private static boolean canJumpOnto(Level level, BlockPos supportBlock) {
		if (!level.isLoaded(supportBlock)) {
			return false;
		}
		VoxelShape shape = level.getBlockState(supportBlock)
				.getCollisionShape(level, supportBlock);
		return shape.isEmpty() || shape.max(Direction.Axis.Y) <= 1.05;
	}

	private static boolean standable(Level level, BlockPos pos) {
		if (!level.isLoaded(pos) || !level.isLoaded(pos.above())) {
			return false;
		}
		if (hazardAt(level, pos) || hazardAt(level, pos.above())) {
			return false;
		}
		return BlockUtil.standable(level, pos);
	}

	private static boolean hazardAt(Level level, BlockPos pos) {
		return BlockUtil.isHazard(level, pos);
	}

	public static BlockPos findNearbyStandable(Level level, BlockPos center, int horizontalRadius, int verticalRadius) {
		if (BlockUtil.standable(level, center)) {
			return center;
		}
		for (int dy = 0; dy <= verticalRadius; dy++) {
			for (int dx = -horizontalRadius; dx <= horizontalRadius; dx++) {
				for (int dz = -horizontalRadius; dz <= horizontalRadius; dz++) {
					BlockPos candidate = center.offset(dx, dy, dz);
					if (BlockUtil.standable(level, candidate)) {
						return candidate;
					}
					if (dy > 0) {
						candidate = center.offset(dx, -dy, dz);
						if (BlockUtil.standable(level, candidate)) {
							return candidate;
						}
					}
				}
			}
		}
		return null;
	}

	private static double heuristic(BlockPos a, BlockPos b) {
		double dx = Math.abs(a.getX() - b.getX());
		double dz = Math.abs(a.getZ() - b.getZ());
		double dy = Math.abs(a.getY() - b.getY());
		return dx + dz + dy * 1.6;
	}

	private record Edge(BlockPos pos, double cost) {
	}

	private static final class Node {
		final BlockPos pos;
		final double g;
		final double f;
		final Node parent;
		boolean closed;

		Node(BlockPos pos, double g, double f, Node parent) {
			this.pos = pos;
			this.g = g;
			this.f = f;
			this.parent = parent;
		}
	}
}
