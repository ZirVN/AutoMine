package com.automine.mixin;

import com.automine.AutoMineClient;
import com.automine.util.AutoEat;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets AutoMine actually break blocks &mdash; and actually finish eating.
 *
 * <p>{@code handleBlockBreaking} is the only place mining progress accumulates,
 * and it calls {@code cancelBlockBreaking()} on every tick where the attack
 * button isn't held &mdash; so a mod that merely calls
 * {@code updateBlockBreakingProgress} sees its progress reset each tick and
 * nothing ever breaks. This flips the {@code breaking} flag to true while
 * AutoMine is digging a specific block that the crosshair is already on.
 */
@Mixin(MinecraftClient.class)
public class MinecraftClientMixin {

	@ModifyVariable(method = "handleBlockBreaking", at = @At("HEAD"), argsOnly = true)
	private boolean automine$forceBreaking(boolean breaking) {
		return breaking || AutoMineClient.shouldForceBreaking();
	}

	/**
	 * Keeps a golden apple going down. {@code handleInputEvents} stops any item
	 * use the moment the use key isn't held ({@code if (!useKey.isPressed())
	 * stopUsingItem(...)}), and AutoEat starts eating with {@code interactItem}
	 * rather than a real key press &mdash; so without this the bite was cancelled
	 * one tick after it began, every time, and the mod re-clicked forever.
	 *
	 * <p>Only the use key is affected, and only while AutoEat is genuinely
	 * mid-chew ({@link AutoEat#isHoldingUseKey}); every other key binding in the
	 * method reads exactly as pressed. The {@code doItemUse} re-trigger further
	 * down the method is already guarded by {@code !isUsingItem}, so forcing the
	 * key while chewing cannot start a second bite.
	 */
	@Redirect(method = "handleInputEvents",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/option/KeyBinding;isPressed()Z"))
	private boolean automine$holdUseKeyWhileEating(KeyBinding binding) {
		MinecraftClient self = (MinecraftClient) (Object) this;
		if (binding == self.options.useKey && AutoEat.isHoldingUseKey(self)) {
			return true;
		}
		return binding.isPressed();
	}

	/**
	 * Golden shovel, left click = corner 1. Handled here at the source instead of
	 * through Fabric's {@code AttackBlockCallback}: that event both fired
	 * unreliably on this client and, when answered with SUCCESS, still sent the
	 * attack packet to the server. Cancelling {@code doAttack} outright means the
	 * shovel never punches the block and the server never hears about the click.
	 */
	@Inject(method = "doAttack", at = @At("HEAD"), cancellable = true)
	private void automine$shovelMarksCorner1(CallbackInfoReturnable<Boolean> cir) {
		if (AutoMineClient.markCornerWithShovel(1)) {
			cir.setReturnValue(false);
		}
	}

	/**
	 * Golden shovel, right click = corner 2. Same reasoning as corner 1 — and
	 * doubly important here, because many servers bind their own tools (land
	 * claims, GriefPrevention) to a golden shovel right click; swallowing the
	 * click before any packet leaves keeps the two features from fighting.
	 */
	@Inject(method = "doItemUse", at = @At("HEAD"), cancellable = true)
	private void automine$shovelMarksCorner2(CallbackInfo ci) {
		if (AutoMineClient.markCornerWithShovel(2)) {
			ci.cancel();
		}
	}
}
