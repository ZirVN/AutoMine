package com.automine;

import com.automine.command.ClientCommands;
import com.automine.config.AutoMineConfig;
import com.automine.gui.AutoMineMenuScreen;
import com.automine.mine.QuarryEngine;
import com.automine.mine.Selection;
import com.automine.render.SelectionRenderer;
import com.automine.render.StatusHud;
import com.automine.util.AutoEat;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.glfw.GLFW;

/**
 * AutoMine clears a marked box: {@code /sel 1} and {@code /sel 2} mark two
 * corners, {@code /start} digs it out top-down in layers, taking a 3x3 face at a
 * time and picking the travel axis itself.
 */
@Environment(EnvType.CLIENT)
public final class AutoMineClient implements ClientModInitializer {
	public static AutoMineConfig CONFIG;
	public static Selection SELECTION;
	public static QuarryEngine ENGINE;
	public static com.automine.spotify.SpotifyHudOverlay SPOTIFY;
	/** Phiên bản đang chạy, in kèm lệnh /start — để biết chắc jar nào đang load. */
	public static String VERSION = "dev";

	/** Tên file jar mà class này được nạp từ đó — vũ khí bắt jar lậu trong mods/. */
	private static String jarOf(Class<?> type) {
		try {
			String path = type.getProtectionDomain().getCodeSource().getLocation().getPath();
			int slash = path.lastIndexOf('/');
			return slash >= 0 ? path.substring(slash + 1) : path;
		} catch (Throwable t) {
			return "không rõ (" + t.getClass().getSimpleName() + ")";
		}
	}

