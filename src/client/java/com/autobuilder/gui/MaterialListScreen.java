package com.autobuilder.gui;

import com.autobuilder.material.MaterialEntry;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

import java.util.List;

/** Litematica-style material list window: item icon, name, and have/need counts, with scrolling. */
public final class MaterialListScreen extends Screen {

	private static final int ROW_HEIGHT = 20;
	private static final int TOP = 48;

	private static final int WHITE = 0xFFFFFFFF;
	private static final int GRAY = 0xFFAAAAAA;
	private static final int RED = 0xFFFF5555;
	private static final int GREEN = 0xFF55FF55;

	private final List<MaterialEntry> entries;
	private final int totalRequired;
	private final int totalMissing;
	private int scroll;
	private int bottom;

	public MaterialListScreen(List<MaterialEntry> entries) {
		super(Text.literal("AutoBuilder Material List"));
		this.entries = entries;
		int req = 0;
		int miss = 0;
		for (MaterialEntry e : entries) {
			req += e.required();
			miss += e.missing();
		}
		this.totalRequired = req;
		this.totalMissing = miss;
	}

	@Override
	protected void init() {
		bottom = height - 40;
		addDrawableChild(ButtonWidget.builder(Text.literal("Close"), b -> close())
				.dimensions(width / 2 - 60, height - 28, 120, 20).build());
	}

	private int visibleRows() {
		return Math.max(1, (bottom - TOP) / ROW_HEIGHT);
	}

	private int maxScroll() {
		return Math.max(0, entries.size() - visibleRows());
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		scroll = Math.max(0, Math.min(maxScroll(), scroll - (int) Math.signum(verticalAmount)));
		return true;
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
		super.render(context, mouseX, mouseY, deltaTicks);

		context.drawText(textRenderer, title, width / 2 - textRenderer.getWidth(title) / 2, 16, WHITE, true);

		String summary = "Types: " + entries.size() + "    Blocks: " + totalRequired
				+ (totalMissing > 0 ? ("    Missing: " + totalMissing) : "    All materials ready");
		context.drawText(textRenderer, Text.literal(summary),
				width / 2 - textRenderer.getWidth(summary) / 2, 30, totalMissing > 0 ? RED : GREEN, true);

		int x = width / 2 - 140;
		int rows = visibleRows();
		for (int i = 0; i < rows && i + scroll < entries.size(); i++) {
			MaterialEntry entry = entries.get(i + scroll);
			int y = TOP + i * ROW_HEIGHT;

			ItemStack icon = entry.item().getDefaultStack();
			context.drawItem(icon, x, y);

			String name = entry.item().getName().getString();
			context.drawText(textRenderer, Text.literal(name), x + 22, y + 4, WHITE, true);

			String counts = entry.available() + " / " + entry.required();
			context.drawText(textRenderer, Text.literal(counts), x + 232, y + 4, entry.hasEnough() ? GREEN : RED, true);
		}

		if (maxScroll() > 0) {
			String hint = "Scroll for more  (" + (scroll + 1) + " / " + (maxScroll() + 1) + ")";
			context.drawText(textRenderer, Text.literal(hint),
					width / 2 - textRenderer.getWidth(hint) / 2, bottom + 6, GRAY, true);
		}
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}
