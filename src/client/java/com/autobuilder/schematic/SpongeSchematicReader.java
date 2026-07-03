package com.autobuilder.schematic;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
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
 * Reads Sponge Schematic (.schem) files, versions 2 and 3: dimensions plus a
 * palette (blockstate string -> int id) and a VarInt-encoded per-block array
 * of palette ids, ordered {@code index = x + z*width + y*width*length}.
 */
public final class SpongeSchematicReader {

	private SpongeSchematicReader() {
	}

	public static SchematicData read(Path path) throws IOException {
		NbtCompound root = NbtIo.readCompressed(path, NbtSizeTracker.ofUnlimitedBytes());

		int version = root.getInt("Version").orElse(2);
		NbtCompound blockContainer = version >= 3
				? root.getCompound("Blocks").orElseThrow(() -> new IOException("Missing 'Blocks' in v3 .schem file"))
				: root;

		int width = root.getInt("Width").orElseThrow(() -> new IOException("Missing 'Width' in .schem file"));
		int height = root.getInt("Height").orElseThrow(() -> new IOException("Missing 'Height' in .schem file"));
		int length = root.getInt("Length").orElseThrow(() -> new IOException("Missing 'Length' in .schem file"));

		NbtCompound paletteTag = blockContainer.getCompound("Palette")
				.orElseThrow(() -> new IOException("Missing 'Palette' in .schem file"));
		Map<Integer, BlockState> palette = readPalette(paletteTag);

		byte[] blockData = blockContainer.getByteArray("Data").or(() -> blockContainer.getByteArray("BlockData"))
				.orElseThrow(() -> new IOException("Missing block data in .schem file"));

		int expectedCount = width * height * length;
		List<Integer> indices = VarIntReader.readAll(blockData, expectedCount);

		Map<BlockPos, BlockState> blocks = new HashMap<>();
		for (int i = 0; i < indices.size(); i++) {
			int x = i % width;
			int z = (i / width) % length;
			int y = i / (width * length);

			BlockState state = palette.get(indices.get(i));
			if (state == null || state.isAir()) {
				continue;
			}
			blocks.put(new BlockPos(x, y, z), state);
		}

		String name = path.getFileName().toString();
		return new SchematicData(name, new Vec3i(width, height, length), blocks);
	}

	private static Map<Integer, BlockState> readPalette(NbtCompound paletteTag) {
		Map<Integer, BlockState> result = new HashMap<>();
		for (String blockStateString : paletteTag.getKeys()) {
			int id = paletteTag.getInt(blockStateString).orElse(-1);
			if (id < 0) {
				continue;
			}
			result.put(id, parseBlockStateString(blockStateString));
		}
		return result;
	}

	private static BlockState parseBlockStateString(String blockStateString) {
		int bracket = blockStateString.indexOf('[');
		String name = bracket < 0 ? blockStateString : blockStateString.substring(0, bracket);
		Block block = Registries.BLOCK.get(Identifier.of(name));
		BlockState state = block.getDefaultState();

		if (bracket < 0) {
			return state;
		}

		String propsString = blockStateString.substring(bracket + 1, blockStateString.length() - 1);
		for (String pair : propsString.split(",")) {
			int eq = pair.indexOf('=');
			if (eq < 0) {
				continue;
			}
			String key = pair.substring(0, eq);
			String value = pair.substring(eq + 1);
			Property<?> property = state.getBlock().getStateManager().getProperty(key);
			if (property != null) {
				state = withProperty(state, property, value);
			}
		}

		return state;
	}

	private static <T extends Comparable<T>> BlockState withProperty(BlockState state, Property<T> property, String value) {
		Optional<T> parsed = property.parse(value);
		return parsed.map(v -> state.with(property, v)).orElse(state);
	}
}
