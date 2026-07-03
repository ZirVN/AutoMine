package com.autobuilder.schematic;

import java.util.ArrayList;
import java.util.List;

/**
 * Decodes the VarInt-encoded block data arrays used by Sponge Schematic
 * (.schem) files: standard protobuf-style 7-bits-per-byte, high bit = more
 * bytes follow.
 */
public final class VarIntReader {
	private VarIntReader() {
	}

	public static List<Integer> readAll(byte[] data, int expectedCount) {
		List<Integer> values = new ArrayList<>(expectedCount);
		int index = 0;
		while (index < data.length && values.size() < expectedCount) {
			int value = 0;
			int shift = 0;
			byte b;
			do {
				b = data[index++];
				value |= (b & 0x7F) << shift;
				shift += 7;
			} while ((b & 0x80) != 0);
			values.add(value);
		}
		return values;
	}
}
