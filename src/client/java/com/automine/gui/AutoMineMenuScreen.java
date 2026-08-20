package com.automine.gui;

import com.automine.AutoMineClient;
import com.automine.config.AutoMineConfig;
import com.automine.mine.QuarryEngine;
import com.automine.mine.Selection;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/**
 * ClickGUI theo kiểu Dawn Client: các thẻ danh mục nền đen trong mờ, viền xám
 * mảnh, bo góc, tiêu đề có icon nhỏ + chữ trắng; mỗi dòng là text thuần —
 * TẮT là chữ xám, BẬT là chữ trắng trên nền sáng rất nhẹ, hover sáng thêm chút.
 * Không còn tím/neon và không còn vạch accent: bảng màu chỉ đen–xám–trắng.
 * Panel kéo thả bằng tiêu đề, vị trí lưu vào config.
 */
public final class AutoMineMenuScreen extends Screen implements StyledScreen {

	// Bảng màu MONOCHROME theo đúng menu mẫu user đưa 2026-08-18 ("HUD Editor layers"):
	// panel xám đậm trong mờ gần như không viền, dòng bật = pill xám sáng + vạch TRẮNG nhỏ
	// bên trái, không tím — điểm nhấn duy nhất là trắng.
	private static final int PANEL_BG = 0xD91E1E22;
	private static final int PANEL_BORDER = 0x14FFFFFF;
	private static final int PANEL_SHADOW = 0x4D000000;
	private static final int HEADER_TXT = 0xFFFFFFFF;
	private static final int SEPARATOR = 0x1FFFFFFF;
	private static final int TXT = 0xFFC9CBD1;
	private static final int TXT_ON = 0xFFFFFFFF;
	private static final int TXT_DIM = 0xFF8A8D94;
	private static final int ROW_HOVER = 0x17FFFFFF;
	private static final int BAR_TRACK = 0xFF2B2B30;
	private static final int ACCENT = 0xFFFFFFFF;
	private static final int ROW_ON_BG = 0x30FFFFFF;
	private static final int ACCENT_DIM = 0xFFB9BBC2;

	private static final int PANEL_W = 200;
	private static final int HEADER_H = 24;
	private static final int PAD = 8;

	private final Screen parent;
	private final List<Panel> panels = new ArrayList<>();
	private Panel draggingPanel;
	/** Đang nắm kéo thẻ HUD staff (bản xem trước vẽ trong menu). */
	private boolean draggingHud;
	private double hudOffX;
	private double hudOffY;

	public AutoMineMenuScreen(Screen parent) {
		super(Text.literal("AutoMine"));
		this.parent = parent;
	}

	private static Selection sel() {
		return AutoMineClient.SELECTION;
	}

	private static QuarryEngine engine() {
		return AutoMineClient.ENGINE;
	}

	private static AutoMineConfig config() {
		return AutoMineClient.CONFIG;
	}

	@Override
	protected void init() {
		panels.clear();
		buildPanels();
		applySavedPositions();
	}

