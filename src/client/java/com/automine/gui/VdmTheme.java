package com.automine.gui;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/**
 * Bảng màu + hàm vẽ dùng chung — bản yarn của {@code VdmTheme} bên AutoSellVDM
 * (nguồn chuẩn của cả bộ: AutoSellVDM ↔ Litematica fork ↔ AutoMine). Nền đen,
 * viền xám mảnh, bo góc, chữ TẮT xám — BẬT trắng; không neon, không texture
 * vanilla. Port nguyên xi theo lệnh user "lấy full GUI + font của autosell nhét
 * vào automine" — đổi số nào là lệch tông với hai mod kia.
 *
 * <p>Nút và ô nhập vanilla được vẽ lại trong {@code mixin.PressableWidgetMixin}
 * / {@code mixin.TextFieldWidgetMixin}; chúng chỉ đổi kiểu khi màn hình đang mở
 * là của bộ mod ({@link #isModScreen}), nên GUI vanilla/mod khác giữ nguyên.
 */
public final class VdmTheme {

	/** Lớp phủ tối toàn màn hình sau khi làm mờ thế giới. */
	public static final int SCRIM = 0xD9070809;
	/** Nền thẻ lớn ôm toàn bộ nội dung. */
	public static final int SURFACE = 0xF20E0F12;
	public static final int BORDER = 0xFF26282E;
	public static final int BORDER_LIGHT = 0xFF3A3D45;
	public static final int TITLE = 0xFFFFFFFF;
	public static final int TEXT = 0xFF9BA1AB;
	public static final int TEXT_ON = 0xFFE8EAEE;
	public static final int TEXT_DIM = 0xFF61666E;
	public static final int BTN_BG = 0xFF26262B;
	public static final int BTN_BG_HOVER = 0xFF303036;
	public static final int BTN_BG_OFF = 0xFF1E1E23;
	public static final int FIELD_BG = 0xFF232328;
	/** Nền thẻ nhóm — sáng hơn nền chung vừa đủ để thấy khối. */
	public static final int GROUP_BG = 0x26161A20;
	public static final int SEPARATOR = 0x2EFFFFFF;
	/** Màu nhấn, dùng rất tiết chế: ô đang gõ, nút đang rê, gạch dưới tiêu đề. */
	public static final int ACCENT = 0xFFEDEEF2;
	private static final int ACCENT_FAINT = 0x59FFFFFF;

	/** Nhớ sẵn lớp nào là màn hình của bộ mod, khỏi soi interface mỗi khung hình. */
	private static final java.util.Map<Class<?>, Boolean> MOD_SCREENS = new java.util.WeakHashMap<>();

	private VdmTheme() {
	}

	/**
	 * Màn hình đang mở có thuộc BỘ MOD không — nhận cả màn hình của mod ANH EM
	 * (AutoSellVDM / Litematica fork), soi theo TÊN interface chứ không
	 * {@code instanceof}: các mod vá cùng một lời vẽ và Mixin chỉ cho một
	 * redirect thắng, nên cái thắng phải biết tô cho màn hình của tất cả.
	 */
	public static boolean isModScreen(Object screen) {
		if (screen == null) {
			return false;
		}
		if (screen instanceof StyledScreen) {
			return true;
		}

		Class<?> type = screen.getClass();
		Boolean known = MOD_SCREENS.get(type);
		if (known != null) {
			return known;
		}

		boolean found = false;
		for (Class<?> c = type; c != null && !found; c = c.getSuperclass()) {
			for (Class<?> itf : c.getInterfaces()) {
				String name = itf.getSimpleName();
				if (name.equals("VdmStyledScreen") || name.equals("StyledScreen")) {
					found = true;
					break;
				}
			}
		}
		MOD_SCREENS.put(type, found);
		return found;
	}

	/** Khối chữ nhật bo bốn góc, tô đặc. */
	public static void roundedRect(DrawContext g, int x, int y, int w, int h, int r, int color) {
		if (w <= 0 || h <= 0) {
			return;
		}
		r = Math.min(r, Math.min(w / 2, h / 2));
		if (r <= 0) {
			g.fill(x, y, x + w, y + h, color);
			return;
		}
		if (h - 2 * r > 0) {
			g.fill(x, y + r, x + w, y + h - r, color);
		}
		for (int dy = 0; dy < r; dy++) {
			double cy = (r - 0.5) - dy;
			int inset = (int) Math.round(r - Math.sqrt((double) r * r - cy * cy));
			if (inset < 0) {
				inset = 0;
			}
			g.fill(x + inset, y + dy, x + w - inset, y + dy + 1, color);
			g.fill(x + inset, y + h - 1 - dy, x + w - inset, y + h - dy, color);
		}
	}

