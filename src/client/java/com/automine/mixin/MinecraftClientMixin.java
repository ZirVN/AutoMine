package com.automine.mixin;

import com.automine.Bridge;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;

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
		return breaking || Bridge.shouldForceBreaking();
	}

	/**
	 * Keeps a golden apple going down. {@code handleInputEvents} stops any item
	 * use the moment the use key isn't held ({@code if (!useKey.isPressed())
	 * stopUsingItem(...)}), and AutoEat starts eating with {@code interactItem}
	 * rather than a real key press &mdash; so without this the bite was cancelled
	 * one tick after it began, every time, and the mod re-clicked forever.
	 *
	 * <p>Only the use key is affected, and only while AutoEat is genuinely
	 * mid-chew (via {@code Bridge.isHoldingUseKey}); every other key binding in the
	 * method reads exactly as pressed. The {@code doItemUse} re-trigger further
	 * down the method is already guarded by {@code !isUsingItem}, so forcing the
	 * key while chewing cannot start a second bite.
	 */
	@Redirect(method = "handleInputEvents",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/option/KeyBinding;isPressed()Z"))
	private boolean automine$holdUseKeyWhileEating(KeyBinding binding) {
		MinecraftClient self = (MinecraftClient) (Object) this;
		if (binding == self.options.useKey && Bridge.isHoldingUseKey(self)) {
			return true;
		}
		return binding.isPressed();
	}

}
