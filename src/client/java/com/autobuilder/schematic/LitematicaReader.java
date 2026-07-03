package com.autobuilder.schematic;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads Litematica's .litematic format: gzipped NBT with one or more named
 * regions, each holding a block-state palette and a contiguous bit-packed
 * index array (see {@link LitematicaBitArray}). Multiple regions are merged
 * into a single {@link SchematicData}, positioned relative to the
 * schematic's own origin (0,0,0) - each region's "Position" is already that
 * offset.
 *
 * <p>Litematica's exact bit-packing and index ordering aren't published by
 * Mojang, so this is reconstructed from how the format is known to behave;
 * it should be smoke-tested against a real .litematic file.
 */
public final class LitematicaReader {

	private LitematicaReader() {
	}

	public static SchematicData read(Path path) throws IOException {
		NbtCompound root = NbtIo.readCompressed(path, NbtSizeTracker.ofUnlimitedBytes());

		NbtCompound regions = root.getCompound("Regions").orElseThrow(() -> new IOException("Missing 'Regions' in .litematic file"));

		Map<BlockPos, BlockState> blocks = new HashMap<>();
		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

		for (String regionName : regions.getKeys()) {
			NbtCompound region = regions.getCompound(regionName).orElseThrow();
			readRegion(region, blocks);
		}

		for (BlockPos pos : blocks.keySet()) {
			minX = Math.min(minX, pos.getX());
			minY = Math.min(minY, pos.getY());
			minZ = Math.min(minZ, pos.getZ());
			maxX = Math.max(maxX, pos.getX());
			maxY = Math.max(maxY, pos.getY());
			maxZ = Math.max(maxZ, pos.getZ());
		}

		Vec3i size = blocks.isEmpty()
				? Vec3i.ZERO
				: new Vec3i(maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1);

		// Normalize so the schematic's minimum corner sits at relative (0,0,0),
		// like the Sponge format already does. This makes the build origin
		// predictable: origin = where this minimum corner goes in the world.
		Map<BlockPos, BlockState> normalized = blocks;
		if (!blocks.isEmpty() && (minX != 0 || minY != 0 || minZ != 0)) {
			normalized = new HashMap<>(blocks.size());
			for (Map.Entry<BlockPos, BlockState> entry : blocks.entrySet()) {
				BlockPos p = entry.getKey();
				normalized.put(new BlockPos(p.getX() - minX, p.getY() - minY, p.getZ() - minZ), entry.getValue());
			}
		}

		String name = root.getCompound("Metadata")
				.flatMap(meta -> meta.getString("Name"))
				.orElse(path.getFileName().toString());

		return new SchematicData(name, size, normalized);
	}

	private static void readRegion(NbtCompound region, Map<BlockPos, BlockState> out) {
		NbtCompound positionTag = region.getCompound("Position").orElseThrow();
		NbtCompound sizeTag = region.getCompound("Size").orElseThrow();

		int posX = positionTag.getInt("x").orElse(0);
		int posY = positionTag.getInt("y").orElse(0);
		int posZ = positionTag.getInt("z").orElse(0);

		int sizeX = sizeTag.getInt("x").orElse(0);
		int sizeY = sizeTag.getInt("y").orElse(0);
		int sizeZ = sizeTag.getInt("z").orElse(0);

		int absX = Math.abs(sizeX);
		int absY = Math.abs(sizeY);
		int absZ = Math.abs(sizeZ);

		// Litematica places container index (x,y,z) at the region's minimum
		// corner + (x,y,z), where the minimum corner accounts for negative
		// sizes: min(pos, pos + size ± 1). Getting this wrong mirrors regions
		// that were selected in a negative direction.
		int minX = Math.min(posX, posX + (sizeX >= 0 ? sizeX - 1 : sizeX + 1));
		int minY = Math.min(posY, posY + (sizeY >= 0 ? sizeY - 1 : sizeY + 1));
		int minZ = Math.min(posZ, posZ + (sizeZ >= 0 ? sizeZ - 1 : sizeZ + 1));

		List<BlockState> palette = readPalette(region.getList("BlockStatePalette").orElseThrow());

		long[] backing = region.getLongArray("BlockStates").orElseThrow();
		int bitsPerEntry = LitematicaBitArray.bitsRequired(palette.size());
		long totalEntries = (long) absX * absY * absZ;
		LitematicaBitArray bitArray = new LitematicaBitArray(bitsPerEntry, totalEntries, backing);

		for (int y = 0; y < absY; y++) {
			for (int z = 0; z < absZ; z++) {
				for (int x = 0; x < absX; x++) {
					long index = (long) (y * absZ + z) * absX + x;
					int paletteIndex = (int) bitArray.getAt(index);
					if (paletteIndex < 0 || paletteIndex >= palette.size()) {
						continue;
					}
					BlockState state = palette.get(paletteIndex);
					if (state.isAir()) {
						continue; // air (whatever its palette index) isn't a block to place
					}

					out.put(new BlockPos(minX + x, minY + y, minZ + z), state);
				}
			}
		}
	}

	private static List<BlockState> readPalette(NbtList paletteList) {
		List<BlockState> palette = new java.util.ArrayList<>(paletteList.size());
		for (int i = 0; i < paletteList.size(); i++) {
			NbtCompound entry = paletteList.getCompound(i).orElseThrow();
			palette.add(parseBlockState(entry));
		}
		return palette;
	}

	private static BlockState parseBlockState(NbtCompound entry) {
		String name = entry.getString("Name").orElse("minecraft:air");
		Block block = Registries.BLOCK.get(Identifier.of(name));
		BlockState state = block.getDefaultState();

		Optional<NbtCompound> properties = entry.getCompound("Properties");
		if (properties.isPresent()) {
			for (String key : properties.get().getKeys()) {
				String value = properties.get().getString(key).orElse(null);
				if (value == null) {
					continue;
				}
				Property<?> property = state.getBlock().getStateManager().getProperty(key);
				if (property != null) {
					state = withProperty(state, property, value);
				}
			}
		}

		return state;
	}

	private static <T extends Comparable<T>> BlockState withProperty(BlockState state, Property<T> property, String value) {
		Optional<T> parsed = property.parse(value);
		return parsed.map(v -> state.with(property, v)).orElse(state);
	}
}
