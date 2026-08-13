package com.automine.util;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * Towers the player upwards the way a person does it: look down, jump, and drop
 * a block underneath at the top of the hop.
 *
 * <p>Deliberately paced. An earlier version jumped and called
 * {@code interactBlock} on <em>every</em> tick, which spammed the server with
 * placements and re-jumps that tripped over each other, so the climb never got
 * anywhere. Each hop now runs through {@link Phase}: jump once, place once (with
 * a retry gap), wait to land, settle, then hop again.
 *
 * <p>The click itself goes through the <b>live crosshair</b>, never a synthetic
 * hit — see {@link #placeUnder}. That is the difference between a block appearing
 * and the server quietly ignoring it.
 */
public final class BlockPlacer {
	/** Gap between placement attempts, so we don't machine-gun the server. */
	private static final int PLACE_RETRY_TICKS = 2;
	/** Pause after landing before starting the next hop. */
	private static final int SETTLE_TICKS = 5;
	/**
	 * How fast to tip the view down onto the support block. Faster than the
	 * breaker's turn: the jump apex window is only a few ticks long.
	 */
	private static final float TURN_DEGREES_PER_TICK = 45.0F;
	/** Don't hop until the view is at least this far down — the click needs it. */
	private static final float MIN_PITCH_TO_JUMP = 60.0F;

	private enum Phase {READY, RISING, PLACED, SETTLE}

	private final MinecraftClient client;
	private Phase phase = Phase.READY;
	private BlockPos base;
	private int timer;

	public BlockPlacer(MinecraftClient client) {
		this.client = client;
	}

	public void reset() {
		phase = Phase.READY;
		base = null;
		timer = 0;
	}

	/** @return the hotbar slot holding a full solid block, or -1 if there is none. */
	public static int findBuildingBlock(ClientPlayerEntity player) {
		PlayerInventory inventory = player.getInventory();
		for (int slot = 0; slot < PlayerInventory.HOTBAR_SIZE; slot++) {
			ItemStack stack = inventory.getStack(slot);
			if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
				continue;
			}
			if (blockItem.getBlock().getDefaultState().isOpaqueFullCube()) {
				return slot;
			}
		}
		return -1;
	}

	public static boolean hasBuildingBlock(ClientPlayerEntity player) {
		return findBuildingBlock(player) >= 0;
	}

	/** Whether {@code pos} touches at least one solid full cube to place against. */
	public static boolean hasSolidNeighbor(World world, BlockPos pos) {
		for (Direction dir : Direction.values()) {
			BlockState state = world.getBlockState(pos.offset(dir));
			if (state.isOpaqueFullCube() && state.getFluidState().isEmpty()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Fill one cell — water, lava or air — by clicking a solid neighbour's face
	 * through the live crosshair, the way a person plugs a hole. Used to seal
	 * fluids before and during the dig.
	 *
	 * <p>The support face is chosen for <b>visibility</b>, not by a fixed order:
	 * a face pointing away from the eye can never carry the crosshair, and aiming
	 * at one used to be a silent way to stand there turning forever.
	 *
	 * @return true while working on it (turning, pacing between clicks); false
	 *         when nothing can be done from here — no block in the hotbar, no
	 *         visible support in reach — and the caller should move or give up.
	 */
	public boolean fillCell(ClientPlayerEntity player, BlockPos target, double reach) {
		World world = client.world;
		if (world == null || client.interactionManager == null) {
			return false;
		}
		if (!world.getBlockState(target).isReplaceable()) {
			return false; // already filled (or a waterlogged block — that one gets dug)
		}
		int slot = findBuildingBlock(player);
		if (slot < 0) {
			return false;
		}
		Vec3d aim = visibleSupportAim(world, player, target, reach);
		if (aim == null) {
			return false;
		}

		PlayerInventory inventory = player.getInventory();
		if (inventory.getSelectedSlot() != slot) {
			inventory.setSelectedSlot(slot);
		}
		if (timer > 0) {
			timer--;
			return true;
		}
		Rotations.turnTo(player, aim, TURN_DEGREES_PER_TICK);
		if (placeUnder(world, target)) {
			timer = PLACE_RETRY_TICKS;
		}
		return true;
	}

	/**
	 * The most eye-facing solid-neighbour face that would grow a block into
	 * {@code target}, or null when no face both faces the player and is in reach.
	 */
	private static Vec3d visibleSupportAim(World world, ClientPlayerEntity player, BlockPos target, double reach) {
		Vec3d eye = player.getEyePos();
		Vec3d best = null;
		double bestDot = 0.0; // must genuinely face the eye, not just tie at 90°
		for (Direction dir : Direction.values()) {
			BlockPos support = target.offset(dir);
			BlockState state = world.getBlockState(support);
			if (!state.isOpaqueFullCube() || !state.getFluidState().isEmpty()) {
				continue;
			}
			Direction face = dir.getOpposite(); // the support's face pointing into target
			Vec3d point = BlockUtil.faceCenter(support, face);
			if (eye.squaredDistanceTo(point) > reach * reach) {
				continue;
			}
			double dot = face.getOffsetX() * (eye.x - point.x)
					+ face.getOffsetY() * (eye.y - point.y)
					+ face.getOffsetZ() * (eye.z - point.z);
			if (dot > bestDot) {
				bestDot = dot;
				best = point;
			}
		}
		return best;
	}

	/**
	 * Advance one tick of towering up.
	 *
	 * @param jump set to true by this method on the tick the player should hop.
	 * @return false when there is nothing to build with.
	 */
	public boolean tick(ClientPlayerEntity player, boolean[] jump) {
		World world = client.world;
		if (world == null || client.interactionManager == null) {
			return false;
		}
		int slot = findBuildingBlock(player);
		if (slot < 0) {
			return false;
		}
		PlayerInventory inventory = player.getInventory();
		if (inventory.getSelectedSlot() != slot) {
			inventory.setSelectedSlot(slot);
		}

		// Looking down while placing is NOT optional. placeUnder() clicks through the
		// live crosshair — the honest way servers accept — so the crosshair must be on
		// the support block before any click can go out. A previous version removed
		// the pitch here "to stop the head dipping" and thereby broke every placement:
		// the view stayed level, the crosshair never touched the floor, and the bot
		// hopped forever without a single block going down. The dip belongs here; the
		// engine levels the view back onto the face as soon as the climb ends.

		if (timer > 0) {
			timer--;
		}

		switch (phase) {
			case READY -> {
				// Aim first, jump second: by the time we leave the ground the
				// crosshair is already resting on the block we'll place against.
				aimStraightDown(player);
				if (player.isOnGround() && timer == 0 && player.getPitch() >= MIN_PITCH_TO_JUMP) {
					base = player.getBlockPos();
					jump[0] = true;
					phase = Phase.RISING;
				}
			}
			case RISING -> {
				if (base == null) {
					phase = Phase.READY;
					break;
				}
				// Follow the body: fill the launch-level cell in the column we are over
				// *now*, not the one we jumped from. Any drift during the hop used to
				// leave the crosshair over a different column than the fixed target, so
				// the placement test never matched and the hop repeated forever — the
				// "nhảy mãi mà không bắc được block".
				BlockPos step = new BlockPos(player.getBlockX(), base.getY(), player.getBlockZ());
				aimStraightDown(player);
				if (!world.getBlockState(step).isAir()) {
					phase = Phase.PLACED; // there's something to land on
				} else if (player.isOnGround()) {
					// Back down without getting one in; pause, then try again.
					phase = Phase.READY;
					timer = SETTLE_TICKS;
				} else if (timer == 0 && clearOf(player, step) && placeUnder(world, step)) {
					timer = PLACE_RETRY_TICKS;
				}
			}
			case PLACED -> {
				if (player.isOnGround()) {
					phase = Phase.SETTLE;
					timer = SETTLE_TICKS;
				}
			}
			case SETTLE -> {
				if (timer == 0) {
					phase = Phase.READY;
					base = null;
				}
			}
		}
		return true;
	}

	/**
	 * Tip the view straight down — <b>pitch only, yaw untouched</b>. The support
	 * block sits directly under the body, so pitch alone puts the crosshair on it.
	 * Steering yaw toward a point underfoot is what caused the "xoay liên tục":
	 * the horizontal offset to a block you are standing on is millimetres of
	 * noise, so the computed yaw was a new random direction every tick and the
	 * player spun on the spot — conspicuous to anti-bot on top of being useless.
	 */
	private static void aimStraightDown(ClientPlayerEntity player) {
		player.setPitch(Math.min(90.0F, player.getPitch() + TURN_DEGREES_PER_TICK));
	}

	/**
	 * Whether the player's body is clear of {@code target}, so a block can legally
	 * appear there. Uses the real bounding box, which is what the server itself
	 * checks before accepting the placement.
	 *
	 * <p>The tolerance matters: a vanilla jump peaks around 1.25 blocks, so demanding
	 * the feet be fully at or above the cell's ceiling left almost no window and the
	 * hop kept landing without a block placed. A small epsilon is enough — the server
	 * only rejects a real overlap.
	 */
	private static boolean clearOf(ClientPlayerEntity player, BlockPos target) {
		return player.getBoundingBox().minY >= target.getY() + 1.0 - 0.02;
	}

	/**
	 * Put a block into {@code target} by right-clicking, exactly the way a person
	 * does: through whatever the crosshair is <b>really</b> on.
	 *
	 * <p>This used to hand {@code interactBlock} a hand-built {@link BlockHitResult}
	 * aimed at the top face of the block below. The client would show a block and
	 * the server would silently drop the packet — a ghost — so the hop kept retrying
	 * and never got anywhere: the "đặt không được, cứ spam hoài". Servers accept the
	 * placement when it comes from the live look direction, so we use
	 * {@code crosshairTarget} and only click when the block would land in the cell we
	 * actually want.
	 *
	 * @return true if a click was sent, so the caller waits before trying again.
	 *         False means we never clicked (nothing lined up yet) — cheap to retry,
	 *         since no packet left the client.
	 */
	private boolean placeUnder(World world, BlockPos target) {
		if (!(client.crosshairTarget instanceof BlockHitResult hit)
				|| hit.getType() != HitResult.Type.BLOCK) {
			return false; // looking at nothing solid — nowhere to click yet
		}
		// The block appears against the face we hit; make sure that's our cell.
		if (!hit.getBlockPos().offset(hit.getSide()).equals(target)) {
			return false;
		}
		BlockState supportState = world.getBlockState(hit.getBlockPos());
		if (supportState.isAir() || !supportState.getFluidState().isEmpty()) {
			return false; // nothing solid to place against
		}

		ActionResult result = client.interactionManager.interactBlock(
				client.player, Hand.MAIN_HAND, hit);
		if (result instanceof ActionResult.Success success
				&& success.swingSource() == ActionResult.SwingSource.CLIENT) {
			client.player.swingHand(Hand.MAIN_HAND);
		}
		// A click went out either way — pace the next one even if this was refused,
		// or a rejected placement would go straight back to machine-gunning.
		return true;
	}
}
