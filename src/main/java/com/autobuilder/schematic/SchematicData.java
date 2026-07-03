package com.autobuilder.schematic;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Format-agnostic representation of a loaded schematic: every non-air block,
 * keyed by position relative to the schematic's own origin (0,0,0), plus the
 * enclosing size. Produced by {@link SchematicLoader} regardless of whether
 * the source file was Litematica or Sponge format.
 */
public final class SchematicData {
	private final String name;
	private final Vec3i size;
	private final Map<BlockPos, BlockState> blocks;

	public SchematicData(String name, Vec3i size, Map<BlockPos, BlockState> blocks) {
		this.name = name;
		this.size = size;
		this.blocks = Collections.unmodifiableMap(new LinkedHashMap<>(blocks));
	}

	public String name() {
		return name;
	}

	public Vec3i size() {
		return size;
	}

	/** Non-air blocks only, keyed by position relative to the schematic's own origin. */
	public Map<BlockPos, BlockState> blocks() {
		return blocks;
	}

	public int blockCount() {
		return blocks.size();
	}
}
