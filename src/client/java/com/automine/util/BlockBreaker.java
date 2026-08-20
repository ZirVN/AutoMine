package com.automine.util;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

/**
 * Aims at the block we want gone and picks the right tool. The breaking itself
 * is left to vanilla: {@code MinecraftClient.handleBlockBreaking} is the only
 * thing that can accumulate mining progress, because it calls
 * {@code cancelBlockBreaking()} every tick the attack key isn't held &mdash; which
 * would wipe any progress we tried to build up ourselves. {@code
 * MinecraftClientMixin} makes that method believe the button is held, but only
 * while {@link #aiming()} names a block the crosshair is already on.
 *
 * <p>The aim point is <b>locked in</b> once found. Recomputing "best face" every
 * tick made the aim flip between two faces as the player drifted, so the
 * crosshair never settled and nothing ever broke.
 *
 * <p>Aiming and breaking are <b>two separate steps</b>. {@link #aimOnly} turns the
 * view and nothing else; only {@link #tickArmed} takes out the pickaxe and names a
 * block for the mixin to break. The engine does not arm until the live crosshair
 * genuinely rests on the cell it wants, so the tool is never swapped in on a
 * crooked aim.
 */
public final class BlockBreaker {
	/**
	 * Tốc độ đưa mắt tới điểm ngắm, độ mỗi tick.
	 *
	 * <p>Nâng 22 → 32: mỗi lần đổi mục tiêu, số tick chỉ để quay đầu giảm gần
	 * một phần ba, mà 32°/tick vẫn là cú vẩy chuột người thật làm được (một vòng
	 * 180° mất ~6 tick), không phải kiểu snap tức thì của bot.
	 */
	private static final float TURN_DEGREES_PER_TICK = 32.0F;

	/**
	 * Mắt xê dịch dưới ngần này (bình phương, block/tick) thì coi là đứng yên.
	 *
	 * <p>0.01 block/tick — đi bộ thường ~0.13, rơi còn nhanh hơn nhiều, nên chỉ
	 * lúc thật sự đứng chôn chân mới lọt qua.
	 */
	private static final double EYE_STILL_EPS_SQ = 1.0E-4;

	private final MinecraftClient client;
	private BlockPos aiming;
	private Vec3d aimPoint;
	/** Vị trí mắt tick trước — xem {@link #onTarget}. */
	private double lastEyeX = Double.NaN;
	private double lastEyeY;
	private double lastEyeZ;
	/**
	 * The aim point {@link #aimOnly} settled on, before the block is armed.
	 * {@link #tickArmed} adopts it verbatim.
	 *
	 * <p>This exists because {@link #resolveAim}'s cache is keyed on {@link #aiming},
	 * which {@code aimOnly} deliberately leaves null. Without somewhere else to keep
	 * the point, every {@code aimOnly} tick would re-resolve it, and the point would
	 * then shift again the moment the block was armed — so the crosshair would be
	 * chasing a target that moved underneath it and the lock could never be confirmed.
	 */
	private BlockPos pendingPos;
	private Vec3d pendingPoint;

	public BlockBreaker(MinecraftClient client) {
		this.client = client;
	}

	/**
	 * Turn toward {@code pos} without committing to break it: no tool is selected and
	 * {@link #aiming()} stays null, so {@code MinecraftClientMixin} cannot force a
	 * swing. Used while lining the crosshair up on a cell.
	 *
	 * <p>The turn keeps going until the crosshair is genuinely ON the block, and then
	 * <b>stops</b>. It may not be gated on anything weaker than that — the engine reads
	 * the live crosshair to decide when the aim has landed, and that crosshair is only
	 * updated by the game <em>after</em> a rotation is applied, so refusing to turn while
	 * merely "close" would be a condition that can never come true. Once the ray really
	 * does land on the block there is nothing left to correct, and every further degree
	 * is pure head-twitching ({@link #onTarget}).
	 *
	 * @return false when no point on the block can be hit from here (the caller should
	 *         reposition or build), true while lining up.
	 */
	public boolean aimOnly(BlockPos pos, double reach) {
		ClientPlayerEntity player = client.player;
		World world = client.world;
		if (player == null || world == null) {
			return false;
		}
		if (world.getBlockState(pos).isAir()) {
			return false;
		}

		Vec3d point = pos.equals(pendingPos) && pendingPoint != null
				&& hits(player, pos, pendingPoint, reach)
						? pendingPoint
						: findAim(player, pos, reach);
		if (point == null) {
			return false;
		}
		pendingPos = pos.toImmutable();
		pendingPoint = point;

		if (!onTarget(pos)) {
			Rotations.turnTo(player, point, TURN_DEGREES_PER_TICK);
		}
		return true;
	}

