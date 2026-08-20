package com.automine.gui;

import com.automine.AutoMineClient;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/**
 * Màn nhập liệu nhỏ cho một dòng config dạng CHỮ (webhook, Discord id, chữ
 * bảng, tên staff…) — sinh ra khi user dẹp cây lệnh {@code /am set} và chốt
 * "all config là ghi vào trong GUI". Ô nhập là TextFieldWidget vanilla nên tự
 * ăn theme tối qua mixin; Enter hoặc nút Lưu là ghi config và quay về menu.
 */
public final class EditTextScreen extends Screen implements StyledScreen {

	private final Screen parent;
	private final String label;
	private final Supplier<String> get;
	private final Consumer<String> set;
	private TextFieldWidget field;

	public EditTextScreen(Screen parent, String label, Supplier<String> get, Consumer<String> set) {
		super(Text.literal(label));
		this.parent = parent;
		this.label = label;
		this.get = get;
		this.set = set;
	}

	@Override
	protected void init() {
		int w = Math.min(340, this.width - 40);
		int x = (this.width - w) / 2;
		int y = this.height / 2 - 10;

		field = new TextFieldWidget(this.textRenderer, x, y, w, 18, Text.literal(label));
		field.setMaxLength(4096);
		field.setText(get.get());
		addDrawableChild(field);
		setInitialFocus(field);

		addDrawableChild(FlatButton.of(x, y + 26, w / 2 - 3, 18, "Lưu", b -> saveAndClose()));
		addDrawableChild(FlatButton.of(x + w / 2 + 3, y + 26, w / 2 - 3, 18, "Huỷ", b -> close()));
	}

	private void saveAndClose() {
		set.accept(field.getText().trim());
		AutoMineClient.CONFIG.save();
		close();
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		int w = Math.min(340, this.width - 40);
		int x = (this.width - w) / 2;
		context.drawTextWithShadow(this.textRenderer,
				Text.literal(VdmTheme.ellipsize(this.textRenderer, label, w)),
				x, this.height / 2 - 24, VdmTheme.TEXT_ON);
	}

	@Override
	public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
		if (client != null && client.world != null) {
			applyBlur(context);
		} else {
			super.renderBackground(context, mouseX, mouseY, delta);
		}
		int w = Math.min(340, this.width - 40) + 24;
		VdmTheme.group(context, (this.width - w) / 2, this.height / 2 - 36, w, 74);
	}

	@Override
	public boolean keyPressed(KeyInput input) {
		if (input.key() == GLFW.GLFW_KEY_ENTER || input.key() == GLFW.GLFW_KEY_KP_ENTER) {
			saveAndClose();
			return true;
		}
		return super.keyPressed(input);
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
}
