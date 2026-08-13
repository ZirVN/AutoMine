package com.automine.mine;

import com.automine.util.BlockUtil;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds a route the player can simply <em>walk</em> — around corners, up a step,
 * down a drop — through space that is already open. Nothing here breaks or
 * places blocks: if a walkable route exists, following it is always tidier than
 * boring a fresh tunnel, which is what the old straight-line mover did.
 *
 * <p>Breadth-first, so the route returned is the one with the fewest steps.
 * Callers fall back to digging only when this returns null.
 */
public final class LocalPath {

	private static final Direction[] HORIZONTAL = {
			Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
	};
	private static final int MAX_NODES = 3000;

	private LocalPath() {
	}

	/**
	 * @return the positions to walk through (excluding the start, ending at
	 *         {@code goal}), or null when no open route exists.
	 */
	public static List<BlockPos> find(World world, BlockPos start, BlockPos goal, int maxFall) {
		if (start.equals(goal)) {
			return List.of();
		}
		if (!BlockUtil.standable(world, goal)) {
			return null; // the destination itself isn't somewhere we can stand
		}

		Map<BlockPos, BlockPos> cameFrom = new HashMap<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		queue.add(start);
		cameFrom.put(start, null);

		int expanded = 0;
		while (!queue.isEmpty() && expanded++ < MAX_NODES) {
			BlockPos current = queue.poll();
			if (current.equals(goal)) {
				return rebuild(cameFrom, current);
			}
			for (BlockPos next : neighbors(world, current, maxFall)) {
				if (!cameFrom.containsKey(next)) {
					cameFrom.put(next, current);
					queue.add(next);
				}
			}
		}
		return null;
	}

	private static List<BlockPos> neighbors(World world, BlockPos from, int maxFall) {
		List<BlockPos> out = new ArrayList<>(8);
		for (Direction dir : HORIZONTAL) {
			BlockPos side = from.offset(dir);

			// Straight ahead, same height.
			if (BlockUtil.standable(world, side)) {
				out.add(side);
				continue;
			}

			// Step up one, if there's headroom both here and there.
			BlockPos up = side.up();
			if (BlockUtil.passable(world, from.up().up())
					&& BlockUtil.standable(world, up)) {
				out.add(up);
				continue;
			}

			// Drop down, as long as the landing is within the safe fall height.
			if (BlockUtil.passable(world, side) && BlockUtil.passable(world, side.up())) {
				BlockPos landing = side;
				for (int dropped = 0; dropped < maxFall; dropped++) {
					BlockPos below = landing.down();
					if (BlockUtil.walkableOn(world, below)) {
						break;
					}
					if (!BlockUtil.passable(world, below)) {
						landing = null;
						break;
					}
					landing = below;
				}
				if (landing != null && !landing.equals(side) && BlockUtil.standable(world, landing)) {
					out.add(landing);
				}
			}
		}
		return out;
	}

	private static List<BlockPos> rebuild(Map<BlockPos, BlockPos> cameFrom, BlockPos end) {
		List<BlockPos> path = new ArrayList<>();
		for (BlockPos node = end; node != null && cameFrom.get(node) != null; node = cameFrom.get(node)) {
			path.add(node);
		}
		Collections.reverse(path);
		return path;
	}
}