	private void buildPanels() {
		Panel dig = new Panel("Đào", "⛏");
		dig.add(new ButtonEntry(() -> "Đặt điểm 1 (chỗ đang đứng)", () -> setCorner(1), null));
		dig.add(new ButtonEntry(() -> "Đặt điểm 2 (góc đối diện)", () -> setCorner(2), null));
		dig.add(new ButtonEntry(() -> "Xoá vùng", () -> sel().clear(), null));
		dig.add(new InfoEntry(() -> "Vùng: " + sel().describe()));
		dig.add(new GapEntry());
		dig.add(new ButtonEntry(() -> "▶ Bắt đầu đào", () -> {
			String err = engine().start();
			if (err == null) {
				close();
			}
		}, () -> sel().isComplete()));
		dig.add(new ButtonEntry(() -> "⏸ Tạm dừng", () -> engine().pause(), null));
		dig.add(new ButtonEntry(() -> "▶ Tiếp tục", () -> engine().resume(), null));
		dig.add(new ButtonEntry(() -> "⏹ Dừng", () -> engine().stop(), null));
		dig.add(new GapEntry());
		dig.add(new InfoEntry(() -> engine().statusLine()));
		dig.add(new ProgressEntry());
		panels.add(dig);

		Panel opts = new Panel("Tuỳ chọn", "⚙");
		opts.add(new ToggleEntry("Chạy nhanh", () -> config().allowSprint, v -> config().allowSprint = v));
		opts.add(new ToggleEntry("Xây trụ leo lên", () -> config().allowPlace, v -> config().allowPlace = v));
		opts.add(new ToggleEntry("Hiện khung vùng", () -> config().renderSelection, v -> config().renderSelection = v));
		opts.add(new ToggleEntry("Vét sạch tầng", () -> config().sweepLayer, v -> config().sweepLayer = v));
		panels.add(opts);

		Panel setup = new Panel("Thiết lập", "☰");
		setup.add(new StepperEntry("Cao mỗi tầng", () -> config().layerHeight,
				v -> config().layerHeight = clamp(v, 1, 6), 1));
		setup.add(new StepperEntry("Rộng mặt đào", () -> config().passWidth,
				v -> config().passWidth = clamp(v, 1, 5), 1));
		setup.add(new DoubleStepperEntry("Tầm với (block)", () -> config().reachDistance,
				v -> config().reachDistance = v));
		setup.add(new GapEntry());
		setup.add(new ToggleEntry("Tự động ăn táo vàng", () -> config().autoEat, v -> config().autoEat = v));
		setup.add(new StepperEntry("Ăn khi mất (thanh)", () -> config().autoEatThreshold,
				v -> config().autoEatThreshold = clamp(v, 1, 10), 1));
		setup.add(new GapEntry());
		setup.add(new ToggleEntry("Quăng exp sửa cúp", () -> config().expRepair, v -> config().expRepair = v));
		setup.add(new StepperEntry("Bền cúp còn", () -> config().expRepairThreshold,
				v -> config().expRepairThreshold = clamp(v, 1, 1000), 10));
		setup.add(new ButtonEntry(() -> "Test ném exp (10 bình)", () -> {
			engine().startExpTest();
			close();
		}, null));
		panels.add(setup);

		Panel guard = new Panel("Bảo vệ", "🛡");
		guard.add(new ToggleEntry("Staff List HUD", () -> config().staffHud,
				v -> config().staffHud = v));
		guard.add(new ToggleEntry("Auto Sign khi staff GẦN", () -> config().autoSign,
				v -> config().autoSign = v));
		guard.add(new StepperEntry("Phạm vi staff (block)", () -> config().staffRadius,
				v -> config().staffRadius = clamp(v, 1, 128), 1));
		guard.add(new ButtonEntry(() -> "👥 Danh sách staff (" + config().staffList().size() + ") ▸", () -> {
			if (client != null) {
				client.setScreen(new StaffListScreen(this));
			}
		}, null));
		guard.add(new GapEntry());
		guard.add(new ToggleEntry("Báo Discord nước/lava", () -> config().alertFluid,
				v -> config().alertFluid = v));
		guard.add(new TextEntry("Webhook Discord", () -> config().alertWebhook,
				v -> config().alertWebhook = v));
		guard.add(new TextEntry("Discord ID (ping)", () -> config().alertDiscordId,
				v -> config().alertDiscordId = v));
		guard.add(new GapEntry());
		guard.add(new TextEntry("Chữ bảng (ngăn |)", () -> config().signText,
				v -> config().signText = v.isBlank() ? config().signText : v));
		guard.add(new TextEntry("Tên staff (ngăn ,)", () -> config().staffNames,
				v -> config().staffNames = v.isBlank() ? config().staffNames : v));
		guard.add(new InfoEntry(() -> com.automine.util.StaffGuard.onlineStaff().isEmpty()
				? "Chưa có staff online"
				: "⚠ " + String.join(", ", com.automine.util.StaffGuard.onlineStaff())));
		panels.add(guard);

		Panel music = new Panel("Nhạc", "♪");
		music.add(new ToggleEntry("Spotify HUD", () -> config().spotifyHud, v -> config().spotifyHud = v));
		music.add(new ButtonEntry(() -> "♫ Mở trình nhạc", () -> {
			if (client != null) {
				client.setScreen(new com.automine.spotify.SpotifyScreen(this));
			}
		}, null));
		music.add(new ButtonEntry(() -> "▶ Mở YouTube", () -> {
			if (client != null) {
				client.setScreen(new YoutubeScreen(this));
			}
		}, null));
		music.add(new GapEntry());
		music.add(new InfoEntry(() -> AutoMineClient.SPOTIFY != null
				? AutoMineClient.SPOTIFY.nowTitle() : ""));
		music.add(new InfoEntry(() -> AutoMineClient.SPOTIFY != null
				? AutoMineClient.SPOTIFY.nowArtist() : ""));
		panels.add(music);
	}

