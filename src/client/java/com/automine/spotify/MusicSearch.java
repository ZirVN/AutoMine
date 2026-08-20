package com.automine.spotify;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.util.Util;

/**
 * Tìm bài + list nhạc, không cần đăng nhập gì cả. Hai nguồn dữ liệu:
 *
 * <ul>
 * <li><b>Tìm bài</b>: API nội bộ của YouTube Music (InnerTube) — endpoint công
 * khai mà chính trang web dùng, không cần key. Chọn bài là mở
 * {@code youtube.com/watch?v=...}, Chrome TỰ PHÁT ngay (đã test sống: GSMTC
 * nhảy sang "Exit Sign | Playing" sau ~7s, không cần bấm gì).</li>
 * <li><b>List nhạc</b>: playlist Spotify công khai qua trang embed — HTML chứa
 * nguyên track list trong JSON {@code __NEXT_DATA__} (đã test: Top 50 Vietnam
 * trả đủ 50 bài). API chính thức thì khoá token ẩn danh (429), embed thì không.
 * Bài trong playlist không có videoId — lúc phát mới tra YouTube theo
 * "tên bài + ca sĩ đầu" và lấy kết quả đầu.</li>
 * </ul>
 *
 * <p>Vì sao không phát thẳng trên Spotify: acc free không cho API start
 * playback, còn mở trang bài hát của web player thì Chrome không tự bấm play —
 * YouTube là đường duy nhất "chọn là kêu" mà không cần Premium.
 */
public final class MusicSearch {

	/** Một dòng trong list. {@code videoId} null = bài Spotify, phát thì tra YT bằng {@code query}. */
	public record Song(String title, String subtitle, String videoId, String query) {
	}

