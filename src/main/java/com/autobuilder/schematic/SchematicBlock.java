package com.autobuilder.schematic;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

/** A single non-air block from a schematic, positioned relative to the schematic's own origin (0,0,0). */
public record SchematicBlock(BlockPos relativePos, BlockState state) {
}
