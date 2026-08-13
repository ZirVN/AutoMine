package com.automine.gui;

import com.automine.AutoMineClient;
import com.automine.config.AutoMineConfig;
import com.automine.mine.QuarryEngine;
import com.automine.mine.Selection;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/**
 * Control panel. Each section carries a one-line description, so the menu also
 * serves as the guide: mark two corners, press start, and watch it clear the box
 * layer by layer from the top down.
 */
public final class AutoMineMenuScreen extends Screen {
	private static final int PANEL_W = 320;
	private static final int LABEL = 0xFFFFE070;
	private static final int DESC = 0xFFA0A0A0;
	private static final int STATUS = 0xFF55FF55;

	private final Screen parent;

	public AutoMineMenuScreen(Screen parent) {
		super(Text.literal("AutoMine"));
		this.parent = parent;
	}

	@Override
	public void close() {
		if (client != null && parent != null) {
			client.setScreen(parent);
		} else {
			super.close();
		}
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
		int left = this.width / 2 - PANEL_W / 2;
		int gap = 6;

		// --- 1) selection ---
		int y = 68;
		int w3 = (PANEL_W - gap * 2) / 3;
		addDrawableChild(ButtonWidget.builder(Text.literal("Đặt điểm 1"), b -> {
			setCorner(1);
			rebuild();
		}).dimensions(left, y, w3, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("Đặt điểm 2"), b -> {
			setCorner(2);
			rebuild();
		}).dimensions(left + w3 + gap, y, w3, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("Xoá vùng"), b -> {
			sel().clear();
			rebuild();
		}).dimensions(left + (w3 + gap) * 2, y, w3, 20).build());

		// --- 2) run ---
		y = 124;
		int w2 = (PANEL_W - gap) / 2;
		ButtonWidget startBtn = ButtonWidget.builder(Text.literal("▶ Bắt đầu đào"), b -> {
			String err = engine().start();
			if (err == null) {
				close();
			}
		}).dimensions(left, y, w2, 20).build();
		startBtn.active = sel().isComplete();
		addDrawableChild(startBtn);
		addDrawableChild(ButtonWidget.builder(Text.literal("⏹ Dừng"), b -> {
			engine().stop();
			rebuild();
		}).dimensions(left + w2 + gap, y, w2, 20).build());

		y += 24;
		addDrawableChild(ButtonWidget.builder(Text.literal("⏸ Tạm dừng"), b -> {
			engine().pause();
			rebuild();
		}).dimensions(left, y, w2, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("▶ Tiếp tục"), b -> {
			engine().resume();
			rebuild();
		}).dimensions(left + w2 + gap, y, w2, 20).build());

		// --- 3) shape ---
		y = 196;
		addStepper(left, y, w2, "Cao mỗi tầng", () -> config().layerHeight, v -> {
			config().layerHeight = clamp(v, 1, 6);
			config().save();
		});
		addStepper(left + w2 + gap, y, w2, "Rộng mặt đào", () -> config().passWidth, v -> {
			config().passWidth = clamp(v, 1, 5);
			config().save();
		});

		// --- 4) options ---
		y = 244;
		addToggle(left, y, w2, "Né dung nham",
				() -> config().avoidLava, v -> config().avoidLava = v);
		addToggle(left + w2 + gap, y, w2, "Chạy nhanh",
				() -> config().allowSprint, v -> config().allowSprint = v);
		y += 24;
		addToggle(left, y, w2, "Xây trụ leo lên",
				() -> config().allowPlace, v -> config().allowPlace = v);
		addToggle(left + w2 + gap, y, w2, "Hiện khung",
				() -> config().renderSelection, v -> config().renderSelection = v);
		y += 24;
		addToggle(left, y, w2, "Xẻng vàng mark",
				() -> config().goldenShovelMark, v -> config().goldenShovelMark = v);
		addToggle(left + w2 + gap, y, w2, "Tự động ăn",
				() -> config().autoEat, v -> config().autoEat = v);
		y += 24;
		addToggle(left, y, w2, "Vét sạch tầng",
				() -> config().sweepLayer, v -> config().sweepLayer = v);
		addToggle(left + w2 + gap, y, w2, "Lấp nước/lava",
				() -> config().fillFluids, v -> config().fillFluids = v);

		// --- 5) auto-eat settings ---
		y += 24;
		addStepper(left, y, w2, "Ăn khi mất (thanh)", () -> config().autoEatThreshold, v -> {
			config().autoEatThreshold = clamp(v, 1, 10);
			config().save();
		});
		addToggle(left + w2 + gap, y, w2, "Né acc khác",
				() -> config().avoidPlayers, v -> config().avoidPlayers = v);

		y += 28;
		addDrawableChild(ButtonWidget.builder(Text.literal("Đóng"), b -> close())
				.dimensions(left + PANEL_W / 2 - 60, y, 120, 20).build());
	}

