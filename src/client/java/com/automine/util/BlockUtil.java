package com.automine.util;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;

/** Small stateless helpers for looking at blocks. */
public final class BlockUtil {
	private BlockUtil() {
	}

	public static boolean isLava(BlockState state) {
		return state.getFluidState().isIn(FluidTags.LAVA);
	}

	public static boolean isLava(World world, BlockPos pos) {
		return isLava(world.getBlockState(pos));
	}

	/** True if any of the six neighbours is lava &mdash; mining here would let it in. */
	public static boolean lavaAdjacent(World world, BlockPos pos) {
		for (Direction dir : Direction.values()) {
			if (isLava(world, pos.offset(dir))) {
				return true;
			}
		}
		return false;
	}

	/** Any water here — source, flowing, or inside a waterlogged block. */
	public static boolean isWater(World world, BlockPos pos) {
		return world.getBlockState(pos).getFluidState().isIn(FluidTags.WATER);
	}

	/** Whether the block can actually be broken (not air, not fluid, not bedrock). */
	public static boolean isBreakable(World world, BlockPos pos) {
		BlockState state = world.getBlockState(pos);
		if (state.isAir() || !state.getFluidState().isEmpty()) {
			return false;
		}
		return state.getHardness(world, pos) >= 0.0F;
	}

	/** Whether the player's body can pass through this block. */
	public static boolean passable(World world, BlockPos pos) {
		BlockState state = world.getBlockState(pos);
		return !isLava(state) && state.getCollisionShape(world, pos).isEmpty();
	}

	/** Whether this block gives solid footing to stand on top of. */
	public static boolean walkableOn(World world, BlockPos pos) {
		BlockState state = world.getBlockState(pos);
		if (isLava(state) || !state.getFluidState().isEmpty()) {
			return false;
		}
		return Block.isFaceFullSquare(state.getCollisionShape(world, pos), Direction.UP);
	}

	/** Whether the player standing with feet at {@code pos} fits (body + head clear, floor solid). */
	public static boolean standable(World world, BlockPos pos) {
		return walkableOn(world, pos.down()) && passable(world, pos) && passable(world, pos.up());
	}

	/** Centre point of a block face, used as an aim target. */
	public static Vec3d faceCenter(BlockPos pos, Direction face) {
		return Vec3d.ofCenter(pos).add(
				face.getOffsetX() * 0.5, face.getOffsetY() * 0.5, face.getOffsetZ() * 0.5);
	}

	/**
	 * Pick a face of {@code pos} to aim at: one whose neighbour isn't a solid cube
	 * (so the face is exposed) and that points most directly at the eye.
	 */
	public static Direction exposedFaceToward(BlockView world, BlockPos pos, Vec3d eye) {
		Vec3d center = Vec3d.ofCenter(pos);
		double tx = eye.x - center.x;
		double ty = eye.y - center.y;
		double tz = eye.z - center.z;

		Direction best = null;
		double bestDot = -Double.MAX_VALUE;
		Direction fallback = Direction.UP;
		double fallbackDot = -Double.MAX_VALUE;

		for (Direction dir : Direction.values()) {
			double dot = dir.getOffsetX() * tx + dir.getOffsetY() * ty + dir.getOffsetZ() * tz;
			if (dot > fallbackDot) {
				fallbackDot = dot;
				fallback = dir;
			}
			BlockState neighbor = world.getBlockState(pos.offset(dir));
			if ((neighbor.isAir() || !neighbor.isOpaqueFullCube()) && dot > bestDot) {
				bestDot = dot;
				best = dir;
			}
		}
		return best != null ? best : fallback;
	}
}
