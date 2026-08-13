package com.automine.command;

import com.automine.AutoMineClient;
import com.automine.gui.AutoMineMenuScreen;
import com.automine.mine.QuarryEngine;
import com.automine.mine.QuarryPlan;
import com.automine.mine.Selection;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

/** Client-side slash commands: mark the box with {@code /sel}, then {@code /start}. */
public final class ClientCommands {

	// ---- actions ----

	/**
	 * Đặt góc và hiển thị thông báo giống lệnh /sel
	 * @param which 1 hoặc 2
	 * @param pos vị trí block
	 */
	public static void setCornerAt(int which, BlockPos pos) {
		if (which == 1) {
			AutoMineClient.SELECTION.setPos1(pos);
		} else {
			AutoMineClient.SELECTION.setPos2(pos);
		}
		
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player != null) {
			String msg = "§b[AutoMine] §rđiểm " + which + " = " + pos.getX() + " " + pos.getY() + " " + pos.getZ();
			if (AutoMineClient.SELECTION.isComplete()) {
				msg += " · vùng " + AutoMineClient.SELECTION.describe() + " — gõ /start";
			}
			mc.player.sendMessage(Text.literal(msg), false);
		}
	}

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
			dispatcher.register(ClientCommandManager.literal("status").executes(ctx -> {
				info(engine().statusLine());
				return 1;
			}));

			for (String name : new String[]{"automine", "am"}) {
				dispatcher.register(ClientCommandManager.literal(name)
						.executes(ctx -> {
							openMenu();
							return 1;
						})
						.then(ClientCommandManager.literal("help").executes(ctx -> {
							help();
							return 1;
						}))
						.then(ClientCommandManager.literal("test").executes(ctx -> {
							info("§eTest xẻng vàng:");
							info("  goldenShovelMark = " + AutoMineClient.CONFIG.goldenShovelMark);
							info("  Cầm xẻng vàng và chuột phải vào block để test");
							return 1;
						}))
						.then(ClientCommandManager.literal("set")
								.executes(ctx -> {
									listSettings();
									return 1;
								})
								.then(ClientCommandManager.argument("key", StringArgumentType.word())
										.executes(ctx -> {
											String key = StringArgumentType.getString(ctx, "key");
											String value = AutoMineClient.CONFIG.get(key);
											if (value == null) {
												error("không có cấu hình '" + key + "'");
												return 0;
											}
											info(key + " = " + value);
											return 1;
										})
										.then(ClientCommandManager.argument("value", StringArgumentType.word())
												.executes(ctx -> {
													String key = StringArgumentType.getString(ctx, "key");
													String value = StringArgumentType.getString(ctx, "value");
													if (AutoMineClient.CONFIG.set(key, value)) {
														info(key + " = " + AutoMineClient.CONFIG.get(key));
														return 1;
													}
													error("không đặt được '" + key + "'");
													return 0;
												})))));
			}
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
				+ " · trục " + (plan.travelAxis == net.minecraft.util.math.Direction.Axis.X ? "X" : "Z"));
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

	private static void listSettings() {
		info("cấu hình:");
		for (String key : AutoMineClient.CONFIG.keys()) {
			info("  " + key + " = " + AutoMineClient.CONFIG.get(key));
		}
	}

	private static void help() {
		info("§b§lAutoMine §7— đào rỗng một vùng, theo tầng, từ trên xuống.");
		info("§f/sel 1§7 — đặt điểm 1 tại chỗ đang đứng");
		info("§f/sel 2§7 — đặt điểm 2 (góc đối diện)");
		info("§f/start§7 — bắt đầu đào  ·  §f/stop§7 — dừng");
		info("§f/pause§7 · §f/resume§7 — tạm dừng / chạy tiếp");
		info("§f/status§7 — đang đào tới đâu  ·  §f/sel info§7 — xem vùng chọn");
		info("§f/sel clear§7 — xoá vùng chọn  ·  §f/automine§7 — mở menu");
		info("§f/am set <key> <value>§7 — đổi cấu hình (layerHeight, passWidth, ...)");
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
