package com.autobuilder.schematic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LitematicaBitArrayTest {

	@Test
	void readsValuesPackedWithinASingleLong() {
		// bitsPerEntry=5, entries [1, 31, 16, 0] packed LSB-first into one long:
		// 1 | (31<<5) | (16<<10) | (0<<15) = 17377
		long[] backing = {17377L};
		LitematicaBitArray array = new LitematicaBitArray(5, 4, backing);

		assertEquals(1, array.getAt(0));
		assertEquals(31, array.getAt(1));
		assertEquals(16, array.getAt(2));
		assertEquals(0, array.getAt(3));
	}

	@Test
	void readsValueThatSpansTwoLongs() {
		// bitsPerEntry=5, size=14 -> ceil(70/64)=2 longs.
		// Entry 12 starts at bit 60 and spans into the next long: value 27 (0b11011)
		// contributes bits 60-63 (low nibble 0b1011=11) to long0 and bit 0 (the
		// remaining high bit=1) to long1; all other entries are 0.
		long[] backing = {11L << 60, 1L};
		LitematicaBitArray array = new LitematicaBitArray(5, 14, backing);

		assertEquals(0, array.getAt(11));
		assertEquals(27, array.getAt(12));
		assertEquals(0, array.getAt(13));
	}

	@Test
	void bitsRequiredHasAFloorOfTwo() {
		assertEquals(2, LitematicaBitArray.bitsRequired(1));
		assertEquals(2, LitematicaBitArray.bitsRequired(2));
		assertEquals(2, LitematicaBitArray.bitsRequired(4));
		assertEquals(3, LitematicaBitArray.bitsRequired(5));
		assertEquals(3, LitematicaBitArray.bitsRequired(8));
		assertEquals(4, LitematicaBitArray.bitsRequired(9));
	}
}
