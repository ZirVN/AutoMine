package com.autobuilder.build;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * Walks the player straight toward a horizontal target using simulated key
 * input (normal walk speed, no flight/noclip). No obstacle avoidance: it
 * heads directly at the target and only auto-jumps over a single block
 * directly in the way. If it stops making progress for a while it reports
 * STUCK so the engine can skip that block instead of freezing.
 */
public final class MovementController {
	private static final double PROBE_DISTANCE = 0.75;
	private static final int MAX_STUCK_TICKS = 40;
	private static final double PROGRESS_EPSILON = 0.02;

	public enum Result {MOVING, ARRIVED, STUCK}

	private final ClientPlayerEntity player;
	private final AutoBuildInput input;
	private int stuckTicks;
	private double lastDistSq = Double.MAX_VALUE;

	public MovementController(ClientPlayerEntity player, AutoBuildInput input) {
		this.player = player;
		this.input = input;
	}

	public Result moveToward(Vec3d target, double arriveDistance) {
		double dx = target.x - player.getX();
		double dz = target.z - player.getZ();
		double horizontalDistSq = dx * dx + dz * dz;

		if (horizontalDistSq <= arriveDistance * arriveDistance) {
			input.stop();
			resetStuck();
			return Result.ARRIVED;
		}

		// Stuck detection: if we're not getting meaningfully closer, count down.
		if (lastDistSq - horizontalDistSq < PROGRESS_EPSILON) {
			stuckTicks++;
			if (stuckTicks > MAX_STUCK_TICKS) {
				input.stop();
				resetStuck();
				return Result.STUCK;
			}
		} else {
			stuckTicks = 0;
		}
		lastDistSq = horizontalDistSq;

		double targetAngle = Math.atan2(-dx, dz);
		boolean shouldJump = shouldAutoJump(player.getEntityWorld(), targetAngle);

		double playerYawRad = Math.toRadians(player.getYaw());
		double diff = wrapRadians(targetAngle - playerYawRad);
		float forward = (float) Math.cos(diff);
		float strafe = (float) Math.sin(diff);
		input.set(forward, strafe, shouldJump);
		return Result.MOVING;
	}

	public void stop() {
		input.stop();
		resetStuck();
	}

	private void resetStuck() {
		stuckTicks = 0;
		lastDistSq = Double.MAX_VALUE;
	}

	/** Jump only when a single block blocks the path directly ahead and there's room to step up. */
	private boolean shouldAutoJump(World world, double angle) {
		BlockPos feet = probePos(angle);
		BlockPos head = feet.up();
		BlockPos aboveHead = head.up();
		return !world.getBlockState(feet).isAir()
				&& world.getBlockState(head).isAir()
				&& world.getBlockState(aboveHead).isAir();
	}

	private BlockPos probePos(double angle) {
		double dirX = -Math.sin(angle);
		double dirZ = Math.cos(angle);
		double x = player.getX() + dirX * PROBE_DISTANCE;
		double z = player.getZ() + dirZ * PROBE_DISTANCE;
		return BlockPos.ofFloored(x, player.getY() + 0.1, z);
	}

	private static double wrapRadians(double angle) {
		double twoPi = Math.PI * 2;
		angle = angle % twoPi;
		if (angle >= Math.PI) angle -= twoPi;
		if (angle < -Math.PI) angle += twoPi;
		return angle;
	}
}
