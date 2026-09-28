package com.automine.gui;

import com.automine.spotify.MusicSearch;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

/**
 * Màn YouTube trong AutoMine: gõ tên video (hoặc dán link/ID YouTube) rồi bấm là mở NGAY. Cửa sổ mở ở
 * dạng "khung nổi" đè lên game (Chrome/Edge chế độ --app, không thanh tab, ghim luôn-trên-cùng) nên
 * nhìn được ngay trong Minecraft — miễn là để MC ở CHẾ ĐỘ CỬA SỔ (không fullscreen độc quyền).
 * Gõ tên thì hiện danh sách kết quả, bấm một dòng là xem; dán link thì mở thẳng, khỏi tìm.
 */
public final class YoutubeScreen extends Screen implements StyledScreen {

	private static final int PANEL_W = 340;
	private static final int ROW_H = 24;
	private static final int LIST_TOP = 62;

	// Bắt link/ID YouTube ở mọi dạng: youtube.com/watch?v=, youtu.be/, /shorts/, /embed/, hoặc ID trần.
	private static final Pattern YT_ID = Pattern.compile(
			"(?:youtu\\.be/|v=|/shorts/|/embed/|/live/)([A-Za-z0-9_-]{11})");
	private static final Pattern BARE_ID = Pattern.compile("^[A-Za-z0-9_-]{11}$");

	/** Hồ sơ riêng cho cửa sổ nổi — tách hẳn tiến trình Chrome/Edge của overlay để dễ ghim topmost. */
	private static final String OVERLAY_PROFILE =
			System.getProperty("java.io.tmpdir") + "automine-yt-overlay";

	private final Screen parent;
	private TextFieldWidget searchBox;
	private List<MusicSearch.Song> results = List.of();
	private String status = "§7Dán link YouTube rồi bấm §fXem ngay§7 — cửa sổ sẽ nổi đè lên game.";
	private int scroll;

	public YoutubeScreen(Screen parent) {
		super(Text.literal("Xem Web"));
		this.parent = parent;
	}

	private int panelLeft() {
		return this.width / 2 - PANEL_W / 2;
	}

	private int listBottom() {
		return this.height - 58;
	}

	private int visibleRows() {
		return Math.max(1, (listBottom() - LIST_TOP) / ROW_H);
	}

	@Override
	protected void init() {
		int left = panelLeft();
		searchBox = new TextFieldWidget(this.textRenderer, left, 26, PANEL_W - 176, 18,
				Text.literal("Tìm YouTube"));
		searchBox.setMaxLength(300);
		searchBox.setEditableColor(0xFFFFFFFF);   // chữ gõ vào TRẮNG
		searchBox.setUneditableColor(0xFFFFFFFF);
		searchBox.setPlaceholder(Text.literal("§7Tìm YouTube — hoặc dán LINK WEB bất kỳ (facebook, shopee…)"));
		addDrawableChild(searchBox);

		addDrawableChild(FlatButton.of(left + PANEL_W - 172, 26, 78, 18, "Tìm", b -> doSearch()));
		addDrawableChild(FlatButton.of(left + PANEL_W - 90, 26, 90, 18, "Xem ngay", b -> openTyped()));

		addDrawableChild(FlatButton.of(this.width / 2 - 60, this.height - 26, 120, 20, "Đóng",
				b -> close()));
	}

	/** Bấm "Tìm": nếu ô nhập là link/ID thì mở thẳng, không thì tra YouTube và hiện danh sách. */
	private void doSearch() {
		String q = searchBox.getText().trim();
		if (q.isEmpty()) {
			return;
		}
		if (openIfLink(q)) {
			return;
		}
		this.status = "§7Đang tìm \"" + q + "\"…";
		this.results = List.of();
		this.scroll = 0;
		MusicSearch.search(q, list -> this.client.execute(() -> {
			this.results = list;
			this.status = list.isEmpty()
					? "§cKhông thấy kết quả nào — thử từ khoá khác."
					: "§a" + list.size() + " kết quả — bấm một dòng để xem.";
		}), err -> this.client.execute(() ->
				this.status = "§cLỗi tìm kiếm: " + err));
	}

	/** Bấm "Xem ngay": link thì mở thẳng, chữ thường thì mở trang tìm kiếm YouTube. */
	private void openTyped() {
		String q = searchBox.getText().trim();
		if (q.isEmpty()) {
			return;
		}
		if (openIfLink(q)) {
			return;
		}
		open("https://www.youtube.com/results?search_query=" + urlEncode(q));
	}