	private static final String UA =
			"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/126 Safari/537.36";
	/** Filter "chỉ bài hát" của search YT Music (protobuf b64 lấy từ chính trang web). */
	private static final String SONGS_FILTER = "EgWKAQIIAWoKEAkQBRAKEAMQBA==";
	private static final Pattern NEXT_DATA = Pattern.compile(
			"<script id=\"__NEXT_DATA__\" type=\"application/json\">(.*?)</script>", Pattern.DOTALL);
	private static final Pattern PLAYLIST_ID = Pattern.compile("playlist[/:]([A-Za-z0-9]{16,34})");

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(8))
			.followRedirects(HttpClient.Redirect.ALWAYS)
			.build();
	private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "AutoMine-MusicSearch");
		t.setDaemon(true);
		return t;
	});

	private MusicSearch() {
	}

	/** Nhặt playlist id từ link dán vào ô tìm (link web hoặc spotify:playlist:...). */
	public static String extractPlaylistId(String text) {
		Matcher m = PLAYLIST_ID.matcher(text);
		return m.find() ? m.group(1) : null;
	}

	/** Tìm bài trên YouTube Music; callback chạy Ở LUỒNG NỀN — bên nhận tự mc.execute. */
	public static void search(String query, Consumer<List<Song>> done, Consumer<String> error) {
		EXEC.submit(() -> {
			try {
				List<Song> songs = searchSync(query);
				if (songs.isEmpty()) {
					error.accept("Không tìm thấy bài nào cho \"" + query + "\"");
				} else {
					done.accept(songs);
				}
			} catch (Throwable t) {
				error.accept("Lỗi mạng khi tìm: " + t.getMessage());
			}
		});
	}

	/** Đọc playlist Spotify công khai qua trang embed; callback ở luồng nền. */
	public static void loadPlaylist(String playlistId, BiConsumer<String, List<Song>> done,
			Consumer<String> error) {
		EXEC.submit(() -> {
			try {
				HttpRequest req = HttpRequest.newBuilder(
						URI.create("https://open.spotify.com/embed/playlist/" + playlistId))
						.header("User-Agent", UA)
						.timeout(Duration.ofSeconds(10))
						.GET().build();
				String html = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body();
				Matcher m = NEXT_DATA.matcher(html);
				if (!m.find()) {
					error.accept("Không đọc được playlist (private? hoặc Spotify đổi trang)");
					return;
				}
				JsonObject entity = JsonParser.parseString(m.group(1)).getAsJsonObject()
						.getAsJsonObject("props").getAsJsonObject("pageProps")
						.getAsJsonObject("state").getAsJsonObject("data")
						.getAsJsonObject("entity");
				String name = entity.has("title") ? entity.get("title").getAsString() : "Playlist";
				List<Song> out = new ArrayList<>();
				for (JsonElement e : entity.getAsJsonArray("trackList")) {
					JsonObject t = e.getAsJsonObject();
					String title = t.get("title").getAsString();
					String artists = t.has("subtitle") && !t.get("subtitle").isJsonNull()
							? t.get("subtitle").getAsString() : "";
					long dur = t.has("duration") && !t.get("duration").isJsonNull()
							? t.get("duration").getAsLong() : 0;
					String shown = dur > 0 ? artists + " • " + fmt(dur) : artists;
					String firstArtist = artists.split(",")[0].trim();
					out.add(new Song(title, shown, null, title + " " + firstArtist));
				}
				if (out.isEmpty()) {
					error.accept("Playlist trống hoặc không công khai");
				} else {
					done.accept(name, out);
				}
			} catch (Throwable t) {
				error.accept("Lỗi tải playlist: " + t.getMessage());
			}
		});
	}

	/**
	 * Phát một bài: dừng nhạc đang chạy (toggle phím media — chính là session
	 * hiện tại), rồi mở tab YouTube; Chrome tự phát. Game sẽ nhảy sang Chrome
	 * một nhịp — alt-tab về là nhạc vẫn chạy, HUD tự bắt bài mới qua daemon.
	 */
	public static void play(Song song, SpotifyHudOverlay overlay, Consumer<String> status) {
		EXEC.submit(() -> {
			try {
				String id = song.videoId();
				if (id == null) {
					status.accept("Đang tra YouTube: " + song.title());
					List<Song> found = searchSync(song.query());
					if (found.isEmpty()) {
						status.accept("Không thấy \"" + song.title() + "\" trên YouTube");
						return;
					}
					id = found.get(0).videoId();
				}
				if (overlay != null && overlay.isPlaying()) {
					overlay.playPause();
					Thread.sleep(450); // cho bên cũ kịp dừng trước khi bên mới cất tiếng
				}
				Util.getOperatingSystem().open("https://www.youtube.com/watch?v=" + id);
				status.accept("Đang phát: " + song.title());
			} catch (Throwable t) {
				status.accept("Lỗi khi mở bài: " + t.getMessage());
			}
		});
	}

	private static List<Song> searchSync(String query) throws Exception {
		JsonObject client = new JsonObject();
		client.addProperty("clientName", "WEB_REMIX");
		client.addProperty("clientVersion", "1.20250101.01.00");
		client.addProperty("hl", "vi");
		client.addProperty("gl", "VN");
		JsonObject context = new JsonObject();
		context.add("client", client);
		JsonObject body = new JsonObject();
		body.add("context", context);
		body.addProperty("query", query);
		body.addProperty("params", SONGS_FILTER);

		HttpRequest req = HttpRequest.newBuilder(
				URI.create("https://music.youtube.com/youtubei/v1/search?prettyPrint=false"))
				.header("User-Agent", UA)
				.header("Content-Type", "application/json; charset=utf-8")
				.header("Origin", "https://music.youtube.com")
				.header("Referer", "https://music.youtube.com/")
				.timeout(Duration.ofSeconds(10))
				.POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
				.build();
		String json = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body();

		List<JsonObject> renderers = new ArrayList<>();
		collect(JsonParser.parseString(json), renderers);
		List<Song> out = new ArrayList<>();
		for (JsonObject r : renderers) {
			try {
				String videoId = null;
				if (r.has("playlistItemData")) {
					videoId = r.getAsJsonObject("playlistItemData").get("videoId").getAsString();
				}
				if (videoId == null) {
					videoId = deepFindString(r, "videoId");
				}
				JsonArray cols = r.getAsJsonArray("flexColumns");
				String title = runsText(cols.get(0), true);
				String subtitle = cols.size() > 1 ? runsText(cols.get(1), false) : "";
				if (videoId != null && title != null && !title.isEmpty()) {
					out.add(new Song(title, subtitle, videoId, null));
				}
			} catch (Throwable ignored) {
				// một dòng kết quả hỏng thì bỏ qua dòng đó
			}
			if (out.size() >= 15) {
				break;
			}
		}
		return out;
	}

	/** Gom mọi musicResponsiveListItemRenderer trong cái cây JSON khổng lồ của InnerTube. */
	private static void collect(JsonElement e, List<JsonObject> out) {
		if (e.isJsonObject()) {
			JsonObject o = e.getAsJsonObject();
			if (o.has("musicResponsiveListItemRenderer")) {
				out.add(o.getAsJsonObject("musicResponsiveListItemRenderer"));
			}
			for (var entry : o.entrySet()) {
				collect(entry.getValue(), out);
			}
		} else if (e.isJsonArray()) {
			for (JsonElement c : e.getAsJsonArray()) {
				collect(c, out);
			}
		}
	}

	private static String deepFindString(JsonElement e, String key) {
		if (e.isJsonObject()) {
			JsonObject o = e.getAsJsonObject();
			if (o.has(key) && o.get(key).isJsonPrimitive()) {
				return o.get(key).getAsString();
			}
			for (var entry : o.entrySet()) {
				String found = deepFindString(entry.getValue(), key);
				if (found != null) {
					return found;
				}
			}
		} else if (e.isJsonArray()) {
			for (JsonElement c : e.getAsJsonArray()) {
				String found = deepFindString(c, key);
				if (found != null) {
					return found;
				}
			}
		}
		return null;
	}

	/** Text của một flexColumn: run đầu (tên bài) hoặc nối hết (ca sĩ • album • 3:21). */
	private static String runsText(JsonElement col, boolean firstOnly) {
		JsonArray runs = col.getAsJsonObject()
				.getAsJsonObject("musicResponsiveListItemFlexColumnRenderer")
				.getAsJsonObject("text").getAsJsonArray("runs");
		if (runs == null || runs.isEmpty()) {
			return "";
		}
		if (firstOnly) {
			return runs.get(0).getAsJsonObject().get("text").getAsString();
		}
		StringBuilder sb = new StringBuilder();
		for (JsonElement r : runs) {
			sb.append(r.getAsJsonObject().get("text").getAsString());
		}
		return sb.toString();
	}

	private static String fmt(long ms) {
		long s = ms / 1000;
		return String.format("%d:%02d", s / 60, s % 60);
	}
}
