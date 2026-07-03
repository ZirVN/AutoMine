package com.autobuilder.build;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;

/**
 * Handles refilling the hotbar from the player's own inventory when the
 * currently-held item doesn't match what's needed next. If the item is
 * already somewhere in the hotbar, this just re-selects it (like pressing a
 * number key). Otherwise it actually opens the inventory screen, performs the
 * same slot-swap click a real player would get from pressing 1-9 while
 * hovering a slot, then closes the screen again.
 */
public final class InventoryManager {

	public enum Result {IN_PROGRESS, DONE, ITEM_NOT_FOUND}

	private static final int OPEN_WAIT_TICKS = 3;
	private static final int SWAP_WAIT_TICKS = 3;

	private enum Phase {NONE, WAIT_OPEN, WAIT_SWAP, CLOSE}

	private final MinecraftClient client;
	private Phase phase = Phase.NONE;
	private int waitTicks;
	private int sourceSlotId;
	private int targetHotbarSlot;

	public InventoryManager(MinecraftClient client) {
		this.client = client;
	}

	public boolean isHolding(Item item) {
		ItemStack held = client.player.getMainHandStack();
		return !held.isEmpty() && held.getItem() == item;
	}

	/** Starts (or immediately resolves) fetching {@code item} into the hotbar. */
	public Result begin(Item item) {
		ClientPlayerEntity player = client.player;
		PlayerInventory inventory = player.getInventory();
		int mainIndex = inventory.getMatchingSlot(item.getRegistryEntry(), ItemStack.EMPTY);

		if (mainIndex < 0) {
			phase = Phase.NONE;
			return Result.ITEM_NOT_FOUND;
		}

		if (mainIndex < PlayerInventory.HOTBAR_SIZE) {
			inventory.setSelectedSlot(mainIndex);
			phase = Phase.NONE;
			return Result.DONE;
		}

		// mainIndex is 9..35, which lines up directly with PlayerScreenHandler's
		// slot ids for the main inventory (INVENTORY_START=9..INVENTORY_END=36).
		this.sourceSlotId = mainIndex;
		this.targetHotbarSlot = inventory.getSwappableHotbarSlot();
		this.phase = Phase.WAIT_OPEN;
		this.waitTicks = 0;
		client.setScreen(new InventoryScreen(player));
		return Result.IN_PROGRESS;
	}

	/** Call every client tick while the previous {@link #begin} call returned IN_PROGRESS. */
	public Result tick() {
		if (phase == Phase.NONE) {
			return Result.DONE;
		}

		ClientPlayerEntity player = client.player;
		waitTicks++;

		switch (phase) {
			case WAIT_OPEN -> {
				if (waitTicks >= OPEN_WAIT_TICKS) {
					client.interactionManager.clickSlot(
							player.playerScreenHandler.syncId, sourceSlotId, targetHotbarSlot, SlotActionType.SWAP, player);
					phase = Phase.WAIT_SWAP;
					waitTicks = 0;
				}
			}
			case WAIT_SWAP -> {
				if (waitTicks >= SWAP_WAIT_TICKS) {
					client.setScreen(null);
					phase = Phase.CLOSE;
					waitTicks = 0;
				}
			}
			case CLOSE -> {
				player.getInventory().setSelectedSlot(targetHotbarSlot);
				phase = Phase.NONE;
				return Result.DONE;
			}
			default -> {
				return Result.DONE;
			}
		}

		return Result.IN_PROGRESS;
	}

	public void cancel() {
		if (phase != Phase.NONE) {
			client.setScreen(null);
			phase = Phase.NONE;
		}
	}
}
