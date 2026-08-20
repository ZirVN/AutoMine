package com.automine.spotify;

import com.automine.AutoMineClient;
import com.automine.gui.Cards;
import com.automine.gui.FlatButton;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/**
 * Màn hình nhạc: tìm bài (YouTube Music), list nhạc từ playlist Spotify công
 * khai (mặc định Top 50 Việt Nam — dán link playlist của mình vào ô tìm để
 * đổi), bấm một bài là phát ngay (Chrome tự chạy). Vẫn kéo thả được thẻ HUD
 * tới chỗ tuỳ thích và điều khiển lùi/phát-dừng/qua bài từ trong game.
 */
public final class SpotifyScreen extends Screen implements com.automine.gui.StyledScreen {

	private static final int PANEL_W = 340;
	private static final int ROW_H = 24;
	private static final int LIST_TOP = 62;

	private final Screen parent;
	private boolean dragging;
	private double dragOffX;
	private double dragOffY;

	private TextFieldWidget searchBox;
	/** Kết quả hiện trên list — chỉ đổi trên luồng render (mc.execute). */
	private List<MusicSearch.Song> songs = List.of();
	private String listTitle = "";
	private String status = "";
	private int scroll;
	private boolean startedFirstLoad;

	public SpotifyScreen(Screen parent) {
		super(Text.literal("Spotify"));
		this.parent = parent;
	}

	private int panelLeft() {
		return this.width / 2 - PANEL_W / 2;
	}

	private int listBottom() {
		return this.height - 72;
	}

	private int visibleRows() {
		return Math.max(1, (listBottom() - LIST_TOP) / ROW_H);
	}

	@Override
	protected void init() {
		int left = panelLeft();
		searchBox = new TextFieldWidget(this.textRenderer, left, 26, PANEL_W - 118, 18,
				Text.literal("Tìm bài"));
		searchBox.setMaxLength(160);
		searchBox.setPlaceholder(Text.literal("§7Tên bài / ca sĩ — hoặc dán link playlist Spotify"));
		addDrawableChild(searchBox);
		addDrawableChild(FlatButton.of(left + PANEL_W - 114, 26, 54, 18, "Tìm", b -> doSearch()));
		addDrawableChild(FlatButton.of(left + PANEL_W - 56, 26, 56, 18, "Top VN",
				b -> loadPlaylist("37i9dQZEVXbLdGSmz6xilI")));

		int cx = this.width / 2;
		int rowY = this.height - 58;
		addDrawableChild(FlatButton.of(cx - 95, rowY, 44, 20, "|<<",
				b -> AutoMineClient.SPOTIFY.previous()));
		addDrawableChild(new FlatButton(cx - 47, rowY, 94, 20,
				Text.literal("Phát / Dừng"), b -> AutoMineClient.SPOTIFY.playPause(),
				() -> AutoMineClient.SPOTIFY.isPlaying()));
		addDrawableChild(FlatButton.of(cx + 51, rowY, 44, 20, ">>|",
				b -> AutoMineClient.SPOTIFY.next()));
		addDrawableChild(FlatButton.of(cx - 60, this.height - 32, 120, 20, "Đóng",
				b -> close()));

		// Mở màn là có sẵn list (playlist đã lưu; mặc định Top 50 VN) — chỉ lần đầu,
		// init() còn chạy lại mỗi lần đổi cỡ cửa sổ.
		if (!startedFirstLoad && songs.isEmpty()) {
			startedFirstLoad = true;
			loadPlaylist(AutoMineClient.CONFIG.musicPlaylist);
		}
	}

