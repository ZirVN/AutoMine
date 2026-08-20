package com.automine.mine;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

/** The two marked corners and the inclusive box they span. */
public final class Selection {
	private BlockPos pos1;
	private BlockPos pos2;

	public void setPos1(BlockPos pos) {
		this.pos1 = pos.toImmutable();
	}

	public void setPos2(BlockPos pos) {
		this.pos2 = pos.toImmutable();
	}

	public BlockPos pos1() {
		return pos1;
	}

	public BlockPos pos2() {
		return pos2;
	}

	public void clear() {
		pos1 = null;
		pos2 = null;
	}

	public boolean isComplete() {
		return pos1 != null && pos2 != null;
	}

	public int minX() { return Math.min(pos1.getX(), pos2.getX()); }
	public int minY() { return Math.min(pos1.getY(), pos2.getY()); }
	public int minZ() { return Math.min(pos1.getZ(), pos2.getZ()); }
	public int maxX() { return Math.max(pos1.getX(), pos2.getX()); }
	public int maxY() { return Math.max(pos1.getY(), pos2.getY()); }
	public int maxZ() { return Math.max(pos1.getZ(), pos2.getZ()); }

	public int sizeX() { return maxX() - minX() + 1; }
	public int sizeY() { return maxY() - minY() + 1; }
	public int sizeZ() { return maxZ() - minZ() + 1; }

	public long volume() {
		return (long) sizeX() * sizeY() * sizeZ();
	}

	public boolean contains(BlockPos pos) {
		return contains(pos, 0);
	}

	/** Inside the box stretched by {@code margin} blocks on every axis. */
	public boolean contains(BlockPos pos, int margin) {
		return pos.getX() >= minX() - margin && pos.getX() <= maxX() + margin
				&& pos.getY() >= minY() - margin && pos.getY() <= maxY() + margin
				&& pos.getZ() >= minZ() - margin && pos.getZ() <= maxZ() + margin;
	}

	/** Outer box of the whole selection, for rendering. */
	public Box renderBox() {
		return new Box(minX(), minY(), minZ(), maxX() + 1.0, maxY() + 1.0, maxZ() + 1.0);
	}

	public String describe() {
		if (!isComplete()) {
			return pos1 == null && pos2 == null ? "chưa chọn" : "mới có 1 điểm";
		}
		return sizeX() + "x" + sizeY() + "x" + sizeZ() + " (" + volume() + " block)";
	}
}
