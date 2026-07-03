package com.autobuilder;

import com.autobuilder.build.AutoBuildEngine;
import com.autobuilder.command.AutoBuilderCommand;
import com.autobuilder.config.AutoBuilderConfig;
import com.autobuilder.gui.MaterialListScreen;
import com.autobuilder.material.MaterialEntry;
import com.autobuilder.material.MaterialList;
import com.autobuilder.render.StatusHudRenderer;
import com.autobuilder.schematic.SchematicData;
import com.autobuilder.schematic.SchematicLoader;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

@Environment(EnvType.CLIENT)
public final class AutoBuilderClient implements ClientModInitializer {
	public static AutoBuilderConfig CONFIG;
	public static AutoBuildEngine ENGINE;

	private static Path schematicsDir;
	private static SchematicData loadedSchematic;
	private static BlockPos loadedOrigin;

	@Override
	public void onInitializeClient() {
		Path configDir = FabricLoader.getInstance().getConfigDir();
		CONFIG = AutoBuilderConfig.loadOrCreate(configDir);
		schematicsDir = configDir.resolve(CONFIG.schematicsFolder);
		try {
			Files.createDirectories(schematicsDir);
		} catch (IOException ignored) {
			// non-fatal: the user can still point to an absolute path
		}

		ENGINE = new AutoBuildEngine(MinecraftClient.getInstance(), CONFIG);
		AutoBuilderCommand.register();

		KeyBinding materialKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.autobuilder.materials", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_M, KeyBinding.Category.MISC));

		StatusHudRenderer hud = new StatusHudRenderer(ENGINE, CONFIG);
		HudElementRegistry.addLast(Identifier.of("autobuilder", "status"), (context, tickCounter) -> hud.render(context));
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (materialKey.wasPressed()) {
				openMaterialList();
			}
			ENGINE.tick();
		});
	}

	public static Path schematicsDir() {
		return schematicsDir;
	}

	public static void loadSchematic(String fileName, BlockPos origin) throws IOException {
		Path path = schematicsDir.resolve(fileName);
		loadedSchematic = SchematicLoader.load(path);
		loadedOrigin = origin;

		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player != null) {
			client.player.sendMessage(Text.translatable("autobuilder.msg.loaded", loadedSchematic.name(), loadedSchematic.blockCount()), false);
			sendRegionMessage(client);
		}
	}

	/** Overrides where the schematic's minimum corner (0,0,0) is placed in the world. */
	public static boolean setOrigin(BlockPos origin) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (loadedSchematic == null) {
			if (client.player != null) {
				client.player.sendMessage(Text.translatable("autobuilder.msg.no_schematic"), false);
			}
			return false;
		}
		loadedOrigin = origin;
		if (client.player != null) {
			sendRegionMessage(client);
		}
		return true;
	}

	private static void sendRegionMessage(MinecraftClient client) {
		BlockPos min = loadedOrigin;
		BlockPos max = min.add(loadedSchematic.size()).add(-1, -1, -1);
		client.player.sendMessage(Text.translatable("autobuilder.msg.region",
				min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ()), false);
	}

	public static boolean startLoadedSchematic() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (loadedSchematic == null || loadedOrigin == null) {
			if (client.player != null) {
				client.player.sendMessage(Text.translatable("autobuilder.msg.no_schematic"), false);
			}
			return false;
		}

		// Warn (but don't block) if the player is short on materials.
		List<MaterialEntry> materials = computeMaterials();
		int missing = MaterialList.totalMissing(materials);
		if (missing > 0 && client.player != null) {
			client.player.sendMessage(Text.translatable("autobuilder.msg.start_missing", missing), false);
		}

		ENGINE.start(loadedSchematic, loadedOrigin);
		return true;
	}

	public static boolean hasSchematic() {
		return loadedSchematic != null;
	}

	/** Full material list for the loaded schematic vs. the current inventory, or empty if none loaded. */
	public static List<MaterialEntry> computeMaterials() {
		if (loadedSchematic == null) {
			return Collections.emptyList();
		}
		MinecraftClient client = MinecraftClient.getInstance();
		PlayerInventory inventory = client.player != null ? client.player.getInventory() : null;
		return MaterialList.compute(loadedSchematic, inventory);
	}

	public static void openMaterialList() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (loadedSchematic == null) {
			if (client.player != null) {
				client.player.sendMessage(Text.translatable("autobuilder.msg.no_schematic"), false);
			}
			return;
		}
		client.setScreen(new MaterialListScreen(computeMaterials()));
	}
}
