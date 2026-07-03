package com.autobuilder.build;

import net.minecraft.client.input.Input;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.Vec2f;

/**
 * Installed as {@code ClientPlayerEntity.input} while the build engine is
 * active, in place of the normal {@code KeyboardInput}. Produces the same
 * kind of simulated key state real keyboard input would (forward/strafe
 * magnitude plus a jump flag), so the rest of the game treats it exactly
 * like a player walking normally - no flight, no speed boost.
 */
public final class AutoBuildInput extends Input {
	private float forward;
	private float strafe;
	private boolean jumping;

	public void set(float forward, float strafe, boolean jumping) {
		this.forward = clamp(forward);
		this.strafe = clamp(strafe);
		this.jumping = jumping;
	}

	public void stop() {
		set(0, 0, false);
	}

	@Override
	public void tick() {
		this.playerInput = new PlayerInput(
				forward > 0, forward < 0,
				strafe > 0, strafe < 0,
				jumping,
				false,
				false
		);
		this.movementVector = new Vec2f(strafe, forward).normalize();
	}

	private static float clamp(float v) {
		return Math.max(-1.0F, Math.min(1.0F, v));
	}
}