	/** Viền mảnh 1px chạy quanh thẻ bo góc. */
	public static void roundedBorder(DrawContext g, int x, int y, int w, int h, int r, int color) {
		if (w <= 0 || h <= 0) {
			return;
		}
		r = Math.min(r, Math.min(w / 2, h / 2));

		g.fill(x + r, y, x + w - r, y + 1, color);
		g.fill(x + r, y + h - 1, x + w - r, y + h, color);
		g.fill(x, y + r, x + 1, y + h - r, color);
		g.fill(x + w - 1, y + r, x + w, y + h - r, color);

		for (int dy = 0; dy < r; dy++) {
			double cy = (r - 0.5) - dy;
			int inset = (int) Math.round(r - Math.sqrt((double) r * r - cy * cy));
			if (inset < 0) {
				inset = 0;
			}
			g.fill(x + inset, y + dy, x + inset + 1, y + dy + 1, color);
			g.fill(x + w - inset - 1, y + dy, x + w - inset, y + dy + 1, color);
			g.fill(x + inset, y + h - 1 - dy, x + inset + 1, y + h - dy, color);
			g.fill(x + w - inset - 1, y + h - 1 - dy, x + w - inset, y + h - dy, color);
		}
	}

	/** Nền chung kiểu ClickGUI thẻ nổi: một lớp phủ RẤT nhẹ, các thẻ tự đứng. */
	public static void backdrop(Screen screen, DrawContext g) {
		g.fill(0, 0, screen.width, screen.height, 0x50060708);
	}

	/** Tiêu đề canh giữa: chữ trắng + gạch dưới có đoạn giữa màu nhấn. */
	public static void title(Screen screen, DrawContext g, TextRenderer font, String text, int y) {
		g.drawCenteredTextWithShadow(font, Text.literal(text), screen.width / 2, y, TITLE);
		int mid = screen.width / 2;
		int half = Math.max(40, font.getWidth(text) / 2 + 20);
		g.fill(24, y + 12, screen.width - 24, y + 13, SEPARATOR);
		g.fill(mid - half, y + 12, mid + half, y + 13, ACCENT_FAINT);
	}

	/** Nút phẳng — dùng bởi mixin, nên mọi nút vanilla trong menu của mod đều ăn kiểu này. */
	public static void button(DrawContext g, TextRenderer font, int x, int y, int w, int h,
			Text label, boolean hovered, boolean active) {
		int bg = !active ? BTN_BG_OFF : hovered ? BTN_BG_HOVER : BTN_BG;
		roundedRect(g, x, y, w, h, 4, bg);
		roundedBorder(g, x, y, w, h, 4, !active ? BORDER : hovered ? ACCENT : BORDER);
		// Nhãn dài hơn nút thì cắt kèm "…" — chữ không bao giờ lòi ra ngoài nút.
		Text drawn = label;
		String raw = label.getString();
		if (font.getWidth(raw) > w - 8) {
			drawn = Text.literal(ellipsize(font, raw, w - 8));
		}
		g.drawCenteredTextWithShadow(font, drawn, x + w / 2, y + (h - 8) / 2,
				active ? TEXT_ON : TEXT_DIM);
	}

	/** Cắt chuỗi kèm "…" khi vượt bề rộng cho phép. */
	public static String ellipsize(TextRenderer font, String text, int maxWidth) {
		if (text == null || font.getWidth(text) <= maxWidth) {
			return text == null ? "" : text;
		}
		String out = text;
		while (!out.isEmpty() && font.getWidth(out + "…") > maxWidth) {
			out = out.substring(0, out.length() - 1);
		}
		return out + "…";
	}

	/** Khung ô nhập — vẽ ĐÚNG ô của widget, thay cho texture khung của vanilla. */
	public static void field(DrawContext g, int x, int y, int w, int h, boolean focused) {
		roundedRect(g, x, y, w, h, 4, FIELD_BG);
		roundedBorder(g, x, y, w, h, 4, focused ? ACCENT : BORDER);
	}

	/** Thẻ nhóm — thẻ nổi: bóng đổ mềm + nền đen trong mờ + viền xám. */
	public static void group(DrawContext g, int x, int y, int w, int h) {
		roundedRect(g, x + 1, y + 2, w, h, 8, 0x59000000);
		roundedRect(g, x, y, w, h, 8, 0xD91E1E22);
		roundedBorder(g, x, y, w, h, 8, 0x14FFFFFF);
	}

	/** Nhãn nhóm nhỏ, chữ xám nhạt — đặt ở mép trên thẻ. */
	public static void groupLabel(DrawContext g, TextRenderer font, String text, int x, int y) {
		g.drawText(font, Text.literal(text), x, y, TEXT_DIM, false);
	}
}
