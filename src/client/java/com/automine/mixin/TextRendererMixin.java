package com.automine.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.StyleSpriteSource;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Đổi font chữ sang Roboto trong lúc menu của AutoMine đang mở.
 *
 * <p>{@code TextRenderer.getGlyphs} là cửa duy nhất mọi chữ đi qua để lấy bộ
 * glyph — đổi tham số ở đây là đổi hết (tiêu đề, dòng tuỳ chọn, số liệu, thẻ
 * nhạc) mà không phải sửa từng lời gọi vẽ chữ, và không sót chỗ nào còn font
 * pixel trông chắp vá.
 *
 * <p>Font khai báo trong {@code assets/automine/font/sleek.json}: Roboto
 * trước, ba provider gốc của vanilla sau — ký tự nào font không có (⛏ ⚙ ☰ ♪)
 * tự rơi về font mặc định, vì bộ chữ lấy provider ĐẦU TIÊN có glyph.
 */
@Mixin(TextRenderer.class)
public class TextRendererMixin {

	private static final StyleSpriteSource AUTOMINE_FONT =
			new StyleSpriteSource.Font(Identifier.of("automine", "sleek"));

	@ModifyVariable(method = "getGlyphs", at = @At("HEAD"), argsOnly = true)
	private StyleSpriteSource automine$modFont(StyleSpriteSource source) {
		// isModScreen thay cho instanceof: nhận cả màn hình của mod ANH EM
		// (AutoSellVDM / Litematica fork) — bộ ba dùng chung một kiểu chữ.
		if (source instanceof StyleSpriteSource.Font font
				&& font.id().equals(MinecraftClient.DEFAULT_FONT_ID)
				&& com.automine.gui.VdmTheme.isModScreen(MinecraftClient.getInstance().currentScreen)) {
			return AUTOMINE_FONT;
		}
		return source;
	}
}