	/**
	 * Được phép BUÔNG TAY khỏi góc nhìn tick này chưa: tia ngắm thật đang nằm đúng
	 * trên {@code pos} <b>và</b> con mắt đang đứng yên.
	 *
	 * <p>Vế đầu là để ngừng rung đầu: ngắm trúng rồi mà tick nào cũng gọi
	 * {@link Rotations#turnTo} thì đầu cứ nhích qua nhích lại suốt cả lượt đập —
	 * với block cứng là hàng trăm tick liên tiếp, thứ người chơi thật không bao
	 * giờ làm.
	 *
	 * <p>Vế thứ hai là vế BẮT BUỘC phải có, và thiếu nó thì bản trước tự bắn vào
	 * chân mình. Trong lúc {@code pressIntoTarget} ghì thân tiến vào bức tường
	 * đang đục (hoặc lúc canh tâm nhích ngang), mắt vẫn trôi mỗi tick. Góc nhìn
	 * đứng im + mắt trôi = tia quét dần trên mặt block, và tới lúc nó trượt sang
	 * ô bên cạnh thì {@code Bridge.shouldForceBreaking} tắt, vanilla gọi
	 * {@code cancelBlockBreaking} và TOÀN BỘ tiến độ đập bay sạch — cứ thế lặp,
	 * block cứng không bao giờ vỡ. Ngắm lại theo mắt mỗi tick trong lúc thân
	 * chuyển động chính là vòng kín giữ crosshair dính chặt vào block; chỉ khi
	 * thân thật sự đứng yên thì mới có cái để mà buông.
	 */
	private boolean onTarget(BlockPos pos) {
		ClientPlayerEntity player = client.player;
		if (player == null) {
			return false;
		}
		Vec3d eye = player.getEyePos();
		boolean first = Double.isNaN(lastEyeX);
		double dx = eye.x - lastEyeX;
		double dy = eye.y - lastEyeY;
		double dz = eye.z - lastEyeZ;
		lastEyeX = eye.x;
		lastEyeY = eye.y;
		lastEyeZ = eye.z;
		if (first || dx * dx + dy * dy + dz * dz > EYE_STILL_EPS_SQ) {
			return false; // thân còn đang trôi — phải bám tia theo mắt
		}
		return client.crosshairTarget instanceof BlockHitResult hit
				&& hit.getType() == HitResult.Type.BLOCK
				&& hit.getBlockPos().equals(pos);
	}

	/**
	 * Aim at {@code pos} and hold the right tool, ready for vanilla to break it. Call
	 * only once the crosshair is confirmed to be on {@code pos} — this is the step that
	 * puts the pickaxe in hand and lets the mixin swing.
	 *
	 * @return false if there's no clear line to the block from here (the caller
	 *         should move), true while we're working on it.
	 */
	public boolean tickArmed(BlockPos pos, double reach) {
		ClientPlayerEntity player = client.player;
		World world = client.world;
		if (player == null || world == null || client.interactionManager == null) {
			return false;
		}

		BlockState state = world.getBlockState(pos);
		if (state.isAir()) {
			cancel();
			return false;
		}

		Vec3d point = resolveAim(player, pos, reach);
		if (point == null) {
			// Nothing on this block can be hit from here.
			if (!pos.equals(aiming)) {
				cancel();
			}
			return false;
		}

		if (!pos.equals(aiming)) {
			client.interactionManager.cancelBlockBreaking();
			aiming = pos.toImmutable();
		}
		aimPoint = point;

		ToolSelector.selectBest(player, state);
		// Đang bổ mà crosshair vẫn dính block thì KHÔNG quay nữa: giữ đầu bất động
		// suốt lượt đập, vừa giống người chơi vừa khỏi rủi ro tia trượt khỏi block
		// làm vanilla xoá tiến độ đập.
		if (!onTarget(pos)) {
			Rotations.turnTo(player, point, TURN_DEGREES_PER_TICK);
		}
		return true;
	}

	/** Whether a clear line exists to {@code pos}, without changing what we're aiming at. */
	public boolean canReach(BlockPos pos, double reach) {
		ClientPlayerEntity player = client.player;
		return player != null && resolveAim(player, pos, reach) != null;
	}

