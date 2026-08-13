package com.automine.util;

import net.minecraft.block.BlockState;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;

/** Picks the fastest suitable hotbar tool for a block and selects it like pressing 1-9. */
public final class ToolSelector {
	private ToolSelector() {
	}

	/** @return the hotbar slot now selected. */
	public static int selectBest(ClientPlayerEntity player, BlockState state) {
		PlayerInventory inventory = player.getInventory();
		int bestSlot = inventory.getSelectedSlot();
		float bestSpeed = -1.0F;
		boolean bestSuitable = false;

		for (int slot = 0; slot < PlayerInventory.HOTBAR_SIZE; slot++) {
			ItemStack stack = inventory.getStack(slot);
			float speed = stack.isEmpty() ? 1.0F : stack.getMiningSpeedMultiplier(state);
			boolean suitable = !stack.isEmpty() && stack.isSuitableFor(state);
			// A tool that actually drops the block wins; among equals, the faster one.
			if ((suitable && !bestSuitable) || (suitable == bestSuitable && speed > bestSpeed)) {
				bestSpeed = speed;
				bestSlot = slot;
				bestSuitable = suitable;
			}
		}
		if (bestSlot != inventory.getSelectedSlot()) {
			inventory.setSelectedSlot(bestSlot);
		}
		return bestSlot;
	}
}
