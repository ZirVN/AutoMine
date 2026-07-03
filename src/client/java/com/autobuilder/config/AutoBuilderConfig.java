package com.autobuilder.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Simple JSON-backed settings for AutoBuilder, stored at config/autobuilder.json. */
public final class AutoBuilderConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String FILE_NAME = "autobuilder.json";

	public double reachDistance = 4.5;
	public int ticksBetweenPlacements = 3;
	public int maxRetriesPerBlock = 20;
	public boolean showStatusHud = true;
	public String schematicsFolder = "autobuilder/schematics";

	public static AutoBuilderConfig loadOrCreate(Path configDir) {
		Path file = configDir.resolve(FILE_NAME);
		if (Files.exists(file)) {
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				AutoBuilderConfig loaded = GSON.fromJson(reader, AutoBuilderConfig.class);
				if (loaded != null) {
					return loaded;
				}
			} catch (IOException | RuntimeException ignored) {
				// fall through to defaults on any parse/IO failure
			}
		}

		AutoBuilderConfig defaults = new AutoBuilderConfig();
		defaults.save(configDir);
		return defaults;
	}

	public void save(Path configDir) {
		Path file = configDir.resolve(FILE_NAME);
		try {
			Files.createDirectories(configDir);
			try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
				GSON.toJson(this, writer);
			}
		} catch (IOException ignored) {
			// non-fatal: config just won't persist this run
		}
	}
}
