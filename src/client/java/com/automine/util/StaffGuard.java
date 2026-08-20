package com.automine.util;

import com.automine.AutoMineClient;
import com.automine.config.AutoMineConfig;
import com.automine.gui.Cards;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.AbstractSignEditScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.item.SignItem;
import net.minecraft.network.packet.c2s.play.UpdateSignC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * Bộ ba né-staff dùng chung danh sách tên (đồng bộ với AutoSellVDM / SpawnerProtect / Litematica):
 * Staff List HUD (quét tab-list, staff online là hiện tên), Auto Sign (staff online → dừng máy đào,
 * đứng im, rút bảng đặt xuống tự ghi chữ cấu hình). No-Render đã dời hẳn sang AutoSellVDM + SpawnerProtect.
 */
public final class StaffGuard {

	private StaffGuard() {
	}

	private static final List<String> ONLINE = new ArrayList<>();
	/** Tên hiển thị tab-list (server kèm tag rank màu: SR MOD, ADMIN...). */
	private static final List<net.minecraft.text.Text> ONLINE_DISPLAY = new ArrayList<>();
	/** Staff đang ĐỨNG GẦN (trong bán kính) — chỉ danh sách này mới kích hoạt Auto Sign dừng đào. */
	private static final List<String> NEARBY = new ArrayList<>();
	/** Đã dừng rồi thì staff phải đi xa hơn bán kính + ngần này block mới chạy lại (chống nhấp nháy). */
	private static final float HYSTERESIS = 2.0f;
	private static boolean signDone;
	private static boolean pausedEngine;
	private static BlockPos signPos;
	private static int placeCooldown;

	private static AutoMineConfig config() {
		return AutoMineClient.CONFIG;
	}

	public static List<String> onlineStaff() {
		return ONLINE;
	}

	// (shouldHideItem/No-Render đã XOÁ HẲN khỏi AutoMine 2026-08-18 — tính năng chỉ còn ở
	// AutoSellVDM + SpawnerProtect theo lời chốt; 2 file mixin norender cũng xoá cùng đợt.)

	/**
	 * Nhả pause do CHÍNH StaffGuard đặt. Phải gọi ở MỌI đường thoát — trước đây chỉ set
	 * {@code pausedEngine = false} mà quên {@code ENGINE.resume()}, nên tắt Auto Sign lúc đang bị
	 * dừng là máy đào kẹt PAUSED vĩnh viễn (đúng thứ dòng chat bảo user làm để chạy tiếp).
	 */
	private static void releasePause() {
		if (pausedEngine) {
			AutoMineClient.ENGINE.resume();
			pausedEngine = false;
		}
	}

	public static void tick(MinecraftClient client) {
		AutoMineConfig cfg = config();
		if (client.player == null || client.world == null || client.getNetworkHandler() == null
				|| cfg == null || (!cfg.staffHud && !cfg.autoSign)) {
			ONLINE.clear();
			ONLINE_DISPLAY.clear();
			NEARBY.clear();
			signDone = false;
			// client.player == null (đổi thế giới/thoát) thì engine tự stop, resume() lúc đó vô hại.
			releasePause();
			return;
		}

		ONLINE.clear();
		ONLINE_DISPLAY.clear();
		String[] staff = cfg.staffNames.split(",");
		for (PlayerListEntry entry : client.getNetworkHandler().getPlayerList()) {
			String name = entry.getProfile().name();
			for (String s : staff) {
				if (s.trim().equalsIgnoreCase(name)) {
					ONLINE.add(name);
					ONLINE_DISPLAY.add(entry.getDisplayName() != null
							? entry.getDisplayName() : net.minecraft.text.Text.literal(name));
					break;
				}
			}
		}

		if (!cfg.autoSign) {
			signDone = false;
			NEARBY.clear();
			releasePause(); // tắt Auto Sign = chạy lại NGAY, không để engine kẹt PAUSED
			return;
		}

		// Auto Sign CHỈ kích hoạt khi có staff ĐỨNG GẦN (trong bán kính) — không phải cứ staff online.
		// Có HYSTERESIS: vào trong bán kính mới dừng, nhưng phải đi xa hơn bán kính + BUFFER mới chạy
		// lại — staff đi qua đi lại đúng vạch sẽ không làm dừng/chạy nhấp nháy + spam chat.
		NEARBY.clear();
		int radius = Math.max(1, cfg.staffRadius);
		float limit = pausedEngine ? radius + HYSTERESIS : radius;
		for (AbstractClientPlayerEntity p : client.world.getPlayers()) {
			if (p == client.player) {
				continue;
			}
			String pname = p.getGameProfile().name();
			for (String s : staff) {
				if (s.trim().equalsIgnoreCase(pname) && client.player.distanceTo(p) <= limit) {
					NEARBY.add(pname);
					break;
				}
			}
		}

		if (NEARBY.isEmpty()) {
			// Không staff nào lại gần: máy đào do MÌNH tạm dừng thì tự chạy lại.
			releasePause();
			signDone = false;
			signPos = null;
			return;
		}

		// Có staff đứng gần → tạm dừng đào (đứng im) + đặt bảng đúng một lần.
		if (!pausedEngine) {
			AutoMineClient.ENGINE.pause();
			pausedEngine = true;
			client.player.sendMessage(net.minecraft.text.Text.literal(
					"§e⏸ Staff §f" + String.join(", ", NEARBY) + "§e vào gần (≤" + radius
					+ " block) — Auto Sign TẠM DỪNG đào, đứng im. Tắt §fAuto Sign§e nếu muốn chạy tiếp."),
					false);
			if (client.currentScreen instanceof HandledScreen) {
				client.player.closeHandledScreen();
			}
		}
		if (!signDone) {
			tickSign(client);
		}
	}

