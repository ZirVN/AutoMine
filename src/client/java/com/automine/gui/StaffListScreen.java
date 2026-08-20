package com.automine.gui;

import com.automine.AutoMineClient;
import com.automine.config.AutoMineConfig;
import com.automine.util.StaffGuard;
import java.util.List;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/**
 * Sửa danh sách tên staff của AutoMine (dùng chung cho Staff HUD + Auto Sign): gõ tên → Thêm,
 * bấm §cX§r để xoá, "Mặc định" khôi phục 26 tên gốc. Tên đang online hiện chấm xanh.
 */
public final class StaffListScreen extends Screen implements StyledScreen {

	private final Screen parent;
	private TextFieldWidget nameField;

	private static final int ROW_H = 13;
	private static final int LIST_TOP = 74;
	private static final int PANEL_BG = 0xD91E1E22;
	private static final int PANEL_BORDER = 0x14FFFFFF;

	public StaffListScreen(Screen parent) {
		super(Text.literal("Danh Sách Staff"));
		this.parent = parent;
	}

	private static AutoMineConfig config() {
		return AutoMineClient.CONFIG;
	}

	private int contentWidth() {
		return Math.min(420, this.width - 16);
	}

	private int contentX() {
		return (this.width - contentWidth()) / 2;
	}

	private int rowsPerColumn() {
		return Math.max(5, (this.height - LIST_TOP - 40) / ROW_H);
	}

	@Override
	protected void init() {
		int x0 = contentX();
		int fullW = contentWidth();
		int addW = 70;
		int resetW = 84;

		nameField = new TextFieldWidget(this.textRenderer, x0 + 1, 40, fullW - addW - resetW - 10, 20,
				Text.literal("Tên staff..."));
		nameField.setMaxLength(24);
		nameField.setPlaceholder(Text.literal("§7Tên staff..."));
		addDrawableChild(nameField);

		addDrawableChild(FlatButton.of(x0 + fullW - addW - resetW - 4, 40, addW, 20, "§aThêm", b -> addName()));
		addDrawableChild(FlatButton.of(x0 + fullW - resetW, 40, resetW, 20, "§eMặc định", b -> config().resetStaff()));
		addDrawableChild(FlatButton.of(x0 + (fullW - 100) / 2, this.height - 28, 100, 20, "Đóng", b -> close()));
	}

	private void addName() {
		String name = nameField.getText().trim();
		if (config().addStaff(name)) {
			nameField.setText("");
		}
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		super.render(ctx, mouseX, mouseY, delta);

		int x0 = contentX();
		int fullW = contentWidth();
		Cards.roundedRect(ctx, x0 - 8, 12, fullW + 16, this.height - 24, 8, PANEL_BG);
		Cards.roundedBorder(ctx, x0 - 8, 12, fullW + 16, this.height - 24, 8, 1, PANEL_BORDER);

		ctx.drawText(this.textRenderer, this.title.getString(), x0, 18, 0xFFFFFFFF, true);

		List<String> names = config().staffList();
		int perCol = rowsPerColumn();
		int colW = (fullW - 8) / 2;

		ctx.drawText(this.textRenderer, "§7Tổng: §f" + names.size() + " §7— chấm §axanh§7 = đang online",
				x0, LIST_TOP - 12, 0xFF9BA1AB, true);

		for (int i = 0; i < names.size() && i < perCol * 2; i++) {
			String name = names.get(i);
			int col = i / perCol;
			int x = x0 + col * (colW + 8);
			int y = LIST_TOP + (i % perCol) * ROW_H;
			boolean online = false;
			for (String on : StaffGuard.onlineStaff()) {
				if (on.equalsIgnoreCase(name)) {
					online = true;
					break;
				}
			}
			ctx.drawText(this.textRenderer, online ? "§a●" : "§8●", x, y, 0xFFFFFFFF, true);
			ctx.drawText(this.textRenderer, Cards.ellipsize(this.textRenderer, name, colW - 25), x + 11, y, online ? 0xFFFFFFFF : 0xFFC9CBD1, true);
			int xBtn = x + colW - 10;
			boolean hov = mouseX >= xBtn - 2 && mouseX <= xBtn + 8 && mouseY >= y - 1 && mouseY <= y + 10;
			ctx.drawText(this.textRenderer, "X", xBtn, y, hov ? 0xFFFF3333 : 0xFF666A72, true);
		}

		if (names.size() > perCol * 2) {
			ctx.drawText(this.textRenderer, "§8+ " + (names.size() - perCol * 2) + " nữa…",
					x0, LIST_TOP + perCol * ROW_H + 2, 0xFF888888, true);
		}
	}

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		List<String> names = config().staffList();
		int perCol = rowsPerColumn();
		int colW = (contentWidth() - 8) / 2;
		int x0 = contentX();

		for (int i = 0; i < names.size() && i < perCol * 2; i++) {
			int col = i / perCol;
			int xBtn = x0 + col * (colW + 8) + colW - 10;
			int y = LIST_TOP + (i % perCol) * ROW_H;
			if (click.x() >= xBtn - 2 && click.x() <= xBtn + 8 && click.y() >= y - 1 && click.y() <= y + 10) {
				config().removeStaff(names.get(i));
				return true;
			}
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public void close() {
		if (this.client != null) {
			this.client.setScreen(this.parent);
		}
	}
}
