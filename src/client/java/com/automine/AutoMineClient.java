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
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.Items;
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

	@Override
	public void onInitializeClient() {
		CONFIG = AutoMineConfig.loadOrCreate(FabricLoader.getInstance().getConfigDir());
		SELECTION = new Selection();
		ENGINE = new QuarryEngine(MinecraftClient.getInstance(), CONFIG, SELECTION);

		new ClientCommands().register();

		// Xẻng vàng (trái = điểm 1, phải = điểm 2) được xử lý trong
		// MinecraftClientMixin -> markCornerWithShovel, KHÔNG qua Fabric API nữa.
		// Attack/UseBlockCallback có hai cái dở: trả SUCCESS thì Fabric vẫn gửi
		// packet tương tác lên server (đụng claim tool — GriefPrevention cũng dùng
		// đúng xẻng vàng), và trên client tuỳ biến event này không bắn ổn định —
		// chính là vụ "đánh dấu mà không thấy gọi /sel". Mixin chặn ở doAttack /
		// doItemUse: chưa có packet nào kịp rời client.

		// Unbound by default so the user picks the keys in Options -> Controls.
		KeyBinding menuKey = key("key.automine.menu");
		KeyBinding startKey = key("key.automine.start");
		KeyBinding stopKey = key("key.automine.stop");
		KeyBinding pos1Key = key("key.automine.pos1");
		KeyBinding pos2Key = key("key.automine.pos2");

		StatusHud hud = new StatusHud();
		HudElementRegistry.addLast(Identifier.of("automine", "status"),
				(context, tickCounter) -> hud.render(context));
		SelectionRenderer.register();

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
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
				ENGINE.resume();
			}

			ENGINE.tick();
		});
	}

	/** Last corner marked by the shovel, so a held button doesn't spam the chat. */
	private static int lastMarkCorner;
	private static BlockPos lastMarkPos;

	/**
	 * Mark a selection corner because the player clicked while holding a golden
	 * shovel. Called from {@code MinecraftClientMixin} at the head of
	 * {@code doAttack} (which = 1) and {@code doItemUse} (which = 2).
	 *
	 * @return true when the click was ours — the caller then swallows it, so the
	 *         shovel never punches the block and no interact packet reaches the
	 *         server (some servers bind the golden shovel to their own tools).
	 */
	public static boolean markCornerWithShovel(int which) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (CONFIG == null || !CONFIG.goldenShovelMark || mc.player == null) {
			return false;
		}
		if (mc.player.getMainHandStack().getItem() != Items.GOLDEN_SHOVEL) {
			return false;
		}
		if (!(mc.crosshairTarget instanceof BlockHitResult hit)
				|| hit.getType() != HitResult.Type.BLOCK) {
			return false;
		}
		BlockPos pos = hit.getBlockPos();
		// Still swallow a repeat click on the same block, just without re-announcing:
		// a held right mouse button reaches here every tick.
		if (which != lastMarkCorner || !pos.equals(lastMarkPos)) {
			ClientCommands.setCornerAt(which, pos);
			lastMarkCorner = which;
			lastMarkPos = pos.toImmutable();
		}
		return true;
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
