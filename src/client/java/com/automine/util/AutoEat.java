package com.automine.util;

import com.automine.AutoMineClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;

/**
 * Tự động ăn táo vàng khi mất đủ số thanh đói.
 *
 * <p><b>Ăn được là nhờ mixin giữ nút.</b> Vanilla huỷ việc dùng đồ ngay tick sau
 * khi bắt đầu: {@code handleInputEvents} gọi {@code stopUsingItem} ở mọi tick mà
 * nút chuột phải không được giữ. {@code MinecraftClientMixin} đọc
 * {@link #isHoldingUseKey} để coi như nút đang được giữ trong suốt lúc nhai —
 * không có nó, mỗi tick lại {@code interactItem} một lần mà không bao giờ ăn
 * xong một quả (spam packet lên server).
 */
public final class AutoEat {
	/** Chờ sau một lần ăn không thành (server từ chối) trước khi thử lại. */
	private static final int FAIL_RETRY_TICKS = 20;

	/** Mốc đói để tính "đã mất bao nhiêu": lúc reset, hoặc sau mỗi lần ăn. */
	private static int fullHunger = 20;
	private static boolean eating = false;
	private static int previousSlot = -1;
	/** Đói lúc bắt đầu cắn — không tăng sau khi nhai xong nghĩa là bị từ chối. */
	private static int hungerWhenStarted = 20;
	private static int cooldown = 0;

	private AutoEat() {
	}

	/**
	 * Cờ cho {@code MinecraftClientMixin}: giữ nút chuột phải hộ người chơi.
	 * Chỉ bật khi <b>thật sự đang nhai</b> — nhánh {@code doItemUse} trong
	 * {@code handleInputEvents} có điều kiện {@code !isUsingItem}, nên siết như
	 * vậy để giữa hai lần cắn nó không tự cắn thêm quả táo mới.
	 */
	public static boolean isHoldingUseKey(MinecraftClient client) {
		return eating && client.player != null && client.player.isUsingItem();
	}

	/**
	 * Gọi mỗi tick trong lúc engine chạy (hoặc đang tạm dừng vì chính nó).
	 *
	 * @return true khi đang ăn — engine nên đứng yên chờ.
	 */
	public static boolean checkAndEat(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		if (player == null || client.interactionManager == null) {
			return false;
		}
		if (!AutoMineClient.CONFIG.autoEat) {
			if (eating) {
				stopEating(player); // tắt config giữa chừng: trả slot, thôi giữ nút
			}
			return false;
		}

		int hunger = player.getHungerManager().getFoodLevel();

		if (eating) {
			if (player.isUsingItem()) {
				return true; // đang nhai
			}
			// Nhai xong — hoặc chưa từng bắt đầu được (server từ chối thì đói
			// không tăng); trường hợp đó nghỉ một lúc thay vì spam thử lại.
			if (hunger <= hungerWhenStarted) {
				cooldown = FAIL_RETRY_TICKS;
			}
			stopEating(player);
			fullHunger = hunger; // mốc mới: tính "mất bao nhiêu" từ đây
			return false;
		}

		if (cooldown > 0) {
			cooldown--;
			return false;
		}
		if (hunger > fullHunger) {
			fullHunger = hunger; // ăn từ nguồn khác — nâng mốc theo
		}

		int threshold = AutoMineClient.CONFIG.autoEatThreshold * 2; // thanh -> điểm
		if (fullHunger - hunger < threshold) {
			return false;
		}
		int slot = findGoldenApple(player);
		if (slot < 0) {
			return false;
		}
		previousSlot = player.getInventory().getSelectedSlot();
		player.getInventory().setSelectedSlot(slot);
		// interactItem tự setCurrentHand khi thành công — không ép tay ở đây,
		// ép trước rồi bị từ chối là client tưởng đang ăn trong khi server không.
		client.interactionManager.interactItem(player, Hand.MAIN_HAND);
		hungerWhenStarted = hunger;
		eating = true;
		return true;
	}

	/** Trả lại slot đang cầm trước khi ăn và thôi giữ nút. */
	private static void stopEating(ClientPlayerEntity player) {
		if (previousSlot >= 0 && previousSlot < 9) {
			player.getInventory().setSelectedSlot(previousSlot);
		}
		eating = false;
		previousSlot = -1;
	}

	/**
	 * Tìm táo vàng trong hotbar (0-8).
	 *
	 * @return slot index hoặc -1 nếu không tìm thấy
	 */
	private static int findGoldenApple(ClientPlayerEntity player) {
		for (int i = 0; i < 9; i++) {
			ItemStack stack = player.getInventory().getStack(i);
			if (stack.getItem() == Items.GOLDEN_APPLE || stack.getItem() == Items.ENCHANTED_GOLDEN_APPLE) {
				return i;
			}
		}
		return -1;
	}

	public static void reset() {
		eating = false;
		previousSlot = -1;
		fullHunger = 20;
		hungerWhenStarted = 20;
		cooldown = 0;
	}
}
