package com.autobuilder.build;

import com.autobuilder.schematic.SchematicData;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockView;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns a loaded {@link SchematicData} plus a world-space origin into an
 * ordered queue of {@link PlacementTask}s: already-correct positions are
 * skipped, and everything else is scheduled bottom-up, nearest-to-origin
 * first, deferring any block that doesn't yet have a solid neighbor to place
 * against until one of its neighbors has been scheduled.
 */
public final class BuildPlanner {

	private BuildPlanner() {
	}

	public static Deque<PlacementTask> plan(SchematicData schematic, BlockPos origin, BlockView world) {
		List<BlockPos> candidates = new ArrayList<>(schematic.blockCount());
		for (BlockPos relative : schematic.blocks().keySet()) {
			BlockPos worldPos = origin.add(relative);
			BlockState desired = schematic.blocks().get(relative);
			if (!world.getBlockState(worldPos).equals(desired)) {
				candidates.add(worldPos);
			}
		}

		candidates.sort(Comparator
				.comparingInt(BlockPos::getY)
				.thenComparingDouble(pos -> horizontalDistanceSq(pos, origin)));

		Set<BlockPos> scheduled = new HashSet<>();
		Deque<PlacementTask> result = new ArrayDeque<>(candidates.size());
		List<BlockPos> remaining = candidates;

		while (!remaining.isEmpty()) {
			List<BlockPos> stillRemaining = new ArrayList<>();
			boolean progressMade = false;

			for (BlockPos pos : remaining) {
				Direction support = findSupport(pos, world, scheduled);
				if (support != null) {
					BlockState desired = schematic.blocks().get(pos.subtract(origin));
					result.add(new PlacementTask(pos, desired, support));
					scheduled.add(pos);
					progressMade = true;
				} else {
					stillRemaining.add(pos);
				}
			}

			if (!progressMade) {
				// Nothing left has any support at all (fully floating, detached blocks).
				// Schedule them anyway in their original order; the build engine's
				// retry/stuck handling deals with genuine placement failures at runtime.
				for (BlockPos pos : stillRemaining) {
					BlockState desired = schematic.blocks().get(pos.subtract(origin));
					result.add(new PlacementTask(pos, desired, Direction.DOWN));
				}
				break;
			}

			remaining = stillRemaining;
		}

		return result;
	}

	private static Direction findSupport(BlockPos pos, BlockView world, Set<BlockPos> scheduled) {
		// Prefer placing against the block below first (most stable stance for the player).
		for (Direction dir : PREFERRED_ORDER) {
			BlockPos neighbor = pos.offset(dir);
			if (scheduled.contains(neighbor) || !world.getBlockState(neighbor).isAir()) {
				return dir;
			}
		}
		return null;
	}

	private static double horizontalDistanceSq(BlockPos pos, BlockPos origin) {
		double dx = pos.getX() - origin.getX();
		double dz = pos.getZ() - origin.getZ();
		return dx * dx + dz * dz;
	}

	private static final Direction[] PREFERRED_ORDER = {
			Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP
	};
}