	/** Vị trí panel lưu dạng "x,y|x,y|..." trong config; thiếu/hỏng thì xếp lưới. */
	private void applySavedPositions() {
		// Xếp mặc định theo LƯỚI vừa màn hình, không phải một hàng ngang dài: ở
		// GUI scale 2 màn chỉ ~960px mà 5 panel × 208px = 1040 — panel cuối thò
		// hẳn ra ngoài. Hàng sau đặt dưới mép panel CAO NHẤT của hàng trước, và
		// y bắt đầu 44 cho khỏi chui gầm thanh tiêu đề (cao tới y=34).
		int perRow = Math.max(1, (this.width - PAD) / (PANEL_W + PAD));
		int rowY = 44;
		int rowTallest = 0;
		for (int i = 0; i < panels.size(); i++) {
			Panel p = panels.get(i);
			int col = i % perRow;
			if (col == 0 && i > 0) {
				rowY += rowTallest + PAD;
				rowTallest = 0;
			}
			p.x = PAD + col * (PANEL_W + PAD);
			p.y = rowY;
			rowTallest = Math.max(rowTallest, p.height());
		}
		String[] parts = config().guiPanels.split("\\|");
		for (int i = 0; i < panels.size(); i++) {
			Panel p = panels.get(i);
			if (i < parts.length) {
				String[] xy = parts[i].split(",");
				if (xy.length == 2) {
					try {
						p.x = Integer.parseInt(xy[0].trim());
						p.y = Integer.parseInt(xy[1].trim());
					} catch (NumberFormatException ignored) {
					}
				}
			}
			p.x = clamp(p.x, 0, Math.max(0, this.width - 40));
			p.y = clamp(p.y, 0, Math.max(0, this.height - HEADER_H));
		}
	}

	private void savePositions() {
		StringBuilder sb = new StringBuilder();
		for (Panel p : panels) {
			if (sb.length() > 0) {
				sb.append('|');
			}
			sb.append(p.x).append(',').append(p.y);
		}
		config().guiPanels = sb.toString();
		config().save();
	}

	private void setCorner(int which) {
		if (client == null || client.player == null) {
			return;
		}
		if (which == 1) {
			sel().setPos1(client.player.getBlockPos());
		} else {
			sel().setPos2(client.player.getBlockPos());
		}
	}

	private static int clamp(int v, int lo, int hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	@Override
	public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
		// Kính mờ đúng kiểu client hack: GIỮ blur nhưng BỎ lớp phủ tối vanilla —
		// thế giới vẫn sáng rõ sau các panel trong suốt.
		if (client != null && client.world != null) {
			applyBlur(context);
		} else {
			super.renderBackground(context, mouseX, mouseY, delta);
		}
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);

		// Thanh tiêu đề FULL-WIDTH như menu mẫu: tên trái, trạng thái phải.
		// Trạng thái CẮT theo chỗ trống — statusLine lúc đào dài cả trăm ký tự,
		// không cắt là nó đè thẳng lên tên mod bên trái.
		String head = "AutoMine";
		int headEnd = 26 + this.textRenderer.getWidth(head) + 12;
		String stat = Cards.ellipsize(this.textRenderer, engine().statusLine(),
				Math.max(40, this.width - 16 - headEnd));
		Cards.roundedRect(context, 9, 9, this.width - 16, 26, 8, PANEL_SHADOW);
		Cards.roundedRect(context, 8, 8, this.width - 16, 26, 8, PANEL_BG);
		context.fill(16, 17, 20, 25, TXT_ON);
		context.drawTextWithShadow(this.textRenderer, Text.literal(head), 26, 17, TXT_ON);
		context.drawTextWithShadow(this.textRenderer, Text.literal(stat),
				this.width - 16 - this.textRenderer.getWidth(stat), 17, TXT_DIM);

