package com.automine.gui;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

/**
 * Thẻ giao diện kiểu Spotify: nền tối bo góc + viền màu nhấn. Dùng chung cho
 * HUD nhạc và menu AutoMine để cả mod ăn theo đúng một phong cách.
 */
public final class Cards {

	/** Xanh Spotify — màu nhấn chung của giao diện mới. */
	public static final int ACCENT = 0xFF1DB954;
	/** Nền thẻ tối, hơi trong. */
	public static final int CARD_BG = 0xDD121214;
	/** Nền rãnh (thanh tiến trình chưa chạy tới). */
	public static final int TRACK_BG = 0xFF3A3A3A;
	public static final int TEXT_MAIN = 0xFFFFFFFF;
	public static final int TEXT_DIM = 0xFFAAAAAA;
	public static final int TEXT_FAINT = 0xFF888888;

	private Cards() {
	}

	/** Khối chữ nhật bo bốn góc, tô đặc. */
	public static void roundedRect(DrawContext ctx, int x, int y, int w, int h, int r, int color) {
		if (r > w / 2) {
			r = w / 2;
		}
		if (r > h / 2) {
			r = h / 2;
		}
		if (r <= 0) {
			ctx.fill(x, y, x + w, y + h, color);
			return;
		}
		if (h - 2 * r > 0) {
			ctx.fill(x, y + r, x + w, y + h - r, color);
		}
		for (int dy = 0; dy < r; dy++) {
			double cy = (r - 0.5) - dy;
			double cx = Math.sqrt((double) r * r - cy * cy);
			int inset = (int) Math.round(r - cx);
			if (inset < 0) {
				inset = 0;
			}
			ctx.fill(x + inset, y + dy, x + w - inset, y + dy + 1, color);
			ctx.fill(x + inset, y + h - 1 - dy, x + w - inset, y + h - dy, color);
		}
	}

	/** Viền mảnh chạy quanh thẻ bo góc. */
	public static void roundedBorder(DrawContext ctx, int x, int y, int w, int h, int r, int thick, int color) {
		if (r > w / 2) {
			r = w / 2;
		}
		if (r > h / 2) {
			r = h / 2;
		}
		for (int t = 0; t < thick; t++) {
			ctx.fill(x + r, y + t, x + w - r, y + t + 1, color);
			ctx.fill(x + r, y + h - 1 - t, x + w - r, y + h - t, color);
			ctx.fill(x + t, y + r, x + t + 1, y + h - r, color);
			ctx.fill(x + w - 1 - t, y + r, x + w - t, y + h - r, color);
		}
		for (int dy = 0; dy < r; dy++) {
			double outer = r - Math.sqrt((double) r * r - (double) (r - dy) * (r - dy));
			int outerInset = (int) Math.ceil(outer);
			for (int t = 0; t < thick; t++) {
				double innerR = r - t - 1;
				int innerInset;
				if (innerR <= 0.0) {
					innerInset = 0;
				} else {
					double innerDy = r - dy;
					innerInset = innerDy > innerR
							? (int) Math.ceil((double) r)
							: (int) Math.ceil(r - Math.sqrt(innerR * innerR - innerDy * innerDy));
				}
				int start = Math.min(outerInset, innerInset);
				int end = Math.max(outerInset, innerInset);
				ctx.fill(x + start, y + dy, x + end + 1, y + dy + 1, color);
				ctx.fill(x + w - end - 1, y + dy, x + w - start, y + dy + 1, color);
				ctx.fill(x + start, y + h - 1 - dy, x + end + 1, y + h - dy, color);
				ctx.fill(x + w - end - 1, y + h - 1 - dy, x + w - start, y + h - dy, color);
			}
		}
	}

	/** Cắt chuỗi kèm "..." khi vượt bề rộng cho phép. */
	public static String ellipsize(TextRenderer font, String text, int maxWidth) {
		if (font.getWidth(text) <= maxWidth) {
			return text;
		}
		String out = text;
		while (!out.isEmpty() && font.getWidth(out + "...") > maxWidth) {
			out = out.substring(0, out.length() - 1);
		}
		return out + "...";
	}
}