	/** Mở thẳng nếu chuỗi là link — YouTube hay BẤT KỲ trang web nào. @return true nếu đã mở. */
	private boolean openIfLink(String q) {
		String id = extractId(q);
		if (id != null) {
			open("https://www.youtube.com/watch?v=" + id);
			return true;
		}
		if (q.contains("youtube.com/") || q.contains("youtu.be/")) {
			open(q.startsWith("http") ? q : "https://" + q);
			return true;
		}
		// LINK WEB BẤT KỲ → cùng một cửa sổ nổi (lệnh user 2026-08-20: "gửi link
		// nào vào đó sẽ hiện lên màn y hệt ytb — xem cái gì trên web cũng được").
		// open() tự lo: không phải YouTube thì URL đi nguyên vào cửa sổ --app,
		// vẫn không viền, ghim trên game, kéo được y hệt khung YouTube.
		if (q.startsWith("http://") || q.startsWith("https://")) {
			open(q);
			return true;
		}
		if (q.matches("[\\w.-]+\\.[a-zA-Z]{2,}(/\\S*)?")) {
			open("https://" + q); // gõ trần kiểu "facebook.com/abc" cũng nhận
			return true;
		}
		return false;
	}

	private static String extractId(String q) {
		if (BARE_ID.matcher(q).matches()) {
			return q;
		}
		Matcher m = YT_ID.matcher(q);
		return m.find() ? m.group(1) : null;
	}

	/**
	 * Mở video ở dạng CỬA SỔ NỔI đè lên game: Chrome/Edge chế độ {@code --app} (không thanh tab/địa chỉ,
	 * trông như một khung video), đặt giữa-trên rồi ghim luôn-trên-cùng để nằm trên Minecraft. Không có
	 * Chrome/Edge thì mở bằng trình duyệt mặc định (cửa sổ riêng) như cũ.
	 */
	private void open(String url) {
		// Video đơn → mở TRANG HTML CỤC BỘ chứa <iframe> embed: chỉ hiện KHUNG VIDEO (không header/
		// sidebar/gợi ý), tự phát, và VÌ nằm trong iframe nên KHÔNG dính "Lỗi 153" (lỗi này chỉ xảy ra
		// khi mở thẳng URL /embed làm trang gốc). Playlist/kênh/tìm kiếm thì mở nguyên trang.
		String playUrl = playerPageFor(url);

		if (openOverlay(playUrl)) {
			this.status = "§aĐã mở — kéo dải xám TRÊN CÙNG cửa sổ để dời; kéo mép để to/nhỏ.";
			return;
		}
		try {
			Util.getOperatingSystem().open(playUrl);
			this.status = "§eKhông thấy Chrome/Edge — mở bằng trình duyệt mặc định (cửa sổ riêng).";
		} catch (Throwable t) {
			this.status = "§cKhông mở được trình duyệt: " + t.getMessage();
		}
	}

	// Web-server nhỏ tại 127.0.0.1 phục vụ trang wrapper. LÝ DO: mở wrapper từ file:// thì YouTube coi
	// origin là "null" và báo Lỗi 153; phục vụ qua HTTP localhost cho origin hợp lệ nên embed chạy.
	private static com.sun.net.httpserver.HttpServer server;
	private static int serverPort = -1;

	/**
	 * Với một video đơn: trả URL http://127.0.0.1:PORT/?v=ID — server nội bộ trả trang chỉ có iframe
	 * player. Không rút được ID (playlist/kênh/tìm kiếm) → trả nguyên URL. Server lỗi → về link watch.
	 */
	private static String playerPageFor(String url) {
		String id = extractId(url);
		if (id == null) {
			return url;
		}
		String local = localPlayerUrl(id);
		return local != null ? local : "https://www.youtube.com/watch?v=" + id + "&autoplay=1";
	}

	private static synchronized String localPlayerUrl(String id) {
		ensureServer();
		if (serverPort < 0) {
			return null;
		}
		return "http://127.0.0.1:" + serverPort + "/?v=" + id;
	}

