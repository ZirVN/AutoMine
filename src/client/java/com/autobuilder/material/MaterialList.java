package com.autobuilder.material;

import com.autobuilder.schematic.SchematicData;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Litematica-style material accounting: scans a schematic to work out which
 * items are needed and how many, then compares against the player's
 * inventory to show what's missing.
 */
public final class MaterialList {

	private MaterialList() {
	}

	/** Counts how many of each item the schematic requires (one item per non-air block). */
	public static Map<Item, Integer> countRequired(SchematicData schematic) {
		Map<Item, Integer> counts = new HashMap<>();
		for (BlockState state : schematic.blocks().values()) {
			if (state.isAir()) {
				continue;
			}
			Item item = state.getBlock().asItem();
			if (item == Items.AIR) {
				continue; // block has no obtainable item form (e.g. fire); skip it
			}
			counts.merge(item, 1, Integer::sum);
		}
		return counts;
	}

	/** Total count of an item across the player's 36 main inventory slots. */
	public static int countAvailable(PlayerInventory inventory, Item item) {
		int total = 0;
		for (ItemStack stack : inventory.getMainStacks()) {
			if (!stack.isEmpty() && stack.getItem() == item) {
				total += stack.getCount();
			}
		}
		return total;
	}

	/**
	 * Builds the full material list, sorted with the biggest shortfalls first
	 * so the player sees what they still need at a glance.
	 */
	public static List<MaterialEntry> compute(SchematicData schematic, PlayerInventory inventory) {
		Map<Item, Integer> required = countRequired(schematic);
		List<MaterialEntry> entries = new ArrayList<>(required.size());
		for (Map.Entry<Item, Integer> e : required.entrySet()) {
			int available = inventory == null ? 0 : countAvailable(inventory, e.getKey());
			entries.add(new MaterialEntry(e.getKey(), e.getValue(), available));
		}
		entries.sort(Comparator
				.comparingInt(MaterialEntry::missing).reversed()
				.thenComparing(Comparator.comparingInt(MaterialEntry::required).reversed()));
		return entries;
	}

	public static int totalMissing(List<MaterialEntry> entries) {
		int missing = 0;
		for (MaterialEntry e : entries) {
			missing += e.missing();
		}
		return missing;
	}
}
