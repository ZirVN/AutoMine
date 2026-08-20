package com.automine.command;

import com.automine.AutoMineClient;
import com.automine.gui.AutoMineMenuScreen;
import com.automine.mine.QuarryEngine;
import com.automine.mine.QuarryPlan;
import com.automine.mine.Selection;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

/**
 * Bộ lệnh TỐI THIỂU (user chốt 2026-08-20: "all config là ghi vào trong GUI, dỡ
 * bỏ toàn lệnh /am"): chỉ còn {@code /sel}, {@code /start}, {@code /stop},
 * {@code /pause}, {@code /resume}, và {@code /automine} trần để MỞ MENU — mọi
 * cấu hình giờ chỉnh trong GUI (kể cả các config chữ, qua màn nhập liệu riêng).
 */
public final class ClientCommands {

	public void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			dispatcher.register(ClientCommandManager.literal("sel")
					.executes(ctx -> {
						info(selection().describe());
						return 1;
					})
					.then(ClientCommandManager.literal("1").executes(ctx -> setCorner(1)))
					.then(ClientCommandManager.literal("2").executes(ctx -> setCorner(2)))
					.then(ClientCommandManager.literal("pos1").executes(ctx -> setCorner(1)))
					.then(ClientCommandManager.literal("pos2").executes(ctx -> setCorner(2)))
					.then(ClientCommandManager.literal("clear").executes(ctx -> {
						selection().clear();
						info("đã xoá vùng chọn");
						return 1;
					}))
					.then(ClientCommandManager.literal("info").executes(ctx -> {
						showInfo();
						return 1;
					})));

			dispatcher.register(ClientCommandManager.literal("start").executes(ctx -> start()));
			dispatcher.register(ClientCommandManager.literal("stop").executes(ctx -> {
				engine().stop();
				info("đã dừng");
				return 1;
			}));
			dispatcher.register(ClientCommandManager.literal("pause").executes(ctx -> {
				engine().pause();
				info("tạm dừng — gõ /resume để chạy tiếp");
				return 1;
			}));
			dispatcher.register(ClientCommandManager.literal("resume").executes(ctx -> {
				engine().resume();
				// Only claim success when the engine actually runs — the old
				// unconditional "chạy tiếp" lied after /stop and looked like a hang.
				if (engine().state() == QuarryEngine.State.RUNNING) {
					info("chạy tiếp");
				} else {
					error("không có gì để chạy tiếp — dùng /start");
				}
				return 1;
			}));

			// Lối vào GUI — nơi giờ chứa TOÀN BỘ cấu hình. Không còn subcommand nào.
			dispatcher.register(ClientCommandManager.literal("automine").executes(ctx -> {
				openMenu();
				return 1;
			}));
		});
	}

	// ---- actions ----

	private static int setCorner(int which) {
		ClientPlayerEntity player = MinecraftClient.getInstance().player;
		if (player == null) {
			return 0;
		}
		BlockPos pos = player.getBlockPos();
		if (which == 1) {
			selection().setPos1(pos);
		} else {
			selection().setPos2(pos);
		}
		info("điểm " + which + " = " + pos.getX() + " " + pos.getY() + " " + pos.getZ()
				+ (selection().isComplete() ? " · vùng " + selection().describe() + " — gõ /start" : ""));
		return 1;
	}

	private static int start() {
		String error = engine().start();
		if (error != null) {
			error(error);
			return 0;
		}
		QuarryPlan plan = engine().plan();
		info("bắt đầu đào — " + selection().describe()
				+ " · " + plan.layerCount() + " tầng cao " + AutoMineClient.CONFIG.layerHeight
				+ " · mặt " + AutoMineClient.CONFIG.passWidth + "x" + AutoMineClient.CONFIG.layerHeight
				+ " · trục " + (plan.travelAxis == net.minecraft.util.math.Direction.Axis.X ? "X" : "Z")
				+ " · §7" + AutoMineClient.VERSION);
		return 1;
	}

	private static void showInfo() {
		Selection sel = selection();
		if (!sel.isComplete()) {
			info("vùng chọn: " + sel.describe());
			return;
		}
		info("vùng " + sel.describe());
		info("từ " + sel.minX() + " " + sel.minY() + " " + sel.minZ()
				+ " đến " + sel.maxX() + " " + sel.maxY() + " " + sel.maxZ());
	}

	private static void openMenu() {
		MinecraftClient mc = MinecraftClient.getInstance();
		// Deferred: the chat screen closes right after a command runs.
		mc.execute(() -> mc.setScreen(new AutoMineMenuScreen(null)));
	}

	// ---- helpers ----

	private static Selection selection() {
		return AutoMineClient.SELECTION;
	}

	private static QuarryEngine engine() {
		return AutoMineClient.ENGINE;
	}

	private static void info(String text) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player != null) {
			mc.player.sendMessage(Text.literal("§b[AutoMine] §r" + text), false);
		}
	}

	private static void error(String text) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player != null) {
			mc.player.sendMessage(Text.literal("§c[AutoMine] " + text), false);
		}
	}
}
