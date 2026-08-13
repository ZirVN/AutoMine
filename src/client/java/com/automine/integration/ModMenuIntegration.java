package com.automine.integration;

import com.automine.gui.AutoMineMenuScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Puts a working "Settings" button on AutoMine's entry in the Mods list, so the
 * control panel can be opened straight from there instead of typing a command.
 * The parent screen is remembered, so closing the panel returns to the mod list.
 */
public final class ModMenuIntegration implements ModMenuApi {

	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return AutoMineMenuScreen::new;
	}
}
