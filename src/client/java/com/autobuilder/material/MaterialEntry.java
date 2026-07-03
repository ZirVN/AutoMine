package com.autobuilder.material;

import net.minecraft.item.Item;

/** One material row: an item, how many the schematic needs, and how many the player currently has. */
public record MaterialEntry(Item item, int required, int available) {

	public int missing() {
		return Math.max(0, required - available);
	}

	public boolean hasEnough() {
		return available >= required;
	}
}
