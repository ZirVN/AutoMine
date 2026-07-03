package com.autobuilder.render;

import com.autobuilder.build.AutoBuildEngine;
import com.autobuilder.config.AutoBuilderConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.minecraft.util.Colors;

/** Small always-on-screen status line ("AutoBuilder: PLACING - 42 left") while a build is running. */
public final class StatusHudRenderer {

	private final AutoBuildEngine engine;
	private final AutoBuilderConfig config;

	public StatusHudRenderer(AutoBuildEngine engine, AutoBuilderConfig config) {
		this.engine = engine;
		this.config = config;
	}

	public void render(DrawContext context) {
		if (!config.showStatusHud || !engine.isActive()) {
			return;
		}

		MinecraftClient client = MinecraftClient.getInstance();
		String text = "AutoBuilder: " + engine.state() + " - " + engine.remainingTasks() + " left";
		context.drawText(client.textRenderer, Text.literal(text), 6, 6, Colors.WHITE, true);
	}
}