	/** Dựng server 127.0.0.1 một lần (cổng ngẫu nhiên). Mỗi request trả trang iframe cho ?v=ID. */
	private static synchronized void ensureServer() {
		if (server != null) {
			return;
		}
		try {
			com.sun.net.httpserver.HttpServer s = com.sun.net.httpserver.HttpServer.create(
					new java.net.InetSocketAddress("127.0.0.1", 0), 0);
			s.createContext("/", exchange -> {
				String q = exchange.getRequestURI().getQuery();
				String id = "";
				if (q != null) {
					for (String p : q.split("&")) {
						if (p.startsWith("v=")) {
							id = p.substring(2);
						}
					}
				}
				id = id.replaceAll("[^A-Za-z0-9_-]", "");
				int port = exchange.getLocalAddress().getPort();
				String origin = "http://127.0.0.1:" + port;
				String html = "<!doctype html><html><head><meta charset=\"utf-8\"><title>YouTube</title>"
						+ "<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}"
						+ "body{display:flex;flex-direction:column}"
						+ "#bar{flex:0 0 30px;height:30px;background:rgba(20,20,24,.96);cursor:move;user-select:none;border-bottom:1px solid rgba(255,255,255,.10);display:flex;align-items:center;justify-content:center}"
						+ "#bar::after{content:'';width:46px;height:4px;border-radius:3px;background:rgba(255,255,255,.28)}"
						+ "#bar:hover{background:rgba(34,34,40,.96)}"
						+ "iframe{border:0;width:100%;flex:1 1 auto;display:block}</style></head><body>"
						+ "<div id=\"bar\"></div>"
						+ "<iframe src=\"https://www.youtube.com/embed/" + id
						+ "?autoplay=1&fs=1&rel=0&modestbranding=1&playsinline=1&origin=" + origin + "\""
						+ " allow=\"autoplay; fullscreen; picture-in-picture; encrypted-media\""
						+ " allowfullscreen></iframe>"
						+ "<script>(function(){var b=document.getElementById('bar'),on=false,px=0,py=0;"
						+ "b.addEventListener('pointerdown',function(e){on=true;px=e.screenX;py=e.screenY;"
						+ "b.setPointerCapture(e.pointerId);e.preventDefault();});"
						+ "b.addEventListener('pointermove',function(e){if(!on)return;"
						+ "var dx=e.screenX-px,dy=e.screenY-py;if(dx||dy){window.moveBy(dx,dy);px=e.screenX;py=e.screenY;}});"
						+ "var end=function(e){on=false;try{b.releasePointerCapture(e.pointerId);}catch(_){}};"
						+ "b.addEventListener('pointerup',end);b.addEventListener('pointercancel',end);})();</script>"
						+ "</body></html>";
				byte[] b = html.getBytes(java.nio.charset.StandardCharsets.UTF_8);
				exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
				exchange.sendResponseHeaders(200, b.length);
				try (java.io.OutputStream os = exchange.getResponseBody()) {
					os.write(b);
				}
			});
			s.setExecutor(null);
			s.start();
			server = s;
			serverPort = s.getAddress().getPort();
		} catch (Throwable t) {
			server = null;
			serverPort = -1;
		}
	}

	private boolean openOverlay(String url) {
		String browser = findChromium();
		if (browser == null) {
			return false;
		}

		ensureShutdownHook(); // tắt game (JVM thoát) → tự đóng cửa sổ YouTube, không để lại tiến trình mồ côi

		// KHUNG NHỎ GÓC DƯỚI-PHẢI: cửa sổ ~16:9 nhỏ, ép sát mép phải + đáy khung game (chừa lề dưới để
		// không đè lên taskbar). ĐỌC vị trí Ở ĐÂY (thread client) vì API cửa sổ GLFW chỉ an toàn ở thread
		// chính; phần đóng cửa sổ cũ + bật Chrome đẩy sang thread nền để không làm đơ game.
		int w = 520;
		int h = 374;   // dư ~34px (title bar Chrome bị xén) + 30px (thanh KÉO) → video ~300px
		int x = 1360;
		int y = 700;
		try {
			var win = net.minecraft.client.MinecraftClient.getInstance().getWindow();
			x = win.getX() + Math.max(0, win.getWidth() - w - 16);
			y = win.getY() + Math.max(0, win.getHeight() - h - 56);
		} catch (Throwable ignored) {
			// API cửa sổ đổi ở bản khác → dùng vị trí cố định ở trên.
		}

		final int fx = x, fy = y, fw = w, fh = h;
		Thread t = new Thread(() -> {
			try {
				// ĐÓNG mọi cửa sổ overlay CŨ trước (tránh xếp chồng + cửa sổ đen trống thừa), rồi mở đúng
				// MỘT cửa sổ mới. Bỏ "--new-window" (thừa với --app, hay đẻ thêm cửa sổ trống); thêm cờ
				// chặn bong bóng "khôi phục phiên" khi cửa sổ cũ vừa bị đóng cưỡng bức.
				closeOverlayWindows();
				new ProcessBuilder(
						browser,
						"--app=" + url,
						"--user-data-dir=" + OVERLAY_PROFILE,
						"--window-size=" + fw + "," + fh,
						"--window-position=" + fx + "," + fy,
						"--no-first-run",
						"--no-default-browser-check",
						"--disable-session-crashed-bubble",
						"--noerrdialogs",
						"--autoplay-policy=no-user-gesture-required")
						.start();
				pinTopMostAsync();
			} catch (Throwable ignored) {
			}
		}, "yt-overlay-launch");
		t.setDaemon(true);
		t.start();
		return true;
	}