	private void doSearch() {
		String text = searchBox.getText().trim();
		if (text.isEmpty()) {
			return;
		}
		String playlistId = MusicSearch.extractPlaylistId(text);
		if (playlistId != null) {
			AutoMineClient.CONFIG.musicPlaylist = playlistId;
			AutoMineClient.CONFIG.save();
			loadPlaylist(playlistId);
			return;
		}
		status = "Đang tìm \"" + text + "\"…";
		MusicSearch.search(text,
				found -> onMainThread(() -> {
					songs = found;
					listTitle = "Kết quả cho \"" + text + "\"";
					scroll = 0;
					status = "";
				}),
				err -> onMainThread(() -> status = err));
	}

	private void loadPlaylist(String playlistId) {
		status = "Đang tải playlist…";
		MusicSearch.loadPlaylist(playlistId,
				(name, found) -> onMainThread(() -> {
					songs = found;
					listTitle = name + " (" + found.size() + " bài)";
					scroll = 0;
					status = "";
				}),
				err -> onMainThread(() -> status = err));
	}

	private void playRow(MusicSearch.Song song) {
		status = "Đang mở: " + song.title() + "…";
		MusicSearch.play(song, AutoMineClient.SPOTIFY,
				msg -> onMainThread(() -> status = msg));
	}

	private void onMainThread(Runnable r) {
		MinecraftClient.getInstance().execute(r);
	}

	@Override
	public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
		// Kính mờ như menu chính: giữ blur, bỏ lớp phủ tối vanilla.
		if (client != null && client.world != null) {
			applyBlur(context);
		} else {
			super.renderBackground(context, mouseX, mouseY, delta);
		}
		// Tấm nền panel vẽ TRƯỚC widget (ô tìm, nút) để chúng nổi lên trên.
		int left = panelLeft();
		int top = 18;
		int h = listBottom() - 18 + 8;
		Cards.roundedRect(context, left - 8, top + 3, PANEL_W + 20, h, 8, 0x4C000000);
		Cards.roundedRect(context, left - 10, top, PANEL_W + 20, h, 8, 0xA80E0E16);
		Cards.roundedBorder(context, left - 11, top - 1, PANEL_W + 22, h + 2, 9, 1, 0x2E1DB954);
		Cards.roundedBorder(context, left - 10, top, PANEL_W + 20, h, 8, 1, 0x8046464E);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		int left = panelLeft();

		context.drawCenteredTextWithShadow(this.textRenderer,
				Text.literal("Kéo thẻ nhạc tới chỗ muốn đặt — bấm một bài để phát."),
				this.width / 2, 8, Cards.TEXT_DIM);

		context.drawTextWithShadow(this.textRenderer, Text.literal(Cards.ellipsize(this.textRenderer, listTitle, PANEL_W)), left, 50, Cards.ACCENT);

		// Các dòng bài hát.
		int visible = visibleRows();
		int end = Math.min(songs.size(), scroll + visible);
		for (int i = scroll; i < end; i++) {
			MusicSearch.Song song = songs.get(i);
			int ry = LIST_TOP + (i - scroll) * ROW_H;
			boolean hover = mouseX >= left && mouseX <= left + PANEL_W
					&& mouseY >= ry && mouseY < ry + ROW_H;
			if (hover) {
				Cards.roundedRect(context, left - 4, ry, PANEL_W + 8, ROW_H - 2, 3, 0xFF2E2E34);
			}
			context.drawText(this.textRenderer,
					Cards.ellipsize(this.textRenderer, song.title(), PANEL_W - 8),
					left, ry + 3, hover ? Cards.ACCENT : Cards.TEXT_MAIN, false);
			context.drawText(this.textRenderer,
					Cards.ellipsize(this.textRenderer, song.subtitle(), PANEL_W - 8),
					left, ry + 13, Cards.TEXT_DIM, false);
		}

		// Thanh cuộn mảnh bên phải khi list dài hơn khung.
		if (songs.size() > visible) {
			int trackTop = LIST_TOP;
			int trackH = listBottom() - LIST_TOP;
			int thumbH = Math.max(12, trackH * visible / songs.size());
			int maxScroll = songs.size() - visible;
			int thumbY = trackTop + (trackH - thumbH) * scroll / Math.max(1, maxScroll);
			context.fill(left + PANEL_W + 4, trackTop, left + PANEL_W + 7, trackTop + trackH, Cards.TRACK_BG);
			context.fill(left + PANEL_W + 4, thumbY, left + PANEL_W + 7, thumbY + thumbH, Cards.ACCENT);
		}

