package com.automine.spotify;

import com.automine.AutoMineClient;
import com.automine.gui.Cards;
import de.labystudio.spotifyapi.SpotifyAPI;
import de.labystudio.spotifyapi.SpotifyAPIFactory;
import de.labystudio.spotifyapi.SpotifyListener;
import de.labystudio.spotifyapi.model.MediaKey;
import de.labystudio.spotifyapi.model.Track;
import java.awt.image.BufferedImage;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

/**
 * Thẻ "đang phát" kiểu Spotify — port từ module 1.21.4 sang 1.21.11 và chạy
 * thuần Fabric (không cần Meteor): nền tối bo góc, viền xanh Spotify, ảnh bìa
 * bo góc, tên bài + ca sĩ, thanh tiến trình có chấm trượt và mốc thời gian.
 *
 * <p>Đường render 1.21.11 khác 1.21.4 ở đúng một chỗ đáng kể:
 * {@code drawTexture} giờ nhận {@code RenderPipelines.GUI_TEXTURED} thay cho
 * {@code RenderLayer::getGuiTextured}, và {@code NativeImageBackedTexture}
 * cần thêm tên. Ảnh bìa được cắt vuông + khoét bo góc bằng alpha như bản gốc.
 */
public final class SpotifyHudOverlay {

	private static final Identifier COVER_ID = Identifier.of("automine", "spotify_cover");

	/** Kích thước thẻ — SpotifyScreen dùng để bắt vùng kéo thả. */
	public static final int CARD_W = 200;
	public static final int CARD_H = 65;

	private volatile String currentTrack = "Chưa phát nhạc";
	private volatile String currentArtist = "Mở Spotify lên nhé";
	private volatile boolean playing;
	private volatile int durationMs;
	private volatile int progressMs;
	private volatile BufferedImage pendingCover;

	private SpotifyAPI api;
	private boolean started;
	private boolean hasCover;
	private long lastRenderTime;
	/** App Spotify desktop đang nối? Khi có, nó là nguồn chính; web chỉ là dự phòng. */
	private volatile boolean connectedDesktop;
	private final WebMediaSession web = new WebMediaSession();
	private volatile String lastWebTitle = "";
	/** Vị trí lần trước web báo — Chrome hay báo đứng im một số, xem sink. */
	private volatile long lastWebPos = Long.MIN_VALUE;

	/** Nối vào Spotify đúng một lần, ở luồng nền để không khựng game. */
	private void ensureStarted() {
		if (started) {
			return;
		}
		started = true;
		Thread thread = new Thread(() -> {
			try {
				api = SpotifyAPIFactory.create();
				api.registerListener(new SpotifyListener() {
					@Override
					public void onConnect() {
						connectedDesktop = true;
					}

					@Override
					public void onTrackChanged(Track track) {
						currentTrack = track.getName();
						currentArtist = track.getArtist();
						durationMs = track.getLength();
						pendingCover = track.getCoverArt();
					}

					@Override
					public void onPositionChanged(int position) {
						progressMs = position;
					}

					@Override
					public void onPlayBackChanged(boolean nowPlaying) {
						playing = nowPlaying;
					}

					@Override
					public void onSync() {
					}

					@Override
					public void onDisconnect(Exception exception) {
						connectedDesktop = false;
						playing = false;
						currentTrack = "Chưa phát nhạc";
						currentArtist = "Mở Spotify lên nhé";
						pendingCover = null;
					}
				});
				api.initialize();
			} catch (Throwable t) {
				currentArtist = "Không nối được Spotify";
			}
		}, "AutoMine-Spotify");
		thread.setDaemon(true);
		thread.start();

		// Nguồn thứ hai: Windows Media Session — bắt được cả Spotify WEB trong
		// trình duyệt (app desktop vẫn được ưu tiên khi nó nối được).
		web.start(new WebMediaSession.Sink() {
			@Override
			public void update(String title, String artist, boolean nowPlaying,
					long posMs, long durMs, String app) {
				if (connectedDesktop) {
					return; // app desktop đang phát — nguồn chính thắng
				}
				boolean newTrack = !title.equals(lastWebTitle);
				if (newTrack) {
					lastWebTitle = title;
					hasCover = false; // web không kèm bìa; đừng treo bìa bài cũ
				}
				currentTrack = title;
				currentArtist = artist.isEmpty() ? "Web / Media Session" : artist;
				playing = nowPlaying;
				// GSMTC của Chrome hay báo pos đứng im (chỉ nhảy khi tua/pause).
				// Ghi đè mỗi giây sẽ kéo thanh tiến trình về chỗ cũ liên tục —
				// chỉ nhận pos khi nó THẬT SỰ đổi, còn lại để nội suy tự trôi.
				if (newTrack || posMs != lastWebPos) {
					lastWebPos = posMs;
					progressMs = (int) Math.max(0, posMs);
				}
				durationMs = (int) Math.max(0, durMs);
			}

			@Override
			public void clear() {
				if (connectedDesktop) {
					return;
				}
				playing = false;
			}
		});
	}

