package com.automine.util;

import net.minecraft.client.input.Input;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.Vec2f;

/**
 * Installed as {@code ClientPlayerEntity.input} while AutoMine drives the player,
 * in place of the normal keyboard input. It emits the same simulated key state a
 * real player produces, so movement stays ordinary walking &mdash; no flight, no speed.
 *
 * <p>Sneak is never emitted: the user asked for no crouching, so the flag is not
 * modelled at all rather than left as a switch nothing turns on.
 */
public final class SimInput extends Input {
	private float forward;
	private float strafe;
	private boolean jumping;
	private boolean sprinting;

	public void set(float forward, float strafe, boolean jumping, boolean sprinting) {
		this.forward = clamp(forward);
		this.strafe = clamp(strafe);
		this.jumping = jumping;
		this.sprinting = sprinting;
	}

	public void stop() {
		forward = 0;
		strafe = 0;
		jumping = false;
		sprinting = false;
	}

	@Override
	public void tick() {
		this.playerInput = new PlayerInput(
				forward > 0, forward < 0,
				strafe > 0, strafe < 0,
				jumping, false, sprinting);
		this.movementVector = new Vec2f(strafe, forward).normalize();
	}

	private static float clamp(float v) {
		return Math.max(-1.0F, Math.min(1.0F, v));
	}
}
