package com.autobuilder.schematic;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VarIntReaderTest {

	@Test
	void decodesSingleByteValues() {
		byte[] data = {0x05};
		assertEquals(List.of(5), VarIntReader.readAll(data, 1));
	}

	@Test
	void decodesMultiByteValue() {
		// 300 = 0b1_0010_1100 -> byte0 = 0x2C|0x80=0xAC (continuation), byte1 = 0x02
		byte[] data = {(byte) 0xAC, 0x02};
		assertEquals(List.of(300), VarIntReader.readAll(data, 1));
	}

	@Test
	void decodesSequenceOfMixedLengthValues() {
		byte[] data = {0x05, (byte) 0xAC, 0x02, 0x01};
		assertEquals(List.of(5, 300, 1), VarIntReader.readAll(data, 3));
	}
}