	/** Điều khiển nhạc từ trong game — chạy off-thread cho chắc tay. */
	public void playPause() {
		pressKey(MediaKey.PLAY_PAUSE);
	}

	public void next() {
		pressKey(MediaKey.NEXT);
	}

	public void previous() {
		pressKey(MediaKey.PREV);
	}

	public boolean isPlaying() {
		return playing;
	}

	/** Cho menu ClickGUI hiện "đang phát gì" mà không đụng vào state. */
	public String nowTitle() {
		return currentTrack;
	}

	public String nowArtist() {
		return currentArtist;
	}

	private void pressKey(MediaKey key) {
		Thread thread = new Thread(() -> {
			try {
				// Phím media TOÀN CỤC của Windows: đi qua Media Session nên điều
				// khiển được cả Spotify web trong trình duyệt lẫn app desktop —
				// api.pressMediaKey của thư viện chỉ chọc được app.
				byte vk = switch (key) {
					case PLAY_PAUSE -> (byte) 0xB3;
					case NEXT -> (byte) 0xB0;
					case PREV -> (byte) 0xB1;
				};
				MediaKeys.INSTANCE.keybd_event(vk, (byte) 0, 0, 0);
				MediaKeys.INSTANCE.keybd_event(vk, (byte) 0, 2 /* KEYEVENTF_KEYUP */, 0);
			} catch (Throwable t) {
				SpotifyAPI current = api;
				if (current != null) {
					try {
						current.pressMediaKey(key);
					} catch (Throwable ignored) {
					}
				}
			}
		}, "AutoMine-Spotify-Key");
		thread.setDaemon(true);
		thread.start();
	}

