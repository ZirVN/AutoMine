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
	/** Reach used to decide whether a block can be mined from here (vanilla is ~4.5). */
	public double reachDistance = 4.5;
	/** Draw the selection box, the current layer and the block being mined. */
	public boolean renderSelection = true;
	/** Tự động ăn táo vàng khi mất thanh đói. */
	public boolean autoEat = true;
	/** Số thanh đói mất trước khi tự động ăn (1 thanh = 2 hunger points). */
	public int autoEatThreshold = 2;
	/** Tự quăng bình exp (Mending) hồi cúp khi độ bền tụt thấp. */
	public boolean expRepair = true;
	/** Độ bền còn lại kích hoạt việc quăng exp. */
	public int expRepairThreshold = 50;
	/** Thẻ "đang phát" Spotify trên HUD. */
	public boolean spotifyHud = true;
	public int spotifyX = 5;
	public int spotifyY = 45;
	/** Playlist Spotify công khai cho list nhạc (mặc định: Top 50 Việt Nam). */
	public String musicPlaylist = "37i9dQZEVXbLdGSmz6xilI";
	// --- Staff List + Auto Sign (2026-08-18; No-Render đã XOÁ HẲN khỏi AutoMine —
	// tính năng chỉ còn ở AutoSellVDM + SpawnerProtect theo lời chốt) ---
	public boolean staffHud = true;
	public int staffHudX = -1;
	public int staffHudY = -1;
	public boolean autoSign = false;
	/** Bán kính (block) coi là "staff tới gần" cho Auto Sign. Mặc định 10. */
	public int staffRadius = 10;
	/** 4 dòng cách nhau dấu | , mỗi dòng <=15 ký tự. */
	public String signText = "Minh AFK dao da|Khong dung hack|Cam on staff <3";
	/** Tên staff cách nhau dấu phẩy. */
	public String staffNames = "DrDonutt,Dough4,Fallerfly,Evxn,Ryuui,Shyalyy,OGsummer,ItsDefRealMe,LzouZMp5,Munkerlich,Chaon,Showered,PastaGamer,Bautiegar,bloodspulse,GsMusie,Frwost,FluffyMaster07,W1zoX_,Itszdeath,archivePedro,evify,NoahvdAa,Zababi,Nathan,Owen1212055";

	// --- Báo Discord khi dính nước/dung nham (khuôn config theo AutoSellVDM) ---
	/** Bật gửi cảnh báo webhook khi máy đang đào mà dính nước/dung nham. */
	public boolean alertFluid = true;
	/** URL webhook Discord (https://discord.com/api/webhooks/...). */
	public String alertWebhook = "";
	/** Id Discord của người chơi để ping (chỉ số; dán nguyên <@id> cũng nhận). */
	public String alertDiscordId = "";

	/** Vị trí các panel ClickGUI, dạng "x,y|x,y|..." — rỗng = xếp cột mặc định. */
	public String guiPanels = "";
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
		return List.of("layerHeight", "passWidth", "allowSprint", "allowPlace", "sweepLayer", "reachDistance", "renderSelection", "autoEat", "autoEatThreshold",
				"expRepair", "expRepairThreshold",
				"alertFluid", "alertWebhook", "alertDiscordId",
				"spotifyHud", "spotifyX", "spotifyY", "musicPlaylist", "guiPanels", "staffHud", "staffHudX", "staffHudY", "autoSign", "staffRadius", "signText", "staffNames");
	}

	public String get(String key) {
		return switch (key) {
			case "layerHeight" -> String.valueOf(layerHeight);
			case "passWidth" -> String.valueOf(passWidth);
			case "allowSprint" -> String.valueOf(allowSprint);
			case "allowPlace" -> String.valueOf(allowPlace);
			case "sweepLayer" -> String.valueOf(sweepLayer);
			case "reachDistance" -> String.valueOf(reachDistance);
			case "renderSelection" -> String.valueOf(renderSelection);
			case "autoEat" -> String.valueOf(autoEat);
			case "autoEatThreshold" -> String.valueOf(autoEatThreshold);
			case "expRepair" -> String.valueOf(expRepair);
			case "expRepairThreshold" -> String.valueOf(expRepairThreshold);
			case "alertFluid" -> String.valueOf(alertFluid);
			case "alertWebhook" -> alertWebhook;
			case "alertDiscordId" -> alertDiscordId;
			case "spotifyHud" -> String.valueOf(spotifyHud);
			case "spotifyX" -> String.valueOf(spotifyX);
			case "spotifyY" -> String.valueOf(spotifyY);
			case "musicPlaylist" -> musicPlaylist;
			case "guiPanels" -> guiPanels;
			case "staffHud" -> String.valueOf(staffHud);
			case "staffHudX" -> String.valueOf(staffHudX);
			case "staffHudY" -> String.valueOf(staffHudY);
			case "autoSign" -> String.valueOf(autoSign);
			case "staffRadius" -> String.valueOf(staffRadius);
			case "signText" -> signText;
			case "staffNames" -> staffNames;
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
				case "reachDistance" -> reachDistance = Double.parseDouble(value);
				case "renderSelection" -> renderSelection = parseBool(value);
				case "autoEat" -> autoEat = parseBool(value);
				case "autoEatThreshold" -> autoEatThreshold = clamp(Integer.parseInt(value), 1, 10);
				case "expRepair" -> expRepair = parseBool(value);
				case "expRepairThreshold" -> expRepairThreshold = clamp(Integer.parseInt(value), 1, 1000);
				case "alertFluid" -> alertFluid = parseBool(value);
				case "alertWebhook" -> alertWebhook = value;
				case "alertDiscordId" -> alertDiscordId = value;
				case "spotifyHud" -> spotifyHud = parseBool(value);
				case "spotifyX" -> spotifyX = clamp(Integer.parseInt(value), 0, 4000);
				case "spotifyY" -> spotifyY = clamp(Integer.parseInt(value), 0, 4000);
				case "musicPlaylist" -> musicPlaylist = value.isEmpty() ? musicPlaylist : value;
				case "guiPanels" -> guiPanels = value;
				case "staffHud" -> staffHud = parseBool(value);
				case "staffHudX" -> staffHudX = Integer.parseInt(value);
				case "staffHudY" -> staffHudY = Integer.parseInt(value);
				case "autoSign" -> autoSign = parseBool(value);
				case "staffRadius" -> staffRadius = clamp(Integer.parseInt(value), 1, 128);
				case "signText" -> signText = value.isEmpty() ? signText : value;
				case "staffNames" -> staffNames = value.isEmpty() ? staffNames : value;
				default -> {
					return false;
				}
			}
			return true;
		} catch (NumberFormatException e) {
			return false;
		}
	}

	// --- Danh sách staff (sửa trong GUI; staffNames lưu dạng CSV) ---

	/** staffNames CSV → List, bỏ ô trống. */
	public List<String> staffList() {
		List<String> out = new java.util.ArrayList<>();
		for (String s : staffNames.split(",")) {
			s = s.trim();
			if (!s.isEmpty()) out.add(s);
		}
		return out;
	}

	/** @return true nếu tên chưa có (so không phân biệt hoa thường). */
	public boolean addStaff(String name) {
		if (name == null || name.isBlank()) return false;
		List<String> list = staffList();
		for (String s : list) {
			if (s.equalsIgnoreCase(name.trim())) return false;
		}
		list.add(name.trim());
		staffNames = String.join(",", list);
		save();
		return true;
	}

	public void removeStaff(String name) {
		List<String> list = staffList();
		list.removeIf(s -> s.equalsIgnoreCase(name));
		staffNames = String.join(",", list);
		save();
	}

	/** Khôi phục 26 tên staff gốc. */
	public void resetStaff() {
		staffNames = new AutoMineConfig().staffNames;
		save();
	}

	private static int clamp(int v, int lo, int hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	private static boolean parseBool(String value) {
		String v = value.toLowerCase(Locale.ROOT);
		return v.equals("true") || v.equals("1") || v.equals("yes") || v.equals("on");
	}
}
