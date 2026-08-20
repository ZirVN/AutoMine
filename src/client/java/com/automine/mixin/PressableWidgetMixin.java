package com.automine.mixin;

import com.automine.gui.VdmTheme;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.PressableWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vẽ nút phẳng kiểu Dawn thay cho texture nút vanilla — CHỈ khi màn hình đang
 * mở thuộc bộ mod ({@link VdmTheme#isModScreen}). Bản yarn của
 * {@code MixinAbstractButton} bên AutoSellVDM (mojmap AbstractButton = yarn
 * PressableWidget).
 *
 * <p>Phải chặn ở đây vì {@code PressableWidget.renderWidget} là {@code final}:
 * không kế thừa vẽ đè được. HEAD + cancel, và cổng isModScreen giữ cho vanilla
 * nguyên vẹn. Cố tình KHÔNG {@code @Shadow} field kế thừa nào — shadow field
 * lớp cha là công thức crash lúc nạp mixin (bài học malilib cũ).
 */
@Mixin(PressableWidget.class)
public class PressableWidgetMixin {

	@Inject(method = "renderWidget", at = @At("HEAD"), cancellable = true)
	private void automine$flatStyle(DrawContext context, int mouseX, int mouseY, float delta,
			CallbackInfo ci) {
		MinecraftClient mc = MinecraftClient.getInstance();

		// Nhận cả màn hình mod anh em: mod nào vẽ trước thì vẽ đúng kiểu rồi
		// cancel, mod sau khỏi vẽ đè.
		if (!VdmTheme.isModScreen(mc.currentScreen)) {
			return;
		}

		PressableWidget self = (PressableWidget) (Object) this;
		VdmTheme.button(context, mc.textRenderer, self.getX(), self.getY(),
				self.getWidth(), self.getHeight(), self.getMessage(),
				self.isHovered(), self.active);
		ci.cancel();
	}
}