	public void render(DrawContext ctx) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null || mc.options.hudHidden
				|| AutoMineClient.CONFIG == null || !AutoMineClient.CONFIG.spotifyHud) {
			return;
		}
		if (mc.currentScreen instanceof SpotifyScreen) {
			return; // màn hình chỉnh vị trí tự vẽ thẻ, khỏi vẽ chồng
		}
		renderCard(ctx);
	}

	/** Vẽ thẻ tại vị trí đã cấu hình — dùng chung cho HUD lẫn màn hình chỉnh. */
	public void renderCard(DrawContext ctx) {
		MinecraftClient mc = MinecraftClient.getInstance();
		ensureStarted();

		// Nội suy tiến trình giữa hai lần server-Spotify báo vị trí.
		long now = System.currentTimeMillis();
		long delta = lastRenderTime == 0 ? 0 : now - lastRenderTime;
		lastRenderTime = now;
		if (playing && durationMs > 0) {
			progressMs = (int) Math.min(durationMs, progressMs + delta);
		}

		BufferedImage cover = pendingCover;
		if (cover != null) {
			pendingCover = null;
			uploadCover(mc, cover);
		}

		int x = AutoMineClient.CONFIG.spotifyX;
		int y = AutoMineClient.CONFIG.spotifyY;
		int totalWidth = CARD_W;
		int totalHeight = CARD_H;
		int iconSize = 42;
		int pad = 8;
		int textX = x + iconSize + pad * 2;

		Cards.roundedRect(ctx, x, y, totalWidth, totalHeight, 5, Cards.CARD_BG);
		Cards.roundedBorder(ctx, x, y, totalWidth, totalHeight, 5, 1, Cards.ACCENT);

		int iconX = x + pad;
		int iconY = y + (totalHeight - iconSize) / 2;
		if (hasCover) {
			ctx.drawTexture(RenderPipelines.GUI_TEXTURED, COVER_ID,
					iconX, iconY, 0.0F, 0.0F, iconSize, iconSize, iconSize, iconSize);
		} else {
			Cards.roundedRect(ctx, iconX, iconY, iconSize, iconSize, 5, 0xFF2A2A2A);
			drawMusicNote(ctx, iconX + iconSize / 2, iconY + iconSize / 2, Cards.ACCENT);
		}

		int labelY = y + pad;
		ctx.drawText(mc.textRenderer, "SPOTIFY", textX, labelY, Cards.ACCENT, false);

		int maxTextWidth = x + totalWidth - textX - pad;
		int trackY = labelY + 12;
		ctx.drawText(mc.textRenderer,
				Cards.ellipsize(mc.textRenderer, currentTrack, maxTextWidth),
				textX, trackY, Cards.TEXT_MAIN, false);
		ctx.drawText(mc.textRenderer,
				Cards.ellipsize(mc.textRenderer, currentArtist, maxTextWidth),
				textX, trackY + 11, Cards.TEXT_DIM, false);

		// Thanh tiến trình + chấm trượt + mốc thời gian, đúng bố cục bản gốc.
		int barHeight = 4;
		int barY = y + totalHeight - barHeight - 14;
		int barWidth = x + totalWidth - pad - textX;
		Cards.roundedRect(ctx, textX, barY, barWidth, barHeight, barHeight / 2, Cards.TRACK_BG);
		int progressWidth = durationMs > 0
				? Math.min(barWidth, (int) ((long) barWidth * progressMs / durationMs))
				: 0;
		if (progressWidth > 0) {
			Cards.roundedRect(ctx, textX, barY, progressWidth, barHeight,
					Math.min(barHeight / 2, progressWidth / 2), Cards.ACCENT);
		}
		int dotX = Math.max(textX, Math.min(textX + barWidth - barHeight,
				textX + progressWidth - barHeight / 2));
		Cards.roundedRect(ctx, dotX, barY, barHeight, barHeight, barHeight / 2, 0xFFFFFFFF);

		int timeY = barY + barHeight + 2;
		String elapsed = formatTime(durationMs > 0 ? progressMs : 0);
		String total = formatTime(durationMs);
		ctx.drawText(mc.textRenderer, elapsed, textX, timeY, Cards.TEXT_FAINT, false);
		ctx.drawText(mc.textRenderer, total,
				textX + barWidth - mc.textRenderer.getWidth(total), timeY, Cards.TEXT_FAINT, false);
	}

	/** Ảnh bìa: cắt vuông giữa, khoét bo góc bằng alpha, nạp làm texture. */
	private void uploadCover(MinecraftClient mc, BufferedImage img) {
		try {
			int w = img.getWidth();
			int h = img.getHeight();
			int size = Math.min(w, h);
			int radius = size / 8;
			NativeImage image = new NativeImage(size, size, false);
			for (int py = 0; py < size; py++) {
				for (int px = 0; px < size; px++) {
					int argb = img.getRGB(px + (w - size) / 2, py + (h - size) / 2);
					if (outsideCorner(px, py, size, radius)) {
						argb = 0;
					}
					// setColorArgb nhận ARGB thật — bản decompile 1.21.4 tự đảo
					// kênh sang ABGR là sản phẩm của mapping cũ, đừng bắt chước.
					image.setColorArgb(px, py, argb);
				}
			}
			mc.getTextureManager().registerTexture(COVER_ID,
					new NativeImageBackedTexture(() -> "automine_spotify_cover", image));
			hasCover = true;
		} catch (Throwable t) {
			hasCover = false;
		}
	}

	private static boolean outsideCorner(int px, int py, int size, int radius) {
		int cx;
		int cy;
		if (px < radius && py < radius) {
			cx = radius;
			cy = radius;
		} else if (px >= size - radius && py < radius) {
			cx = size - 1 - radius;
			cy = radius;
		} else if (px < radius && py >= size - radius) {
			cx = radius;
			cy = size - 1 - radius;
		} else if (px >= size - radius && py >= size - radius) {
			cx = size - 1 - radius;
			cy = size - 1 - radius;
		} else {
			return false;
		}
		float dx = px - cx;
		float dy = py - cy;
		return Math.sqrt(dx * dx + dy * dy) > radius;
	}

	/** Nốt nhạc vẽ tay cho lúc chưa có ảnh bìa — giữ nguyên từ bản gốc. */
	private static void drawMusicNote(DrawContext ctx, int cx, int cy, int color) {
		int headW = 7;
		int headH = 5;
		int headX = cx - headW / 2 - 2;
		int headY = cy + 5;
		ctx.fill(headX, headY, headX + headW, headY + headH, color);
		int head2X = cx + 2;
		ctx.fill(head2X, headY - 3, head2X + headW, headY - 3 + headH, color);
		int stemW = 2;
		int stemH = 16;
		int stemX = headX + headW - stemW;
		int stemY = headY - stemH + headH / 2;
		ctx.fill(stemX, stemY, stemX + stemW, headY + headH / 2, color);
		int stem2X = head2X + headW - stemW;
		int stem2Y = headY - 3 - stemH + headH / 2;
		ctx.fill(stem2X, stem2Y, stem2X + stemW, headY - 3 + headH / 2, color);
		int beamY = Math.min(stemY, stem2Y);
		ctx.fill(stemX, beamY, stem2X + stemW, beamY + 3, color);
	}

	private static String formatTime(int ms) {
		int seconds = ms / 1000;
		return String.format("%d:%02d", seconds / 60, seconds % 60);
	}

	/**
	 * Binding user32 của riêng mình: bản JNA đóng gói cùng Minecraft không khai
	 * {@code keybd_event} trong {@code User32}, nên tự map — đúng kiểu cách
	 * thư viện spotify-api tự map Kernel32. dwExtraInfo là ULONG_PTR nhưng game
	 * chỉ chạy 64-bit và ta luôn truyền 0, để {@code long} là khớp thanh ghi.
	 */
	private interface MediaKeys extends com.sun.jna.win32.StdCallLibrary {
		MediaKeys INSTANCE = com.sun.jna.Native.load("user32", MediaKeys.class);

		void keybd_event(byte bVk, byte bScan, int dwFlags, long dwExtraInfo);
	}
}