	/**
	 * Đóng MỌI cửa sổ overlay YouTube đang mở (chỉ Windows): kill các tiến trình chrome/edge có
	 * {@code automine-yt-overlay} trong dòng lệnh — KHÔNG đụng Chrome thường của người dùng (profile
	 * khác, không mang cờ này). Chờ tối đa vài giây cho tiến trình chết + khoá {@code SingletonLock}
	 * của profile nhả ra rồi mới cho mở lại. Gọi trong THREAD NỀN (có blocking). Lỗi / không phải
	 * Windows thì bỏ qua.
	 */
	private static void closeOverlayWindows() {
		if (System.getProperty("os.name", "").toLowerCase().contains("win") == false) {
			return;
		}
		try {
			String ps = "Get-CimInstance Win32_Process -Filter \"Name='chrome.exe' OR Name='msedge.exe'\""
					+ " | Where-Object { $_.CommandLine -like '*automine-yt-overlay*' }"
					+ " | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }";
			Process p = new ProcessBuilder("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
					"-WindowStyle", "Hidden", "-Command", ps).start();
			p.waitFor(4, java.util.concurrent.TimeUnit.SECONDS);
			Thread.sleep(400);
		} catch (Throwable ignored) {
		}
	}

	private static boolean shutdownHookAdded;

	/** Đăng ký MỘT LẦN hook chạy lúc JVM tắt (thoát game) để dọn cửa sổ overlay còn sót. */
	private static synchronized void ensureShutdownHook() {
		if (shutdownHookAdded) {
			return;
		}
		shutdownHookAdded = true;
		try {
			Runtime.getRuntime().addShutdownHook(new Thread(YoutubeScreen::killOverlayNow, "yt-overlay-cleanup"));
		} catch (Throwable ignored) {
		}
	}

	/** Bắn PowerShell kill mọi tiến trình overlay (chỉ Windows). Trả Process để bên gọi tự chờ hay không. */
	private static Process spawnKillOverlay() {
		if (System.getProperty("os.name", "").toLowerCase().contains("win") == false) {
			return null;
		}
		try {
			String ps = "Get-CimInstance Win32_Process -Filter \"Name='chrome.exe' OR Name='msedge.exe'\""
					+ " | Where-Object { $_.CommandLine -like '*automine-yt-overlay*' }"
					+ " | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }";
			return new ProcessBuilder("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
					"-WindowStyle", "Hidden", "-Command", ps).start();
		} catch (Throwable ignored) {
			return null;
		}
	}

	/** Fire-and-forget (shutdown hook lúc JVM tắt): bắn kill rồi để powershell tự chạy tiếp. */
	private static void killOverlayNow() {
		spawnKillOverlay();
	}

	/**
	 * Gọi khi GAME ĐANG ĐÓNG có trật tự (Fabric CLIENT_STOPPING) — JVM còn sống nên chờ ngắn cho
	 * PowerShell kill xong cửa sổ overlay trước khi game thoát hẳn. Công khai cho entry-point gọi.
	 */
	public static void onGameStopping() {
		try {
			Process p = spawnKillOverlay();
			if (p != null) {
				p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
			}
		} catch (Throwable ignored) {
		}
	}

	/** Chrome trước, không có thì Edge (cùng nhân Chromium, cùng cờ --app). null = không có cái nào. */
	private static String findChromium() {
		String[] cands = {
				env("ProgramFiles") + "\\Google\\Chrome\\Application\\chrome.exe",
				env("ProgramFiles(x86)") + "\\Google\\Chrome\\Application\\chrome.exe",
				env("LOCALAPPDATA") + "\\Google\\Chrome\\Application\\chrome.exe",
				env("ProgramFiles(x86)") + "\\Microsoft\\Edge\\Application\\msedge.exe",
				env("ProgramFiles") + "\\Microsoft\\Edge\\Application\\msedge.exe",
		};
		for (String c : cands) {
			if (c != null && new java.io.File(c).isFile()) {
				return c;
			}
		}
		return null;
	}

