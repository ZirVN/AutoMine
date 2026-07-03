package com.autobuilder.command;

import com.autobuilder.AutoBuilderClient;
import com.autobuilder.material.MaterialEntry;
import com.autobuilder.material.MaterialList;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.util.List;

/** /autobuilder load|origin|start|pause|resume|stop|status|materials */
public final class AutoBuilderCommand {

	private AutoBuilderCommand() {
	}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
				ClientCommandManager.literal("autobuilder")
						.then(ClientCommandManager.literal("load")
								.then(ClientCommandManager.argument("file", StringArgumentType.greedyString())
										.executes(ctx -> load(ctx.getSource(), StringArgumentType.getString(ctx, "file")))))
						.then(ClientCommandManager.literal("origin")
								.then(ClientCommandManager.argument("x", IntegerArgumentType.integer())
										.then(ClientCommandManager.argument("y", IntegerArgumentType.integer())
												.then(ClientCommandManager.argument("z", IntegerArgumentType.integer())
														.executes(ctx -> origin(ctx.getSource(),
																IntegerArgumentType.getInteger(ctx, "x"),
																IntegerArgumentType.getInteger(ctx, "y"),
																IntegerArgumentType.getInteger(ctx, "z")))))))
						.then(ClientCommandManager.literal("start").executes(ctx -> start(ctx.getSource())))
						.then(ClientCommandManager.literal("pause").executes(ctx -> pause(ctx.getSource())))
						.then(ClientCommandManager.literal("resume").executes(ctx -> resume(ctx.getSource())))
						.then(ClientCommandManager.literal("stop").executes(ctx -> stop(ctx.getSource())))
						.then(ClientCommandManager.literal("status").executes(ctx -> status(ctx.getSource())))
						.then(ClientCommandManager.literal("materials").executes(ctx -> materials(ctx.getSource())))));
	}

	private static int load(FabricClientCommandSource source, String file) {
		try {
			String name = file.endsWith(".litematic") || file.endsWith(".schem") || file.endsWith(".schematic")
					? file
					: file + ".litematic";
			AutoBuilderClient.loadSchematic(name, source.getPlayer().getBlockPos());
		} catch (IOException e) {
			source.sendError(Text.literal("AutoBuilder: failed to load '" + file + "': " + e.getMessage()));
		}
		return 1;
	}

	private static int origin(FabricClientCommandSource source, int x, int y, int z) {
		AutoBuilderClient.setOrigin(new BlockPos(x, y, z));
		return 1;
	}

	private static int start(FabricClientCommandSource source) {
		AutoBuilderClient.startLoadedSchematic();
		return 1;
	}

	private static int pause(FabricClientCommandSource source) {
		AutoBuilderClient.ENGINE.pause();
		return 1;
	}

	private static int resume(FabricClientCommandSource source) {
		AutoBuilderClient.ENGINE.resume();
		return 1;
	}

	private static int stop(FabricClientCommandSource source) {
		AutoBuilderClient.ENGINE.stop();
		source.sendFeedback(Text.translatable("autobuilder.msg.stopped"));
		return 1;
	}

	private static int status(FabricClientCommandSource source) {
		source.sendFeedback(Text.literal(
				"AutoBuilder: " + AutoBuilderClient.ENGINE.state() + " - " + AutoBuilderClient.ENGINE.remainingTasks() + " blocks left"));
		return 1;
	}

	private static int materials(FabricClientCommandSource source) {
		if (!AutoBuilderClient.hasSchematic()) {
			source.sendError(Text.translatable("autobuilder.msg.no_schematic"));
			return 1;
		}

		List<MaterialEntry> entries = AutoBuilderClient.computeMaterials();
		int missing = MaterialList.totalMissing(entries);

		source.sendFeedback(Text.literal("=== AutoBuilder materials (" + entries.size() + " types) ===").formatted(Formatting.YELLOW));

		int shown = 0;
		for (MaterialEntry entry : entries) {
			if (shown >= 30) {
				source.sendFeedback(Text.literal("  ... and " + (entries.size() - shown) + " more (press M for full list)").formatted(Formatting.GRAY));
				break;
			}
			Formatting color = entry.hasEnough() ? Formatting.GREEN : Formatting.RED;
			source.sendFeedback(Text.literal("  " + entry.item().getName().getString() + ": "
					+ entry.available() + " / " + entry.required()).formatted(color));
			shown++;
		}

		if (missing > 0) {
			source.sendFeedback(Text.literal("Missing " + missing + " item(s) total.").formatted(Formatting.RED));
		} else {
			source.sendFeedback(Text.literal("All materials ready.").formatted(Formatting.GREEN));
		}
		return 1;
	}
}
