package com.automine.gui;

import java.util.function.Supplier;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.ClickableWidget;

/**
 * Nút phẳng theo đúng ngôn ngữ thẻ Spotify của client gốc: nền tối bo góc,
 * hover sáng nhẹ, trạng thái BẬT viền + chữ xanh nhấn.
 *
 * <p>Kế thừa thẳng {@code ClickableWidget}: bản 1.21.11 đã khoá final
 * {@code PressableWidget.renderWidget} (và {@code ButtonWidget} thành abstract
 * kèm nested class {@code Text} che mất import), nên vẽ tùy biến phải đứng ở
 * tầng này. Widget của riêng mình, không mixin vào của ai — an toàn tuyệt đối.
 */
public final class FlatButton extends ClickableWidget {

	@FunctionalInterface
	public interface PressAction {
		void onPress(FlatButton button);
	}

	private final PressAction onPress;
	/** Null = nút thường; khác null = nút toggle, true → tô xanh nhấn. */
	private final Supplier<Boolean> accentWhen;

	public FlatButton(int x, int y, int width, int height, net.minecraft.text.Text message,
			PressAction onPress, Supplier<Boolean> accentWhen) {
		super(x, y, width, height, message);
		this.onPress = onPress;
		this.accentWhen = accentWhen;
	}

	public static FlatButton of(int x, int y, int width, int height, String label, PressAction onPress) {
		return new FlatButton(x, y, width, height, net.minecraft.text.Text.literal(label), onPress, null);
	}

	@Override
	public void onClick(Click click, boolean doubled) {
		if (onPress != null) {
			onPress.onPress(this);
		}
	}

	@Override
	protected void renderWidget(DrawContext ctx, int mouseX, int mouseY, float delta) {
		// Cùng một kiểu nút với cả bộ mod (VdmTheme của AutoSellVDM) — bỏ viền
		// xanh Spotify cũ cho khỏi lệch tông. Trạng thái BẬT thể hiện bằng viền
		// nhấn sáng, đúng cách nút-đang-rê của theme.
		boolean on = accentWhen != null && Boolean.TRUE.equals(accentWhen.get());
		VdmTheme.button(ctx, MinecraftClient.getInstance().textRenderer,
				getX(), getY(), this.width, this.height, getMessage(),
				on || this.isHovered(), this.active);
	}

	@Override
	protected void appendClickableNarrations(NarrationMessageBuilder builder) {
		appendDefaultNarrations(builder);
	}
}