	private static String env(String k) {
		String v = System.getenv(k);
		return v == null ? "" : v;
	}

	/**
	 * Ghim cửa sổ overlay LUÔN-TRÊN-CÙNG + XÉN HẲN THANH TIÊU ĐỀ (chỉ Windows). Chạy nền: viết một
	 * .ps1 tìm tiến trình chrome/edge có {@code automine-yt-overlay} trong dòng lệnh, rồi mỗi 0.5s:
	 * <ol>
	 *   <li>Đo chiều cao thanh tiêu đề = khoảng cách từ mép trên cửa sổ ({@code GetWindowRect}) tới
	 *       mép trên vùng client ({@code ClientToScreen(0,0)}), rồi {@code SetWindowRgn} cắt vùng
	 *       hiển thị bỏ đúng dải đó — thanh "YouTube ▢ ✕" biến mất về mặt PIXEL (không phụ thuộc
	 *       Chrome vẽ khung kiểu gì, vì {@code WS_CAPTION} gỡ style vẫn bị Chrome vẽ lại qua DWM).
	 *       Vẫn giữ viền cạnh/đáy nên kéo mép trái/phải/dưới để resize được.</li>
	 *   <li>{@code SetWindowPos} HWND_TOPMOST để nằm trên Minecraft.</li>
	 * </ol>
	 * Lặp lại 45s (chống Chrome vẽ lại khung lúc mới nạp / khi resize); vùng cắt vẫn giữ sau khi vòng
	 * lặp dừng. Lỗi / không phải Windows thì bỏ qua (cửa sổ vẫn mở, chỉ không ghim/không xén).
	 */
	private static void pinTopMostAsync() {
		if (System.getProperty("os.name", "").toLowerCase().contains("win") == false) {
			return;
		}
		Thread t = new Thread(() -> {
			try {
				String ps = """
$ErrorActionPreference="SilentlyContinue"
Add-Type @"
using System;using System.Runtime.InteropServices;
public struct RC{public int L,T,R,B;}
public class WT{
 [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr h,IntPtr a,int x,int y,int cx,int cy,uint f);
 [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h,out RC r);
 [DllImport("user32.dll")] public static extern uint GetDpiForWindow(IntPtr h);
 [DllImport("gdi32.dll")] public static extern IntPtr CreateRectRgn(int a,int b,int c,int d);
 [DllImport("user32.dll")] public static extern int SetWindowRgn(IntPtr h,IntPtr r,bool b);
}
"@
$deadline=(Get-Date).AddSeconds(45)
while((Get-Date) -lt $deadline){
  $procs = Get-CimInstance Win32_Process -Filter "Name='chrome.exe' OR Name='msedge.exe'" | Where-Object { $_.CommandLine -like '*automine-yt-overlay*' }
  foreach($pr in $procs){
    $h=(Get-Process -Id $pr.ProcessId).MainWindowHandle
    if($h -ne 0){
      $wr=New-Object RC; [WT]::GetWindowRect($h,[ref]$wr) | Out-Null
      $ww=$wr.R-$wr.L; $wh=$wr.B-$wr.T
      $dpi=[WT]::GetDpiForWindow($h); if($dpi -le 0){ $dpi=96 }
      $strip=[int][Math]::Ceiling(34.0*$dpi/96.0)
      if($ww -gt 0 -and $wh -gt $strip){
        $rgn=[WT]::CreateRectRgn(0,$strip,$ww,$wh)
        [WT]::SetWindowRgn($h,$rgn,$true) | Out-Null
      }
      [WT]::SetWindowPos($h,[IntPtr]-1,0,0,0,0,3) | Out-Null
    }
  }
  Start-Sleep -Milliseconds 500
}
""";
				java.io.File f = new java.io.File(System.getProperty("java.io.tmpdir"), "automine-yt-topmost.ps1");
				java.nio.file.Files.writeString(f.toPath(), ps);
				new ProcessBuilder("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
						"-WindowStyle", "Hidden", "-File", f.getAbsolutePath()).start();
			} catch (Throwable ignored) {
			}
		}, "yt-overlay-topmost");
		t.setDaemon(true);
		t.start();
	}