	private void rebuild() {
		this.clearChildren();
		this.init();
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

	private interface IntGetter { int get(); }
	private interface IntSetter { void set(int v); }
	private interface BoolGetter { boolean get(); }
	private interface BoolSetter { void set(boolean v); }

	/** A "-  label: n  +" row built from three buttons. */
	private void addStepper(int x, int y, int w, String label, IntGetter getter, IntSetter setter) {
		int side = 20;
		addDrawableChild(ButtonWidget.builder(Text.literal("−"), b -> {
			setter.set(getter.get() - 1);
			rebuild();
		}).dimensions(x, y, side, 20).build());

		ButtonWidget value = ButtonWidget.builder(
						Text.literal(label + ": §e" + getter.get()), b -> {})
				.dimensions(x + side, y, w - side * 2, 20).build();
		value.active = false;
		addDrawableChild(value);

		addDrawableChild(ButtonWidget.builder(Text.literal("+"), b -> {
			setter.set(getter.get() + 1);
			rebuild();
		}).dimensions(x + w - side, y, side, 20).build());
	}

	private void addToggle(int x, int y, int w, String label, BoolGetter getter, BoolSetter setter) {
		ButtonWidget[] holder = new ButtonWidget[1];
		holder[0] = ButtonWidget.builder(toggleLabel(label, getter.get()), b -> {
			setter.set(!getter.get());
			config().save();
			holder[0].setMessage(toggleLabel(label, getter.get()));
		}).dimensions(x, y, w, 20).build();
		addDrawableChild(holder[0]);
	}

	private static Text toggleLabel(String label, boolean on) {
		return Text.literal(label + ": " + (on ? "§aBẬT" : "§cTẮT"));
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		int cx = this.width / 2;
		int left = cx - PANEL_W / 2;

		context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§b§lAutoMine"), cx, 16, 0xFFFFFFFF);
		context.drawCenteredTextWithShadow(this.textRenderer,
				Text.literal("§7» " + engine().statusLine()), cx, 30, STATUS);

		header(context, left, 44, "1) Chọn vùng  (/sel 1, /sel 2)");
		desc(context, left, 55, "Đứng ở góc thứ nhất bấm Điểm 1, sang góc đối diện bấm Điểm 2.");

		String selText = sel().isComplete()
				? "§aVùng: " + sel().describe()
				: "§7Vùng: " + sel().describe();
		context.drawTextWithShadow(this.textRenderer, Text.literal(selText), left, 94, 0xFFFFFFFF);

		header(context, left, 108, "2) Chạy  (/start, /stop)");
		desc(context, left, 119, "Đào rỗng cả vùng, chia tầng, làm từ trên xuống dưới.");

		header(context, left, 172, "3) Kích thước mặt đào");
		desc(context, left, 183, "Mỗi bước đào một mặt rộng × cao (mặc định 3×3 = 9 ô), rồi tiến tới.");

		header(context, left, 228, "4) Tuỳ chọn");

		if (engine().plan() != null && engine().state() == QuarryEngine.State.RUNNING) {
			int barY = 400;
			int barW = PANEL_W;
			int filled = Math.round(barW * engine().plan().progress());
			context.fill(left, barY, left + barW, barY + 4, 0xFF333333);
			context.fill(left, barY, left + filled, barY + 4, 0xFF55FF55);
		}
	}

	private void header(DrawContext context, int x, int y, String text) {
		context.drawTextWithShadow(this.textRenderer, Text.literal(text), x, y, LABEL);
	}

	private void desc(DrawContext context, int x, int y, String text) {
		context.drawTextWithShadow(this.textRenderer, Text.literal(text), x, y, DESC);
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}
