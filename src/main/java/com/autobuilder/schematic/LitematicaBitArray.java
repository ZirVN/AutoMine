package com.autobuilder.schematic;

/**
 * Reads Litematica's block-state index array: a contiguous (non-padded) bit
 * stream packed into {@code long}s, LSB-first. This differs from vanilla's
 * palette storage, which is why it needs its own reader instead of Minecraft's
 * {@code PackedIntegerArray}.
 */
public final class LitematicaBitArray {
	private final long[] backingArray;
	private final int bitsPerEntry;
	private final long maxEntryValue;
	private final long size;

	public LitematicaBitArray(int bitsPerEntry, long size, long[] backingArray) {
		if (bitsPerEntry < 1 || bitsPerEntry > 32) {
			throw new IllegalArgumentException("bitsPerEntry out of range: " + bitsPerEntry);
		}
		this.bitsPerEntry = bitsPerEntry;
		this.size = size;
		this.maxEntryValue = (1L << bitsPerEntry) - 1L;

		long expectedLongs = (size * bitsPerEntry + 63L) / 64L;
		if (backingArray != null) {
			// Litematica uses the stored array as-is; only reject one that is too
			// short to hold every entry (which would read out of bounds).
			if (backingArray.length < expectedLongs) {
				throw new IllegalArgumentException(
						"Backing array too short: need " + expectedLongs + " longs but got " + backingArray.length);
			}
			this.backingArray = backingArray;
		} else {
			this.backingArray = new long[(int) expectedLongs];
		}
	}

	public long getAt(long index) {
		if (index < 0 || index >= size) {
			throw new IndexOutOfBoundsException("index " + index + " out of bounds for size " + size);
		}

		long bitIndex = index * bitsPerEntry;
		int arrayIndex = (int) (bitIndex >> 6);
		int bitOffset = (int) (bitIndex & 0x3F);

		long value;
		if (bitOffset + bitsPerEntry <= 64) {
			value = backingArray[arrayIndex] >>> bitOffset;
		} else {
			int bitsInFirstLong = 64 - bitOffset;
			value = (backingArray[arrayIndex] >>> bitOffset) | (backingArray[arrayIndex + 1] << bitsInFirstLong);
		}

		return value & maxEntryValue;
	}

	public long size() {
		return size;
	}

	public int bitsPerEntry() {
		return bitsPerEntry;
	}

	/** Minimum bits needed to index a palette of the given size (Litematica forces a floor of 2). */
	public static int bitsRequired(int paletteSize) {
		if (paletteSize <= 1) {
			return 2;
		}
		int bits = 64 - Long.numberOfLeadingZeros(paletteSize - 1L);
		return Math.max(bits, 2);
	}
}
