package com.autobuilder.build;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/** Smoothly turns the player's look direction toward a point, a few degrees per tick rather than snapping. */
public final class FacingUtil {
	private FacingUtil() {
	}

	/** @return true once the player is looking closely enough at {@code target}. */
	public static boolean turnToward(ClientPlayerEntity player, Vec3d target, float maxDegreesPerTick) {
		Vec3d eyePos = player.getEyePos();
		double dx = target.x - eyePos.x;
		double dy = target.y - eyePos.y;
		double dz = target.z - eyePos.z;
		double horizontalDistance = Math.sqrt(dx * dx + dz * dz);

		float desiredYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
		float desiredPitch = (float) -Math.toDegrees(Math.atan2(dy, horizontalDistance));

		float yawDiff = MathHelper.wrapDegrees(desiredYaw - player.getYaw());
		float pitchDiff = desiredPitch - player.getPitch();

		float yawStep = MathHelper.clamp(yawDiff, -maxDegreesPerTick, maxDegreesPerTick);
		float pitchStep = MathHelper.clamp(pitchDiff, -maxDegreesPerTick, maxDegreesPerTick);

		player.setYaw(player.getYaw() + yawStep);
		player.setPitch(MathHelper.clamp(player.getPitch() + pitchStep, -90.0F, 90.0F));

		return Math.abs(yawDiff) < 1.0F && Math.abs(pitchDiff) < 1.0F;
	}

	/** Whether the given hit result is a right-click-ready hit on the specific block face. */
	public static boolean isLookingAt(HitResult hitResult, BlockPos pos, Direction face) {
		return hitResult != null
				&& hitResult.getType() == HitResult.Type.BLOCK
				&& hitResult instanceof BlockHitResult blockHit
				&& blockHit.getBlockPos().equals(pos)
				&& blockHit.getSide() == face;
	}
}
