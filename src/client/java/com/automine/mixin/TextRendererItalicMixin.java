package com.automine.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Style;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Nghiêng toàn bộ chữ trong menu của AutoMine (kiểu 𝘴𝘰𝘮𝘦 𝘱𝘳𝘦𝘷𝘪𝘦𝘸 𝘵𝘦𝘹𝘵).
 *
 * <p>Không ship font italic vì bộ font sẵn có chỉ còn serif italic và mono
 * italic (Inter italic là .otf/CFF, Minecraft từ chối). Thay vào đó mượn đúng
 * cơ chế nghiêng của game: {@code Style.withItalic} khiến renderer xô nghiêng
 * từng glyph của font hiện tại.
 *
 * <p>Chốt tại {@code TextRenderer$Drawer.accept} — cửa mà MỌI glyph đi qua kèm
 * style của nó — nên nghiêng cả chữ mà code của mod không cầm được (ô nhập, tên
 * bài hát, nhãn nút vanilla). Lớp đó package-private nên phải khai bằng
 * {@code targets} thay vì class literal.
 */
@Mixin(targets = "net.minecraft.client.font.TextRenderer$Drawer")
public class TextRendererItalicMixin {

	@ModifyVariable(method = "accept", at = @At("HEAD"), argsOnly = true, require = 0)
	private Style automine$italic(Style style) {
		if (style != null && !style.isItalic()
				&& com.automine.gui.VdmTheme.isModScreen(MinecraftClient.getInstance().currentScreen)) {
			return style.withItalic(true);
		}
		return style;
	}
}
