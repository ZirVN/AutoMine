package com.autobuilder.build;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * One block to place, in world-space, plus which existing neighbor to place
 * it against. The neighbor block is at {@code target.offset(neighborDirection)};
 * the face to right-click on that neighbor is {@code neighborDirection.getOpposite()}.
 */
public record PlacementTask(BlockPos target, BlockState desiredState, Direction neighborDirection) {

	public BlockPos neighborPos() {
		return target.offset(neighborDirection);
	}

	public Direction clickFace() {
		return neighborDirection.getOpposite();
	}
}
