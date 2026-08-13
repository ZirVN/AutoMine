package com.automine.render;

import com.automine.AutoMineClient;
import com.automine.mine.QuarryEngine;
import com.automine.mine.Selection;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

/** Top-left overlay: what the dig is doing, plus the marked corners while selecting. */
public final class StatusHud {
	private static final int MARGIN = 4;
	private static final int LINE = 10;
	private static final int GREEN = 0xFF55FF55;
	private static final int YELLOW = 0xFFFFE070;
	private static final int GREY = 0xFFB0B6C0;

	public void render(DrawContext context) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null || mc.options.hudHidden) {
			return;
		}
		QuarryEngine engine = AutoMineClient.ENGINE;
		Selection sel = AutoMineClient.SELECTION;
		if (engine == null || sel == null) {
			return;
		}

		boolean running = engine.isActive();
		boolean hasSelection = sel.pos1() != null || sel.pos2() != null;
		if (!running && !hasSelection) {
			return;
		}

		int y = MARGIN;
		if (running) {
			context.drawTextWithShadow(mc.textRenderer,
					Text.literal("AutoMine » " + engine.statusLine()), MARGIN, y, GREEN);
			y += LINE;
			BlockPos target = engine.activeTarget();
			if (target != null) {
				context.drawTextWithShadow(mc.textRenderer,
						Text.literal("  đang đào " + target.toShortString()), MARGIN, y, GREY);
				y += LINE;
			}
		}

		if (hasSelection) {
			context.drawTextWithShadow(mc.textRenderer,
					Text.literal("  điểm 1: " + fmt(sel.pos1()) + "   điểm 2: " + fmt(sel.pos2())), MARGIN, y, YELLOW);
			y += LINE;
			if (sel.isComplete() && !running) {
				context.drawTextWithShadow(mc.textRenderer,
						Text.literal("  vùng " + sel.describe() + " — /start để đào"), MARGIN, y, GREY);
			}
		}
	}

	private static String fmt(BlockPos pos) {
		return pos == null ? "—" : pos.getX() + " " + pos.getY() + " " + pos.getZ();
	}
}