	private static void tickSign(MinecraftClient client) {
		// Màn hình sửa bảng mở → điền chữ qua packet rồi đóng.
		if (client.currentScreen instanceof AbstractSignEditScreen) {
			if (signPos != null) {
				String[] lines = signLines(config().signText);
				client.getNetworkHandler().sendPacket(
						new UpdateSignC2SPacket(signPos, true, lines[0], lines[1], lines[2], lines[3]));
			}
			client.setScreen(null);
			signDone = true;
			return;
		}
		if (client.currentScreen != null || client.interactionManager == null) {
			return;
		}
		if (placeCooldown > 0) {
			placeCooldown--;
			return;
		}
		placeCooldown = 10;

		var inv = client.player.getInventory();
		int signSlot = -1;
		for (int i = 0; i < 9; i++) {
			if (inv.getStack(i).getItem() instanceof SignItem) {
				signSlot = i;
				break;
			}
		}
		if (signSlot >= 0) {
			inv.setSelectedSlot(signSlot);
		} else {
			for (int i = 9; i < 36; i++) {
				if (inv.getStack(i).getItem() instanceof SignItem) {
					client.interactionManager.clickSlot(client.player.playerScreenHandler.syncId,
							i, inv.getSelectedSlot(), SlotActionType.SWAP, client.player);
					return; // tick sau chọn lại
				}
			}
			return; // không có bảng trong người — chỉ đứng im
		}
		if (!(client.player.getMainHandStack().getItem() instanceof SignItem)) {
			return;
		}

		BlockPos feet = client.player.getBlockPos();
		Direction[] dirs = { client.player.getHorizontalFacing(),
				client.player.getHorizontalFacing().rotateYClockwise(),
				client.player.getHorizontalFacing().rotateYCounterclockwise(),
				client.player.getHorizontalFacing().getOpposite() };

		for (Direction d : dirs) {
			BlockPos t = feet.offset(d);
			BlockState at = client.world.getBlockState(t);
			BlockState below = client.world.getBlockState(t.down());
			if (at.isReplaceable() && !below.isReplaceable()) {
				BlockHitResult hit = new BlockHitResult(
						Vec3d.ofCenter(t.down()).add(0, 0.5, 0), Direction.UP, t.down(), false);
				signPos = t;
				ActionResult result = client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit);
				if (result instanceof ActionResult.Success) {
					client.player.swingHand(Hand.MAIN_HAND);
				}
				return; // chờ SignEditScreen mở
			}
		}
	}

	private static String[] signLines(String text) {
		String[] out = { "", "", "", "" };
		if (text == null) {
			return out;
		}
		String[] parts = text.split("\\|");
		for (int i = 0; i < 4 && i < parts.length; i++) {
			String s = parts[i].trim();
			out[i] = s.length() > 15 ? s.substring(0, 15) : s;
		}
		return out;
	}

	private static final String HUD_HEAD = "👤 Online staffs :";
	private static final String HUD_HINT = "⇕ kéo thả để di chuyển";

	private static String signLine() {
		return signDone ? "✔ Đã đặt bảng — đứng im" : "⏳ Đang đặt bảng...";
	}

	private static List<net.minecraft.text.Text> hudLines(boolean preview) {
		if (!ONLINE_DISPLAY.isEmpty()) {
			return ONLINE_DISPLAY;
		}
		return preview ? List.of(net.minecraft.text.Text.literal("§2SR MOD §fShowered")) : List.of();
	}

	/** Khung thẻ HUD {x,y,w,h}; vị trí từ config (kéo thả trong menu), -1 = neo góc phải-trên. */
	public static int[] hudRect(MinecraftClient client, int guiWidth, boolean preview) {
		AutoMineConfig cfg = config();
		var tr = client.textRenderer;
		List<net.minecraft.text.Text> lines = hudLines(preview);
		int w = tr.getWidth(HUD_HEAD);
		for (net.minecraft.text.Text t : lines) {
			w = Math.max(w, tr.getWidth(t));
		}
		if (cfg.autoSign) {
			w = Math.max(w, tr.getWidth(signLine()));
		}
		if (preview) {
			w = Math.max(w, tr.getWidth(HUD_HINT));
		}
		w = Math.max(w, tr.getWidth(modLine()));
		int cardW = w + 16;
		int cardH = 16 + lines.size() * 11 + (cfg.autoSign ? 11 : 0) + 11 + (preview ? 11 : 0);
		// Vị trí ưu tiên bản DÙNG CHUNG 4 mod (system property) — kéo ở menu mod nào cũng dời đúng
		// khung đang hiện; chưa có bản chung thì lấy config của mình.
		int posX = sharedPos(HUD_X_KEY) != Integer.MIN_VALUE ? sharedPos(HUD_X_KEY) : cfg.staffHudX;
		int posY = sharedPos(HUD_Y_KEY) != Integer.MIN_VALUE ? sharedPos(HUD_Y_KEY) : cfg.staffHudY;
		int x = posX >= 0 ? Math.min(posX, Math.max(0, guiWidth - cardW)) : guiWidth - cardW - 6;
		int y = Math.max(0, posY >= 0 ? posY : 5);
		return new int[] { x, y, cardW, cardH };
	}

	/** Dòng cuối thẻ: HUD đang chạy từ mod nào — user yêu cầu "thêm chữ ở cuối là mod: ...". */
	private static String modLine() {
		return "mod: " + HUD_MOD_ID;
	}

	private static final String HUD_X_KEY = "vdm.staffhud.x";
	private static final String HUD_Y_KEY = "vdm.staffhud.y";

	private static int sharedPos(String key) {
		try {
			return Integer.parseInt(System.getProperty(key, ""));
		} catch (NumberFormatException e) {
			return Integer.MIN_VALUE;
		}
	}

	public static void moveHud(int x, int y, int guiWidth, int guiHeight) {
		AutoMineConfig cfg = config();
		cfg.staffHudX = Math.max(0, Math.min(x, guiWidth - 40));
		cfg.staffHudY = Math.max(0, Math.min(y, guiHeight - 20));
		// Đẩy lên kênh chung để khung ngoài game (mod nào vẽ cũng vậy) dời theo NGAY.
		System.setProperty(HUD_X_KEY, Integer.toString(cfg.staffHudX));
		System.setProperty(HUD_Y_KEY, Integer.toString(cfg.staffHudY));
	}

	public static void saveHudPos() {
		config().save();
	}

	/** HUD theo mẫu: thẻ bo góc viền xám, "👤 Online staffs :", dòng = tên tab-list (rank màu server). */
	public static void renderHud(DrawContext ctx) {
		MinecraftClient client = MinecraftClient.getInstance();
		AutoMineConfig cfg = config();
		if (client.player == null || cfg == null || !cfg.staffHud || ONLINE_DISPLAY.isEmpty()
				|| client.options.hudHidden) {
			releaseHud();
			return;
		}
		if (!claimHud()) {
			return; // mod khác đang vẽ thẻ này rồi — khỏi chồng thêm một bản
		}
		// Vị trí chung vừa bị kéo (có thể từ menu mod KHÁC) → chép về config của mình để còn giữ
		// đúng chỗ đó qua lần restart sau. Chỉ ghi khi lệch nên mỗi lượt kéo tốn đúng một lần save.
		int shX = sharedPos(HUD_X_KEY);
		int shY = sharedPos(HUD_Y_KEY);
		if (shX != Integer.MIN_VALUE && (shX != cfg.staffHudX || shY != cfg.staffHudY)) {
			cfg.staffHudX = shX;
			cfg.staffHudY = shY;
			cfg.save();
		}
		drawHud(ctx, client, hudRect(client, ctx.getScaledWindowWidth(), false), false);
	}

	/**
	 * Bản xem trước trong menu — kể cả khi chưa có staff online, để nắm kéo đi chỗ khác.
	 * Chỉ vẽ khi Staff HUD đang BẬT: tắt công tắc là thẻ biến mất ngay trong menu luôn.
	 */
	public static void renderPreview(DrawContext ctx) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (config() == null || !config().staffHud) {
			return;
		}
		drawHud(ctx, client, hudRect(client, ctx.getScaledWindowWidth(), true), true);
	}

	// 4 mod (litematica / autosellvdm / spawnerprotect / automine) cùng chạy là 4 thẻ staff y hệt
	// nhau chồng lên nhau — và tắt HUD ở mod này vẫn thấy thẻ của mod kia, trông như "tắt không ăn".
	// Nên các mod chia nhau MỘT suất vẽ qua System property (kênh chung cả JVM): mod nào vẽ trước
	// giữ suất + đóng "nhịp tim"; mod giữ suất mà tắt HUD (hoặc đứng hình >1s) thì nhả cho mod khác.
	private static final String HUD_OWNER_KEY = "vdm.staffhud.owner";
	private static final String HUD_BEAT_KEY = "vdm.staffhud.beat";
	private static final String HUD_MOD_ID = "automine";

	private static boolean claimHud() {
		long now = System.currentTimeMillis();
		String owner = System.getProperty(HUD_OWNER_KEY, "");
		long beat;
		try {
			beat = Long.parseLong(System.getProperty(HUD_BEAT_KEY, "0"));
		} catch (NumberFormatException e) {
			beat = 0L;
		}
		if (!owner.isEmpty() && !owner.equals(HUD_MOD_ID) && now - beat < 1000L) {
			return false;
		}
		System.setProperty(HUD_OWNER_KEY, HUD_MOD_ID);
		System.setProperty(HUD_BEAT_KEY, Long.toString(now));
		return true;
	}

	private static void releaseHud() {
		if (HUD_MOD_ID.equals(System.getProperty(HUD_OWNER_KEY, ""))) {
			System.setProperty(HUD_OWNER_KEY, "");
		}
	}

	private static void drawHud(DrawContext ctx, MinecraftClient client, int[] r, boolean preview) {
		AutoMineConfig cfg = config();
		var tr = client.textRenderer;
		Cards.roundedRect(ctx, r[0], r[1], r[2], r[3], 6, 0xE6141418);
		Cards.roundedBorder(ctx, r[0], r[1], r[2], r[3], 6, 1, 0xFF33353C);
		int x = r[0] + 8;
		int ly = r[1] + 5;
		ctx.drawText(tr, HUD_HEAD, x, ly, 0xFFFFFFFF, true);
		ly += 13;
		for (net.minecraft.text.Text t : hudLines(preview)) {
			ctx.drawText(tr, t, x, ly, 0xFFFFFFFF, true);
			ly += 11;
		}
		if (cfg.autoSign) {
			ctx.drawText(tr, signLine(), x, ly, 0xFF9BA1AB, true);
			ly += 11;
		}
		// Dòng cuối: khung này là của mod nào — tắt/di chuyển thì biết chỉnh ở đâu.
		ctx.drawText(tr, modLine(), x, ly, 0xFF61666E, true);
		ly += 11;
		if (preview) {
			ctx.drawText(tr, HUD_HINT, x, ly, 0xFF61666E, true);
		}
	}
}