		if (!status.isEmpty()) {
			context.drawCenteredTextWithShadow(this.textRenderer,
					Text.literal(Cards.ellipsize(this.textRenderer, status, this.width - 20)),
					this.width / 2, listBottom() + 2, Cards.TEXT_DIM);
		}

		// Thẻ HUD vẽ CUỐI để kéo thả luôn nhìn thấy nó, kể cả đè lên panel.
		AutoMineClient.SPOTIFY.renderCard(context);
		if (dragging) {
			Cards.roundedBorder(context,
					AutoMineClient.CONFIG.spotifyX - 2, AutoMineClient.CONFIG.spotifyY - 2,
					SpotifyHudOverlay.CARD_W + 4, SpotifyHudOverlay.CARD_H + 4, 6, 1, 0xFFFFFFFF);
		}
	}

	// 1.21.11 gom chuột về record Click (x, y, button) — chữ ký cũ
	// (double,double,int) không còn tồn tại để override.
	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		// Thẻ HUD ăn click trước (nó vẽ trên cùng) -> rồi widget -> rồi list.
		int x = AutoMineClient.CONFIG.spotifyX;
		int y = AutoMineClient.CONFIG.spotifyY;
		if (click.button() == 0
				&& click.x() >= x && click.x() <= x + SpotifyHudOverlay.CARD_W
				&& click.y() >= y && click.y() <= y + SpotifyHudOverlay.CARD_H) {
			dragging = true;
			dragOffX = click.x() - x;
			dragOffY = click.y() - y;
			return true;
		}
		if (super.mouseClicked(click, doubled)) {
			return true;
		}
		int left = panelLeft();
		if (click.button() == 0
				&& click.x() >= left - 4 && click.x() <= left + PANEL_W + 4
				&& click.y() >= LIST_TOP && click.y() < listBottom()) {
			int index = scroll + (int) ((click.y() - LIST_TOP) / ROW_H);
			if (index >= 0 && index < songs.size()) {
				playRow(songs.get(index));
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean mouseDragged(Click click, double deltaX, double deltaY) {
		if (dragging) {
			AutoMineClient.CONFIG.spotifyX = clamp((int) (click.x() - dragOffX), 0,
					Math.max(0, this.width - SpotifyHudOverlay.CARD_W));
			AutoMineClient.CONFIG.spotifyY = clamp((int) (click.y() - dragOffY), 0,
					Math.max(0, this.height - SpotifyHudOverlay.CARD_H));
			return true;
		}
		return super.mouseDragged(click, deltaX, deltaY);
	}

	@Override
	public boolean mouseReleased(Click click) {
		if (dragging) {
			dragging = false;
			AutoMineClient.CONFIG.save();
			return true;
		}
		return super.mouseReleased(click);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
		int left = panelLeft();
		if (mouseX >= left - 10 && mouseX <= left + PANEL_W + 10
				&& mouseY >= LIST_TOP - 8 && mouseY <= listBottom() + 8) {
			int maxScroll = Math.max(0, songs.size() - visibleRows());
			scroll = clamp(scroll - (int) Math.signum(vertical), 0, maxScroll);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
	}

	@Override
	public boolean keyPressed(KeyInput input) {
		if (searchBox != null && searchBox.isFocused()
				&& (input.key() == GLFW.GLFW_KEY_ENTER || input.key() == GLFW.GLFW_KEY_KP_ENTER)) {
			doSearch();
			return true;
		}
		return super.keyPressed(input);
	}

	private static int clamp(int v, int lo, int hi) {
		return Math.max(lo, Math.min(hi, v));
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