	@Override
	public void onInitializeClient() {
		// The mixins live in the jar and can't call this payload class directly,
		// so they call Bridge; register the real implementations here.
		Bridge.forceBreaking = AutoMineClient::shouldForceBreaking;
		Bridge.holdingUseKey = mc -> AutoEat.isHoldingUseKey((MinecraftClient) mc);

		FabricLoader.getInstance().getModContainer("automine").ifPresent(
				container -> VERSION = "v" + container.getMetadata().getVersion().getFriendlyString());

		CONFIG = AutoMineConfig.loadOrCreate(FabricLoader.getInstance().getConfigDir());
		SELECTION = new Selection();
		ENGINE = new QuarryEngine(MinecraftClient.getInstance(), CONFIG, SELECTION);

		new ClientCommands().register();

		// Đánh dấu vùng chỉ còn qua /sel 1, /sel 2 hoặc menu — tính năng "xẻng vàng
		// đánh dấu" đã bỏ hẳn theo yêu cầu (nó nuốt click chuột và đụng claim tool
		// của server, vốn cũng dùng đúng cây xẻng vàng).

		// Unbound by default so the user picks the keys in Options -> Controls.
		KeyBinding menuKey = key("key.automine.menu");
		KeyBinding startKey = key("key.automine.start");
		KeyBinding stopKey = key("key.automine.stop");
		KeyBinding pos1Key = key("key.automine.pos1");
		KeyBinding pos2Key = key("key.automine.pos2");

		StatusHud hud = new StatusHud();
		HudElementRegistry.addLast(Identifier.of("automine", "status"),
				(context, tickCounter) -> hud.render(context));
		// Staff List HUD (góc phải-trên, chỉ hiện khi có staff online).
		HudElementRegistry.addLast(Identifier.of("automine", "staff"),
				(context, tickCounter) -> com.automine.util.StaffGuard.renderHud(context));
		// Thẻ "đang phát" Spotify — port từ SpotifyHud 1.21.4, chạy thuần Fabric.
		SPOTIFY = new com.automine.spotify.SpotifyHudOverlay();
		HudElementRegistry.addLast(Identifier.of("automine", "spotify"),
				(context, tickCounter) -> SPOTIFY.render(context));
		SelectionRenderer.register();

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			// (Khối tự khai jar khi vào thế giới đã GỠ theo lệnh user 2026-08-20 —
			// nhiệm vụ chẩn đoán vụ "Failed to load class" đã xong. Muốn kiểm bản
			// đang chạy: dòng /start vẫn in "· v..." như cũ.)
			// Staff List + Auto Sign: chạy đầu tick — staff online là tạm dừng máy đào ngay.
			com.automine.util.StaffGuard.tick(client);
			// Dính nước/dung nham giữa lúc đào → cảnh báo Discord (webhook + ping id).
			com.automine.util.FluidAlert.tick(client);
			// Phím tắt xử lý TRƯỚC auto-eat, để nút Stop vẫn ăn được cả lúc đang nhai.
			while (menuKey.wasPressed()) {
				if (client.player != null) {
					client.setScreen(new AutoMineMenuScreen(null));
				}
			}
			while (pos1Key.wasPressed()) {
				if (client.player != null) {
					SELECTION.setPos1(client.player.getBlockPos());
					say(client, "điểm 1 = " + client.player.getBlockPos().toShortString());
				}
			}
			while (pos2Key.wasPressed()) {
				if (client.player != null) {
					SELECTION.setPos2(client.player.getBlockPos());
					say(client, "điểm 2 = " + client.player.getBlockPos().toShortString());
				}
			}
			while (startKey.wasPressed()) {
				String error = ENGINE.start();
				say(client, error != null ? "§c" + error : "bắt đầu đào");
			}
			while (stopKey.wasPressed()) {
				ENGINE.stop(); // stop() tự reset AutoEat
				say(client, "đã dừng");
			}

			// Tự ăn táo vàng: tạm dừng trong lúc nhai, và chỉ tự chạy tiếp khi chính
			// auto-eat là thứ đã tạm dừng — /pause của người dùng thì giữ nguyên.
			// (Bản cũ resume mọi PAUSED, nên /pause tay bị hủy ngay tick sau.)
			if (ENGINE.state() == QuarryEngine.State.RUNNING) {
				if (AutoEat.checkAndEat(client)) {
					ENGINE.pauseForEating();
				}
			} else if (ENGINE.state() == QuarryEngine.State.PAUSED && ENGINE.isAutoPaused()
					&& !AutoEat.checkAndEat(client)) {
				// resumeFromEating, KHÔNG phải resume(): resume() dọn sạch trạng
				// thái đang làm dở (sổ ô đã đào, ba nhát đầu dãy, hai nhát tụt
				// tầng) — hợp lý cho /resume của người dùng, nhưng ở đây chỉ là
				// ngắt hai giây để nhai táo.
				ENGINE.resumeFromEating();
			}

			ENGINE.tick();
		});

		// Thoát game (đóng có trật tự) → đóng luôn cửa sổ YouTube overlay để không còn tiến trình
		// Chrome mồ côi. YoutubeScreen còn thêm shutdown hook JVM làm lưới đỡ cho các đường thoát khác.
		ClientLifecycleEvents.CLIENT_STOPPING.register(client ->
				com.automine.gui.YoutubeScreen.onGameStopping());
	}

	/**
	 * Whether vanilla should treat the attack button as held this tick. Gated on
	 * the crosshair already resting on the exact block AutoMine wants gone, so it
	 * can never break anything outside the marked box. Read by
	 * {@code MinecraftClientMixin}.
	 */
	public static boolean shouldForceBreaking() {
		QuarryEngine engine = ENGINE;
		if (engine == null || engine.state() != QuarryEngine.State.RUNNING) {
			return false;
		}
		BlockPos target = engine.breakingTarget();
		if (target == null) {
			return false;
		}
		HitResult hit = MinecraftClient.getInstance().crosshairTarget;
		// Type check matters: a MISS is still a BlockHitResult, with the block pos of
		// wherever the ray petered out — never break on anything but a real hit.
		return hit instanceof BlockHitResult blockHit
				&& blockHit.getType() == HitResult.Type.BLOCK
				&& blockHit.getBlockPos().equals(target);
	}

	private static KeyBinding key(String translationKey) {
		return KeyBindingHelper.registerKeyBinding(new KeyBinding(
				translationKey, InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, KeyBinding.Category.MISC));
	}

	private static void say(MinecraftClient client, String text) {
		if (client.player != null) {
			client.player.sendMessage(Text.literal("§b[AutoMine] §r" + text), false);
		}
	}
}