	/**
	 * Whether {@code pos} could be hit <b>from a stance we are not standing in yet</b> —
	 * used to vet a candidate spot before walking to it, so the player doesn't cross the
	 * quarry only to find the block invisible from there.
	 *
	 * <p>The eye is modelled as the standing cell's centre raised by the player's eye
	 * height, and the ray is cast with the player excluded, exactly as
	 * {@link #hits} does for the live stance.
	 */
	public boolean canReachFrom(BlockPos stand, BlockPos pos, double reach) {
		ClientPlayerEntity player = client.player;
		World world = client.world;
		if (player == null || world == null) {
			return false;
		}
		Vec3d eye = new Vec3d(stand.getX() + 0.5, stand.getY() + player.getStandingEyeHeight(),
				stand.getZ() + 0.5);
		Vec3d center = Vec3d.ofCenter(pos);
		if (eye.squaredDistanceTo(center) > reach * reach) {
			return false;
		}
		BlockHitResult hit = world.raycast(new RaycastContext(
				eye, center, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, player));
		return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos);
	}

	/** Keep the cached aim point while it still works; otherwise hunt for a new one. */
	private Vec3d resolveAim(ClientPlayerEntity player, BlockPos pos, double reach) {
		if (pos.equals(aiming) && aimPoint != null && hits(player, pos, aimPoint, reach)) {
			return aimPoint;
		}
		// Adopt whatever aimOnly lined the crosshair up on. Arming must not move the
		// target out from under a crosshair that has just been confirmed to be on it.
		if (pos.equals(pendingPos) && pendingPoint != null && hits(player, pos, pendingPoint, reach)) {
			return pendingPoint;
		}
		return findAim(player, pos, reach);
	}

	/** Hunt for a hittable point on {@code pos}, ignoring every cache. */
	private Vec3d findAim(ClientPlayerEntity player, BlockPos pos, double reach) {
		// The block centre is the most forgiving target: any ray that gets there
		// lands on this block. Fall back to face centres, nearest facing first.
		Vec3d center = Vec3d.ofCenter(pos);
		if (hits(player, pos, center, reach)) {
			return center;
		}
		for (Direction dir : facesNearestFirst(player.getEyePos(), center)) {
			Vec3d point = BlockUtil.faceCenter(pos, dir);
			if (hits(player, pos, point, reach)) {
				return point;
			}
		}
		return null;
	}

	/** The six faces ordered by how directly they point at the eye. */
	private static Direction[] facesNearestFirst(Vec3d eye, Vec3d center) {
		Direction[] dirs = Direction.values().clone();
		double[] dots = new double[dirs.length];
		for (int i = 0; i < dirs.length; i++) {
			dots[i] = dirs[i].getOffsetX() * (eye.x - center.x)
					+ dirs[i].getOffsetY() * (eye.y - center.y)
					+ dirs[i].getOffsetZ() * (eye.z - center.z);
		}
		for (int i = 1; i < dirs.length; i++) { // insertion sort, descending
			Direction dir = dirs[i];
			double dot = dots[i];
			int j = i - 1;
			while (j >= 0 && dots[j] < dot) {
				dirs[j + 1] = dirs[j];
				dots[j + 1] = dots[j];
				j--;
			}
			dirs[j + 1] = dir;
			dots[j + 1] = dot;
		}
		return dirs;
	}

	private boolean hits(ClientPlayerEntity player, BlockPos pos, Vec3d point, double reach) {
		Vec3d eye = player.getEyePos();
		if (eye.squaredDistanceTo(point) > reach * reach) {
			return false;
		}
		BlockHitResult hit = player.getEntityWorld().raycast(new RaycastContext(
				eye, point, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, player));
		return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos);
	}

	/**
	 * Stop asking the mixin to break anything, but keep the aim point we settled on.
	 *
	 * <p>The difference from {@link #cancel()} is the aim cache. {@code cancel()} throws
	 * away {@code pendingPoint} as well, so the next attempt re-resolves which face to
	 * aim at and may well pick a different one — the crosshair then chases a target that
	 * moved, and the lock can never be confirmed. {@code disarm()} leaves that point
	 * alone, so re-aiming continues toward the same spot.
	 *
	 * <p>Note this does <em>not</em> preserve vanilla's mining progress: with
	 * {@link #aiming()} null the mixin stops forcing the attack button, and
	 * {@code handleBlockBreaking} then cancels the break itself. Keeping progress is
	 * precisely why the aim point must stay stable — so the crosshair drifts off target
	 * as rarely as possible in the first place.
	 */
	public void disarm() {
		aiming = null;
		aimPoint = null;
	}

	public void cancel() {
		aiming = null;
		aimPoint = null;
		pendingPos = null;
		pendingPoint = null;
		if (client.interactionManager != null) {
			client.interactionManager.cancelBlockBreaking();
		}
	}

	/** The block we want vanilla to break right now, or null. */
	public BlockPos aiming() {
		return aiming;
	}
}