		for (Panel p : panels) {
			p.render(context, this.textRenderer, mouseX, mouseY);
		}

		// Bản xem trước thẻ HUD staff — vẽ trên cùng để nắm kéo đi chỗ khác.
		com.automine.util.StaffGuard.renderPreview(context);
	}

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		if (click.button() != 0) {
			return super.mouseClicked(click, doubled);
		}
		// Thẻ HUD staff nằm trên cùng — nắm là kéo. HUD tắt thì bản xem trước không vẽ,
		// nên vùng nắm cũng tắt theo kẻo nuốt chuột oan.
		int[] hud = com.automine.util.StaffGuard.hudRect(this.client, this.width, true);
		if (config() != null && config().staffHud
				&& click.x() >= hud[0] && click.x() <= hud[0] + hud[2]
				&& click.y() >= hud[1] && click.y() <= hud[1] + hud[3]) {
			draggingHud = true;
			hudOffX = click.x() - hud[0];
			hudOffY = click.y() - hud[1];
			return true;
		}
		// Panel vẽ sau nằm trên -> xét click ngược lại.
		for (int i = panels.size() - 1; i >= 0; i--) {
			Panel p = panels.get(i);
			if (p.inHeader(click.x(), click.y())) {
				draggingPanel = p;
				p.dragOffX = click.x() - p.x;
				p.dragOffY = click.y() - p.y;
				panels.remove(p);
				panels.add(p); // kéo lên trên cùng
				return true;
			}
			if (p.inBody(click.x(), click.y())) {
				p.click(click.x(), click.y());
				return true; // nuốt click kể cả trúng dòng trống, khỏi xuyên panel
			}
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseDragged(Click click, double deltaX, double deltaY) {
		if (draggingHud) {
			com.automine.util.StaffGuard.moveHud((int) (click.x() - hudOffX), (int) (click.y() - hudOffY),
					this.width, this.height);
			return true;
		}
		if (draggingPanel != null) {
			draggingPanel.x = clamp((int) (click.x() - draggingPanel.dragOffX),
					0, Math.max(0, this.width - 40));
			draggingPanel.y = clamp((int) (click.y() - draggingPanel.dragOffY),
					0, Math.max(0, this.height - HEADER_H));
			return true;
		}
		return super.mouseDragged(click, deltaX, deltaY);
	}

	@Override
	public boolean mouseReleased(Click click) {
		if (draggingHud) {
			draggingHud = false;
			com.automine.util.StaffGuard.saveHudPos();
			return true;
		}
		if (draggingPanel != null) {
			draggingPanel = null;
			savePositions();
			return true;
		}
		return super.mouseReleased(click);
	}

	@Override
	public void close() {
		if (client != null && parent != null) {
			client.setScreen(parent);
		} else {
			super.close();
		}
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	// ------------------------------------------------------------------ panels

	private final class Panel {
		final String title;
		/** Ký tự nhỏ trước tiêu đề, đúng kiểu icon danh mục của Dawn. */
		final String icon;
		final List<Entry> entries = new ArrayList<>();
		int x;
		int y;
		double dragOffX;
		double dragOffY;

		Panel(String title, String icon) {
			this.title = title;
			this.icon = icon;
		}

		void add(Entry e) {
			entries.add(e);
		}

		int height() {
			int h = HEADER_H + 3;
			for (Entry e : entries) {
				h += e.height();
			}
			return h + PAD;
		}

		boolean inHeader(double mx, double my) {
			return mx >= x && mx <= x + PANEL_W && my >= y && my <= y + HEADER_H;
		}

		boolean inBody(double mx, double my) {
			return mx >= x && mx <= x + PANEL_W && my > y + HEADER_H && my <= y + height();
		}

		void render(DrawContext ctx, TextRenderer tr, int mouseX, int mouseY) {
			int h = height();
			// Thẻ đen phẳng: bóng đổ mềm -> nền -> viền xám 1px. Không glow, không
			// gradient — đúng chất Dawn, mọi điểm nhấn để dành cho chữ.
			Cards.roundedRect(ctx, x + 1, y + 2, PANEL_W, h, 8, PANEL_SHADOW);
			Cards.roundedRect(ctx, x, y, PANEL_W, h, 8, PANEL_BG);
			Cards.roundedBorder(ctx, x, y, PANEL_W, h, 8, 1, PANEL_BORDER);
			ctx.drawText(tr, Text.literal(icon), x + PAD, y + 8, TXT, false);
			ctx.drawTextWithShadow(tr, Text.literal(title), x + PAD + tr.getWidth(icon) + 6, y + 8, HEADER_TXT);
			ctx.fill(x + PAD, y + HEADER_H - 2, x + PANEL_W - PAD, y + HEADER_H - 1, SEPARATOR);

			int ry = y + HEADER_H + 3;
			for (Entry e : entries) {
				e.render(ctx, tr, x, ry, PANEL_W, mouseX, mouseY);
				ry += e.height();
			}
		}

		void click(double mx, double my) {
			int ry = y + HEADER_H + 3;
			for (Entry e : entries) {
				if (my >= ry && my < ry + e.height()) {
					e.click(mx - x, x, ry);
					return;
				}
				ry += e.height();
			}
		}
	}

	// ------------------------------------------------------------------ rows

	private interface Entry {
		int height();

		void render(DrawContext ctx, TextRenderer tr, int x, int y, int w, int mouseX, int mouseY);

		/** {@code localX} tính từ mép trái panel; x/y là toạ độ tuyệt đối của dòng. */
		default void click(double localX, int x, int y) {
		}
	}

	private static boolean hovered(int x, int y, int w, int h, int mouseX, int mouseY) {
		return mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY < y + h;
	}

	/** Dòng bật/tắt: TẮT = chữ xám, BẬT = chữ trắng trên nền sáng rất nhẹ (kiểu Dawn). */
	private final class ToggleEntry implements Entry {
		final String label;
		final BooleanSupplier get;
		final Consumer<Boolean> set;

		ToggleEntry(String label, BooleanSupplier get, Consumer<Boolean> set) {
			this.label = label;
			this.get = get;
			this.set = set;
		}

		@Override
		public int height() {
			return 16;
		}

		@Override
		public void render(DrawContext ctx, TextRenderer tr, int x, int y, int w, int mouseX, int mouseY) {
			boolean on = get.getAsBoolean();
			boolean hover = hovered(x, y, w, height(), mouseX, mouseY);
			if (on || hover) {
				Cards.roundedRect(ctx, x + 4, y, w - 8, height() - 1, 4, on ? ROW_ON_BG : ROW_HOVER);
			}
			if (on) {
				// Vạch TRẮNG nhỏ bên trái — dấu "đang bật" của menu mẫu.
				Cards.roundedRect(ctx, x + 6, y + 3, 2, height() - 7, 1, TXT_ON);
			}
			// Nhãn bó theo bề rộng dòng — nhãn dài không được lan ra ngoài panel.
			ctx.drawTextWithShadow(tr,
					Text.literal(Cards.ellipsize(tr, label, w - PAD * 2 - (on ? 6 : 2))),
					x + PAD + (on ? 4 : 0), y + 4, on || hover ? TXT_ON : TXT);
		}

		@Override
		public void click(double localX, int x, int y) {
			set.accept(!get.getAsBoolean());
			config().save();
		}
	}

	/** Dòng hành động: bấm là chạy; mờ đi khi chưa đủ điều kiện. */
	private final class ButtonEntry implements Entry {
		final Supplier<String> label;
		final Runnable action;
		final BooleanSupplier enabled;

		ButtonEntry(Supplier<String> label, Runnable action, BooleanSupplier enabled) {
			this.label = label;
			this.action = action;
			this.enabled = enabled;
		}

		boolean isEnabled() {
			return enabled == null || enabled.getAsBoolean();
		}

		@Override
		public int height() {
			return 16;
		}

		@Override
		public void render(DrawContext ctx, TextRenderer tr, int x, int y, int w, int mouseX, int mouseY) {
			boolean hover = isEnabled() && hovered(x, y, w, height(), mouseX, mouseY);
			if (hover) {
				Cards.roundedRect(ctx, x + 4, y, w - 8, height() - 1, 4, ROW_HOVER);
			}
			// Nhãn nút là supplier động ("▶ Bắt đầu (chưa đủ 2 điểm)"...) — bó lại.
			ctx.drawTextWithShadow(tr,
					Text.literal(Cards.ellipsize(tr, label.get(), w - PAD * 2)), x + PAD, y + 4,
					!isEnabled() ? TXT_DIM : hover ? ACCENT : TXT);
		}

		@Override
		public void click(double localX, int x, int y) {
			if (isEnabled()) {
				action.run();
			}
		}
	}

	/** Dòng chỉnh số: label bên trái, [-] số [+] bên phải. */
	private final class StepperEntry implements Entry {
		final String label;
		final IntSupplier get;
		final IntConsumer set;
		final int step;

		StepperEntry(String label, IntSupplier get, IntConsumer set, int step) {
			this.label = label;
			this.get = get;
			this.set = set;
			this.step = step;
		}

		@Override
		public int height() {
			return 16;
		}

		@Override
		public void render(DrawContext ctx, TextRenderer tr, int x, int y, int w, int mouseX, int mouseY) {
			boolean hover = hovered(x, y, w, height(), mouseX, mouseY);
			if (hover) {
				Cards.roundedRect(ctx, x + 4, y, w - 8, height() - 1, 4, ROW_HOVER);
			}
			// Nhãn dừng trước cụm "− N +" (chiếm ~40px sát mép phải), không đè lên.
			ctx.drawTextWithShadow(tr, Text.literal(Cards.ellipsize(tr, label, w - PAD - 52)),
					x + PAD, y + 4, hover ? TXT_ON : TXT);
			String value = String.valueOf(get.getAsInt());
			int valueW = tr.getWidth(value);
			ctx.drawTextWithShadow(tr, Text.literal("−"), x + w - 44, y + 4, ACCENT_DIM);
			ctx.drawTextWithShadow(tr, Text.literal(value), x + w - 26 - valueW / 2, y + 4, ACCENT);
			ctx.drawTextWithShadow(tr, Text.literal("+"), x + w - 12, y + 4, ACCENT_DIM);
		}

		@Override
		public void click(double localX, int x, int y) {
			if (localX >= PANEL_W - 50 && localX <= PANEL_W - 36) {
				set.accept(get.getAsInt() - step);
				config().save();
			} else if (localX >= PANEL_W - 18) {
				set.accept(get.getAsInt() + step);
				config().save();
			}
		}
	}

	/**
	 * Dòng config dạng CHỮ: nhãn trái, giá trị (cắt ngắn) mờ bên phải — bấm là mở
	 * {@link EditTextScreen} để sửa. Ra đời khi cây lệnh /am set bị dẹp: mọi
	 * config phải sửa được ngay trong GUI.
	 */
	private final class TextEntry implements Entry {
		final String label;
		final Supplier<String> get;
		final Consumer<String> set;

		TextEntry(String label, Supplier<String> get, Consumer<String> set) {
			this.label = label;
			this.get = get;
			this.set = set;
		}

		@Override
		public int height() {
			return 16;
		}

		@Override
		public void render(DrawContext ctx, TextRenderer tr, int x, int y, int w, int mouseX, int mouseY) {
			boolean hover = hovered(x, y, w, height(), mouseX, mouseY);
			if (hover) {
				Cards.roundedRect(ctx, x + 4, y, w - 8, height() - 1, 4, ROW_HOVER);
			}
			// Nhãn ưu tiên (bó còn chừa tối thiểu 46px cho giá trị), giá trị ăn
			// đúng phần còn lại — hai bên không bao giờ đè nhau, không tràn panel.
			String lab = Cards.ellipsize(tr, label, w - PAD * 2 - 46);
			ctx.drawTextWithShadow(tr, Text.literal(lab), x + PAD, y + 4, hover ? TXT_ON : TXT);
			String value = get.get();
			int valueMax = Math.max(40, w - PAD * 2 - tr.getWidth(lab) - 6);
			String shown = value == null || value.isBlank() ? "— bấm để điền —"
					: Cards.ellipsize(tr, value, valueMax);
			ctx.drawText(tr, Text.literal(shown),
					x + w - PAD - tr.getWidth(shown), y + 4, TXT_DIM, false);
		}

		@Override
		public void click(double localX, int x, int y) {
			if (client != null) {
				client.setScreen(new EditTextScreen(AutoMineMenuScreen.this, label, get, set));
			}
		}
	}

	/** Dòng chỉnh số LẺ (0.5 mỗi nấc): cho reachDistance. */
	private final class DoubleStepperEntry implements Entry {
		final String label;
		final java.util.function.DoubleSupplier get;
		final java.util.function.DoubleConsumer set;

		DoubleStepperEntry(String label, java.util.function.DoubleSupplier get,
				java.util.function.DoubleConsumer set) {
			this.label = label;
			this.get = get;
			this.set = set;
		}

		@Override
		public int height() {
			return 16;
		}

		@Override
		public void render(DrawContext ctx, TextRenderer tr, int x, int y, int w, int mouseX, int mouseY) {
			boolean hover = hovered(x, y, w, height(), mouseX, mouseY);
			if (hover) {
				Cards.roundedRect(ctx, x + 4, y, w - 8, height() - 1, 4, ROW_HOVER);
			}
			ctx.drawTextWithShadow(tr, Text.literal(Cards.ellipsize(tr, label, w - PAD - 52)),
					x + PAD, y + 4, hover ? TXT_ON : TXT);
			String value = String.format("%.1f", get.getAsDouble());
			int valueW = tr.getWidth(value);
			ctx.drawTextWithShadow(tr, Text.literal("−"), x + w - 44, y + 4, ACCENT_DIM);
			ctx.drawTextWithShadow(tr, Text.literal(value), x + w - 26 - valueW / 2, y + 4, ACCENT);
			ctx.drawTextWithShadow(tr, Text.literal("+"), x + w - 12, y + 4, ACCENT_DIM);
		}

		@Override
		public void click(double localX, int x, int y) {
			if (localX >= PANEL_W - 50 && localX <= PANEL_W - 36) {
				set.accept(Math.max(3.0, get.getAsDouble() - 0.5));
				config().save();
			} else if (localX >= PANEL_W - 18) {
				set.accept(Math.min(6.0, get.getAsDouble() + 0.5));
				config().save();
			}
		}
	}

	/** Dòng thông tin mờ, không bấm được. */
	private final class InfoEntry implements Entry {
		final Supplier<String> text;

		InfoEntry(Supplier<String> text) {
			this.text = text;
		}

		@Override
		public int height() {
			return 12;
		}

		@Override
		public void render(DrawContext ctx, TextRenderer tr, int x, int y, int w, int mouseX, int mouseY) {
			String s = text.get();
			if (s != null && !s.isEmpty()) {
				ctx.drawText(tr, Text.literal(Cards.ellipsize(tr, s, w - PAD * 2)),
						x + PAD, y + 2, TXT_DIM, false);
			}
		}
	}

	/** Khoảng thở giữa các nhóm dòng. */
	private static final class GapEntry implements Entry {
		@Override
		public int height() {
			return 5;
		}

		@Override
		public void render(DrawContext ctx, TextRenderer tr, int x, int y, int w, int mouseX, int mouseY) {
		}
	}

	/** Thanh tiến trình trắng trên rãnh xám, chỉ hiện khi đang đào. */
	private final class ProgressEntry implements Entry {
		@Override
		public int height() {
			return engine().plan() != null && engine().state() == QuarryEngine.State.RUNNING ? 10 : 0;
		}

		@Override
		public void render(DrawContext ctx, TextRenderer tr, int x, int y, int w, int mouseX, int mouseY) {
			if (height() == 0) {
				return;
			}
			int barW = w - PAD * 2;
			int filled = Math.round(barW * engine().plan().progress());
			Cards.roundedRect(ctx, x + PAD, y + 2, barW, 4, 2, BAR_TRACK);
			if (filled > 0) {
				// Thanh tiến trình là chỗ đáng tô nhất: đang chạy thì nó là thứ
				// duy nhất động đậy trong cả menu.
				Cards.roundedRect(ctx, x + PAD, y + 2, filled, 4, 2, ACCENT);
			}
		}
	}
}
