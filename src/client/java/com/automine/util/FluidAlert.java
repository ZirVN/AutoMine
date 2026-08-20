package com.automine.util;

import com.automine.AutoMineClient;
import com.automine.config.AutoMineConfig;
import com.automine.mine.QuarryEngine;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;

/**
 * Dính NƯỚC hay DUNG NHAM giữa lúc máy đang đào → bắn cảnh báo lên Discord
 * webhook, ping thẳng id người chơi (yêu cầu user 2026-08-20: "dính nước hay
 * lava thì gửi thông báo discord về webhook server... id discord người chơi +
 * webhook và phần lưu config như mod autosell").
 *
 * <p>Toàn bộ khuôn gửi — embed, mention, allowed_mentions, kiểm URL, gửi async,
 * báo lỗi về chat — chép theo {@code AutoSellWebhook} bên AutoSellVDM để hai mod
 * cư xử y hệt nhau. Config nằm trong {@code automine.properties}:
 * {@code alertWebhook}, {@code alertDiscordId}, {@code alertFluid}.
 */
public final class FluidAlert {

	private static final HttpClient CLIENT = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.build();

	private static final int COLOR_WATER = 0x3498DB;
	private static final int COLOR_LAVA = 0xED4245;
	/** Hai lần báo cách nhau tối thiểu chừng này — nhấp nhô mép nước không spam. */
	private static final long ALERT_COOLDOWN_MS = 30_000L;

	/** Chốt "đang ngâm trong chất lỏng, đã báo rồi" — ra khỏi mới được báo lần sau. */
	private static boolean inFluid;
	private static long lastAlertMs;

	private FluidAlert() {
	}

	/** Gọi mỗi tick từ client — tự kiểm trạng thái máy đào và config. */
	public static void tick(MinecraftClient client) {
		AutoMineConfig config = AutoMineClient.CONFIG;
		QuarryEngine engine = AutoMineClient.ENGINE;
		ClientPlayerEntity player = client.player;

		if (config == null || engine == null || player == null || !config.alertFluid) {
			inFluid = false;
			return;
		}

		boolean lava = player.isInLava();
		boolean water = !lava && player.isTouchingWater();

		if (!lava && !water) {
			inFluid = false; // đã thoát ra — lần dính sau lại được báo/dừng
			return;
		}
		// Máy không chạy thì giữ nguyên chốt: /start lại trong lúc người còn ướt
		// (vừa bị dừng xong, đang bơi ra) không bị dừng oan phát nữa.
		if (engine.state() != QuarryEngine.State.RUNNING || inFluid) {
			return;
		}
		inFluid = true;

		// BÁO TRƯỚC, DỪNG SAU (lệnh user: "thông báo xong /stop là dừng lại việc
		// đào"): embed dựng xong mới stop nên dòng trạng thái trong embed vẫn là
		// cảnh đang đào lúc dính. Dừng luôn cả khi chưa điền webhook — an toàn đồ
		// đạc đứng trên thông báo.
		long now = System.currentTimeMillis();
		if (now - lastAlertMs >= ALERT_COOLDOWN_MS) {
			lastAlertMs = now;
			send(client, player, lava);
		}
		engine.stop();
		msg("§c" + (lava ? "DÍNH DUNG NHAM" : "dính nước")
				+ " — đã tự dừng đào (/start để chạy lại).");
	}