	private static String urlEncode(String s) {
		return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
	}

	@Override
	public boolean keyPressed(KeyInput input) {
		if (input.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER
				|| input.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
			if (searchBox.isFocused()) {
				doSearch();
				return true;
			}
		}
		return super.keyPressed(input);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amountX, double amountY) {
		int max = Math.max(0, results.size() - visibleRows());
		this.scroll = Math.max(0, Math.min(max, this.scroll - (int) Math.signum(amountY)));
		return true;
	}

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		if (click.button() == 0) {
			int idx = rowAt(click.x(), click.y());
			if (idx >= 0 && idx < results.size()) {
				MusicSearch.Song song = results.get(idx);
				String id = song.videoId();
				if (id != null) {
					open("https://www.youtube.com/watch?v=" + id);
					this.status = "§aĐang phát: §f" + song.title();
				} else {
					open("https://www.youtube.com/results?search_query=" + urlEncode(song.query()));
				}
				return true;
			}
		}
		return super.mouseClicked(click, doubled);
	}

	private int rowAt(double mx, double my) {
		int left = panelLeft();
		if (mx < left || mx > left + PANEL_W || my < LIST_TOP || my > listBottom()) {
			return -1;
		}
		return this.scroll + (int) ((my - LIST_TOP) / ROW_H);
	}

	@Override
	public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
		super.renderBackground(ctx, mouseX, mouseY, delta);
		int left = panelLeft();
		// Nền panel vẽ Ở ĐÂY (TRƯỚC widget) + đủ ĐẶC để nền game đỏ không lọt qua ám màu chữ. Trước
		// đây vẽ trong render() SAU super.render() nên panel bán trong suốt phủ đè lên ô nhập/nút.
		Cards.roundedRect(ctx, left - 8, 12, PANEL_W + 16, this.height - 24, 8, 0xF0141418);
		Cards.roundedBorder(ctx, left - 8, 12, PANEL_W + 16, this.height - 24, 8, 1, 0x22FFFFFF);
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		super.render(ctx, mouseX, mouseY, delta);

		int left = panelLeft();
		ctx.drawText(this.textRenderer, "🌐 Xem Web · ▶ YouTube", left, 16, 0xFFFF5555, true);

		int rows = visibleRows();
		for (int i = 0; i < rows; i++) {
			int idx = this.scroll + i;
			if (idx >= results.size()) {
				break;
			}
			MusicSearch.Song song = results.get(idx);
			int y = LIST_TOP + i * ROW_H;
			boolean hov = mouseX >= left && mouseX <= left + PANEL_W && mouseY >= y && mouseY <= y + ROW_H - 2;
			Cards.roundedRect(ctx, left, y, PANEL_W, ROW_H - 2, 4, hov ? 0x24FFFFFF : 0x14FFFFFF);
			ctx.drawText(this.textRenderer, trim(song.title(), 46), left + 6, y + 3, 0xFFFFFFFF, false);
			if (song.subtitle() != null && song.subtitle().isEmpty() == false) {
				ctx.drawText(this.textRenderer, "§7" + trim(song.subtitle(), 52), left + 6, y + 12, 0xFF9BA1AB, false);
			}
		}

		if (results.size() > rows) {
			ctx.drawText(this.textRenderer, "§8▲▼ lăn chuột để xem thêm",
					left, listBottom() + 2, 0xFF888888, false);
		}

		// Cắt theo BỀ RỘNG panel để chữ không tràn ra ngoài khung; nằm trên nút Đóng.
		ctx.drawText(this.textRenderer, fitWidth(status, PANEL_W), left, this.height - 44, 0xFFFFFFFF, true);
	}

	private static String trim(String s, int max) {
		if (s == null) {
			return "";
		}
		return s.length() > max ? s.substring(0, max - 1) + "…" : s;
	}

	/** Cắt chuỗi cho vừa {@code maxW} pixel (thêm "…") — chống chữ tràn khỏi panel. */
	private String fitWidth(String s, int maxW) {
		if (s == null) {
			return "";
		}
		if (this.textRenderer.getWidth(s) <= maxW) {
			return s;
		}
		while (s.length() > 1 && this.textRenderer.getWidth(s + "…") > maxW) {
			s = s.substring(0, s.length() - 1);
		}
		return s + "…";
	}

	@Override
	public void close() {
		if (this.client != null) {
			this.client.setScreen(this.parent);
		}
	}
}
