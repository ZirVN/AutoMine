package com.automine.util;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/** Turns the player's view toward a point, capped per tick so it doesn't snap. */
public final class Rotations {
	private Rotations() {
	}

	/** Yaw in degrees for a horizontal delta. */
	public static float yawTo(double dx, double dz) {
		return (float) Math.toDegrees(Math.atan2(-dx, dz));
	}

	/** @return true once the player is looking closely enough at {@code target}. */
	public static boolean turnTo(ClientPlayerEntity player, Vec3d target, float maxDegreesPerTick) {
		Vec3d eye = player.getEyePos();
		double dx = target.x - eye.x;
		double dy = target.y - eye.y;
		double dz = target.z - eye.z;
		double horizontal = Math.sqrt(dx * dx + dz * dz);

		float desiredYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
		float desiredPitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));

		float yawDiff = MathHelper.wrapDegrees(desiredYaw - player.getYaw());
		float pitchDiff = desiredPitch - player.getPitch();

		player.setYaw(player.getYaw() + MathHelper.clamp(yawDiff, -maxDegreesPerTick, maxDegreesPerTick));
		player.setPitch(MathHelper.clamp(
				player.getPitch() + MathHelper.clamp(pitchDiff, -maxDegreesPerTick, maxDegreesPerTick),
				-90.0F, 90.0F));

		return Math.abs(yawDiff) < 2.5F && Math.abs(pitchDiff) < 2.5F;
	}
}