	private static void send(MinecraftClient client, ClientPlayerEntity player, boolean lava) {
		AutoMineConfig config = AutoMineClient.CONFIG;
		String url = config.alertWebhook;

		if (url == null || url.isBlank()) {
			msg("§edính " + (lava ? "dung nham" : "nước")
					+ " nhưng chưa điền webhook — điền Webhook trong menu (tab Bảo vệ)");
			return;
		}
		if (!isValidWebhookUrl(url)) {
			msg("§cWebhook URL sai — phải là https://discord.com/api/webhooks/...");
			return;
		}

		String name = escapeJson(player.getGameProfile().name());
		String server = escapeJson(client.getCurrentServerEntry() != null
				? client.getCurrentServerEntry().address : "singleplayer");
		String pos = player.getBlockX() + " " + player.getBlockY() + " " + player.getBlockZ();
		String status = escapeJson(AutoMineClient.ENGINE.statusLine());
		String title = lava ? "🔥  Dính DUNG NHAM khi đang đào!" : "🌊  Dính nước khi đang đào";
		int color = lava ? COLOR_LAVA : COLOR_WATER;

		StringBuilder json = new StringBuilder("{");
		json.append("\"username\":\"AutoMine\",");
		json.append("\"content\":\"").append(mention(config.alertDiscordId)).append("\",");
		json.append("\"allowed_mentions\":{\"parse\":[\"users\"]},");
		json.append("\"embeds\":[{\"title\":\"").append(escapeJson(title)).append("\",")
				.append("\"color\":").append(color).append(",")
				.append("\"description\":\"Máy đào vừa chạm ")
				.append(lava ? "**dung nham** — vào kiểm tra NGAY kẻo cháy đồ." : "**nước** — nên vào kiểm tra.")
				.append("\",")
				.append("\"thumbnail\":{\"url\":\"https://mc-heads.net/avatar/").append(name).append("/100.png\"},")
				.append("\"fields\":[")
				.append("{\"name\":\"👤  Người chơi\",\"value\":\"`").append(name).append("`\",\"inline\":true},")
				.append("{\"name\":\"🌐  Máy chủ\",\"value\":\"`").append(server).append("`\",\"inline\":true},")
				.append("{\"name\":\"📍  Toạ độ\",\"value\":\"`").append(pos).append("`\",\"inline\":true},")
				.append("{\"name\":\"⛏  Trạng thái\",\"value\":\"`").append(status).append("`\",\"inline\":false}")
				.append("],\"footer\":{\"text\":\"AutoMine\",")
				.append("\"icon_url\":\"https://mc-heads.net/avatar/").append(name).append("/32.png\"},")
				.append("\"timestamp\":\"").append(Instant.now()).append("\"}]}");

		sendAsync(url, json.toString());
	}

	/** {@code <@id>} cho id đã cấu hình — nhận cả id thô lẫn mention dán nguyên. */
	private static String mention(String id) {
		if (id == null) {
			return "";
		}
		String digits = id.replaceAll("[^0-9]", "");
		return digits.isEmpty() ? "" : "<@" + digits + ">";
	}

	private static boolean isValidWebhookUrl(String url) {
		String lower = url.trim().toLowerCase();
		return (lower.startsWith("https://discord.com/api/webhooks/")
				|| lower.startsWith("https://discordapp.com/api/webhooks/")
				|| lower.startsWith("https://ptb.discord.com/api/webhooks/")
				|| lower.startsWith("https://canary.discord.com/api/webhooks/"))
				&& lower.length() > 40;
	}

	private static void sendAsync(String url, String json) {
		try {
			CLIENT.sendAsync(
					HttpRequest.newBuilder()
							.uri(URI.create(url.trim()))
							.timeout(Duration.ofSeconds(15))
							.header("Content-Type", "application/json")
							.header("User-Agent", "AutoMine/1.0")
							.POST(HttpRequest.BodyPublishers.ofString(json))
							.build(),
					HttpResponse.BodyHandlers.ofString()
			).thenAccept(response -> {
				int code = response.statusCode();
				if (code == 204 || code == 200) {
					msg("§ađã báo Discord về vụ dính chất lỏng.");
				} else if (code == 401 || code == 403 || code == 404) {
					msg("§cWebhook không tồn tại hoặc đã bị xoá (HTTP " + code + ").");
				} else {
					msg("§cDiscord trả lỗi HTTP " + code + ".");
				}
			}).exceptionally(t -> {
				msg("§ckhông gửi được webhook: " + t.getMessage());
				return null;
			});
		} catch (Exception e) {
			msg("§cWebhook URL không hợp lệ: " + e.getMessage());
		}
	}

	/** Chat feedback — đẩy về client thread vì callback HTTP chạy async. */
	private static void msg(String text) {
		MinecraftClient mc = MinecraftClient.getInstance();
		mc.execute(() -> {
			if (mc.player != null) {
				mc.player.sendMessage(Text.literal("§b[AutoMine] §r" + text), false);
			}
		});
	}

	private static String escapeJson(String input) {
		return input == null ? "" : input.replace("\\", "\\\\").replace("\"", "\\\"")
				.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
	}
}
