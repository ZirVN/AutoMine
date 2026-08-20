package com.automine.mixin;

import com.automine.gui.VdmTheme;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Ô nhập trong menu của bộ mod: thay khung texture vanilla bằng khung tối bo
 * góc, giữ nguyên mọi thứ còn lại. Bản yarn của {@code MixinEditBox} bên
 * AutoSellVDM (mojmap EditBox = yarn TextFieldWidget).
 *
 * <p>Chỉ chặn đúng lời vẽ KHUNG ({@code drawGuiTexture}), KHÔNG tắt cờ viền —
 * tắt cờ là vanilla dời chữ lên sát mép trên, lệch hẳn khỏi khung (bug cũ bên
 * AutoSell đã dính).
 *
 * <p><b>{@code require = 0} là bắt buộc:</b> AutoSellVDM và Litematica fork vá
 * đúng lời gọi này bằng redirect y hệt, mà Mixin chỉ cho MỘT redirect trên một
 * lời gọi — cái thua bị bỏ qua, để require mặc định là crash lúc nạp. Cái nào
 * thắng cũng tô cho màn hình của cả bộ nhờ {@link VdmTheme#isModScreen}.
 */
@Mixin(TextFieldWidget.class)
public class TextFieldWidgetMixin {

	@Redirect(method = "renderWidget", require = 0,
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/gui/DrawContext;drawGuiTexture"
							+ "(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/util/Identifier;IIII)V"))
	private void automine$flatField(DrawContext context, RenderPipeline pipeline, Identifier sprite,
			int x, int y, int width, int height) {
		if (VdmTheme.isModScreen(MinecraftClient.getInstance().currentScreen)) {
			VdmTheme.field(context, x, y, width, height,
					((TextFieldWidget) (Object) this).isFocused());
			return;
		}
		context.drawGuiTexture(pipeline, sprite, x, y, width, height);
	}
}
