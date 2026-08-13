package com.automine.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Dependency-free {@code key=value} settings, editable by hand or with {@code /am set}. */
public final class AutoMineConfig {

	/** How many Y levels each mining layer covers. The player stands on the layer floor. */
	public int layerHeight = 3;
	/** How many blocks wide each pass clears (centred on the player's row). */
	public int passWidth = 3;
	/** Sprint along cleared stretches. */
	public boolean allowSprint = true;
	/** Tower up on placed blocks to climb back out after falling. */
	public boolean allowPlace = true;
	/** Sweep every leftover block out of a layer before descending to the next. */
	public boolean sweepLayer = true;
	/**
	 * Seal fluids with blocks from the hotbar: water gets the whole nearby patch
	 * filled (with a pillar-up breather when air runs low), lava only the cells
	 * blocking the dig.
	 */
	public boolean fillFluids = true;
	/**
	 * Share the box with other accounts: start from the corner away from whoever
	 * is already digging, and leave faces they are standing at for later.
	 */
	public boolean avoidPlayers = true;
	/** Refuse to mine next to lava, and never dig down into it. */
	public boolean avoidLava = true;
	/** Reach used to decide whether a block can be mined from here (vanilla is ~4.5). */
	public double reachDistance = 4.5;
	/** Draw the selection box, the current layer and the block being mined. */
	public boolean renderSelection = true;
	/** Tự động ăn táo vàng khi mất thanh đói. */
	public boolean autoEat = true;
	/** Số thanh đói mất trước khi tự động ăn (1 thanh = 2 hunger points). */
	public int autoEatThreshold = 2;
	/** Cho phép dùng xẻng vàng để đánh dấu điểm 1 và 2. */
	public boolean goldenShovelMark = true;
	private transient Path file;

	public static AutoMineConfig loadOrCreate(Path configDir) {
		AutoMineConfig config = new AutoMineConfig();
		config.file = configDir.resolve("automine.properties");
		if (Files.exists(config.file)) {
			try {
				for (String line : Files.readAllLines(config.file)) {
					line = line.trim();
					if (line.isEmpty() || line.startsWith("#")) continue;
					int eq = line.indexOf('=');
					if (eq > 0) {
						config.applyRaw(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
					}
				}
			} catch (IOException ignored) {
				// keep defaults
			}
		} else {
			config.save();
		}
		return config;
	}

	public void save() {
		if (file == null) return;
		StringBuilder sb = new StringBuilder("# AutoMine settings\n");
		for (String key : keys()) {
			sb.append(key).append('=').append(get(key)).append('\n');
		}
		try {
			Files.writeString(file, sb.toString());
		} catch (IOException ignored) {
			// non-fatal
		}
	}

	public List<String> keys() {
		return List.of("layerHeight", "passWidth", "allowSprint", "allowPlace", "sweepLayer", "fillFluids",
				"avoidPlayers", "avoidLava", "reachDistance", "renderSelection", "autoEat", "autoEatThreshold",
				"goldenShovelMark");
	}

	public String get(String key) {
		return switch (key) {
			case "layerHeight" -> String.valueOf(layerHeight);
			case "passWidth" -> String.valueOf(passWidth);
			case "allowSprint" -> String.valueOf(allowSprint);
			case "allowPlace" -> String.valueOf(allowPlace);
			case "sweepLayer" -> String.valueOf(sweepLayer);
			case "fillFluids" -> String.valueOf(fillFluids);
			case "avoidPlayers" -> String.valueOf(avoidPlayers);
			case "avoidLava" -> String.valueOf(avoidLava);
			case "reachDistance" -> String.valueOf(reachDistance);
			case "renderSelection" -> String.valueOf(renderSelection);
			case "autoEat" -> String.valueOf(autoEat);
			case "autoEatThreshold" -> String.valueOf(autoEatThreshold);
			case "goldenShovelMark" -> String.valueOf(goldenShovelMark);
			default -> null;
		};
	}

	public boolean set(String key, String value) {
		boolean ok = applyRaw(key, value);
		if (ok) save();
		return ok;
	}

	private boolean applyRaw(String key, String value) {
		try {
			switch (key) {
				case "layerHeight" -> layerHeight = clamp(Integer.parseInt(value), 1, 6);
				case "passWidth" -> passWidth = clamp(Integer.parseInt(value), 1, 5);
				case "allowSprint" -> allowSprint = parseBool(value);
				case "allowPlace" -> allowPlace = parseBool(value);
				case "sweepLayer" -> sweepLayer = parseBool(value);
				case "fillFluids" -> fillFluids = parseBool(value);
				case "avoidPlayers" -> avoidPlayers = parseBool(value);
				case "avoidLava" -> avoidLava = parseBool(value);
				case "reachDistance" -> reachDistance = Double.parseDouble(value);
				case "renderSelection" -> renderSelection = parseBool(value);
				case "autoEat" -> autoEat = parseBool(value);
				case "autoEatThreshold" -> autoEatThreshold = clamp(Integer.parseInt(value), 1, 10);
				case "goldenShovelMark" -> goldenShovelMark = parseBool(value);
				default -> {
					return false;
				}
			}
			return true;
		} catch (NumberFormatException e) {
			return false;
		}
	}

	private static int clamp(int v, int lo, int hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	private static boolean parseBool(String value) {
		String v = value.toLowerCase(Locale.ROOT);
		return v.equals("true") || v.equals("1") || v.equals("yes") || v.equals("on");
	}
}
