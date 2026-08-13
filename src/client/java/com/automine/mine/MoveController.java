package com.automine.mine;

import com.automine.config.AutoMineConfig;
import com.automine.util.BlockBreaker;
import com.automine.util.BlockPlacer;
import com.automine.util.BlockUtil;
import com.automine.util.Rotations;
import com.automine.util.SimInput;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.List;

/**
 * Gets the player to a feet position. It first asks {@link LocalPath} for a
 * route it can simply walk — round a corner, up a step, down a drop — and
 * follows that. Only when no open route exists does it bore its own way there,
 * one axis at a time, and tower up when it has fallen somewhere it can't climb
 * out of. It never breaks anything outside the selection.
 *
 * <p>It never sneaks. Approaching the final block it eases onto the centre with the
 * movement keys alone, resolved against the view it is already holding, so the aim
 * stays on the block about to be dug.
 */
public final class MoveController {
	public enum Result {MOVING, ARRIVED, BLOCKED}

	/**
	 * How close to the centre of the destination counts as arrived. Tight on
	 * purpose: every face should be dug from the middle of its block, and a third
	 * of a block off to one side skews the whole 3x3 sideways. Nodes passed through
	 * on the way have no such test — reaching their block is enough.
	 */
	private static final double ARRIVE_CENTERED = 0.12;
	/**
	 * Once arrived, how far we may drift before it counts as leaving. Deliberately
	 * far looser than {@link #ARRIVE_CENTERED}: with a single threshold, ordinary
	 * jostle around the centre flipped between "arrived" (the engine aims at the
	 * face) and "not arrived" (this class aims at the block underfoot) tick by tick,
	 * and the view whipped back and forth — the "đầu xoay loạn xạ" the user saw.
	 */
	private static final double ARRIVE_KEEP = 0.45;
	/**
	 * Inside this range we stop steering with the view. The direction to a centre
	 * we're nearly on is mostly noise, so re-aiming at it every tick spins the
	 * player on the spot; we hold the view and side-step in with the movement keys.
	 */
	private static final double HOLD_YAW_RANGE = 0.7;
	/** Movement below this is not worth a key press — it would just jitter. */
	private static final double CENTER_DEADZONE = 0.06;
	/**
	 * Creeping to the exact middle can't go on forever: sneaking moves in whole
	 * ticks and the last few hundredths may never close. After this long we take
	 * the spot we're on. Without it {@link #trackStall} would call centring a stall
	 * and report BLOCKED, and the face would be abandoned to the layer sweep.
	 */
	private static final int CENTER_LIMIT = 30;
	/** Within this distance we creep, for accuracy. */
	private static final double CREEP_RANGE = 1.2;
	private static final int STALL_LIMIT = 80;
	private static final int REPATH_INTERVAL = 40;
	/**
	 * How long standing still counts as "working" rather than "stuck" while the
	 * breaker is actually chewing a block in our way. Server mines have very hard
	 * custom blocks; the plain 4-second stall limit was cutting every bore off
	 * mid-block, so routes through rock never completed ("phải đào block ở trước
	 * mặt để di chuyển tới đó" mà không tự đào).
	 */
	private static final int DIG_PATIENCE = 600;
	/**
	 * How far off a block-column's centre the body may be and still pillar up from
	 * it. The 0.6-wide body pokes into the next column beyond ~0.2, and a player
	 * straddling the seam between two blocks is exactly the "nhảy nhảy không đặt
	 * được": the column flickers under them mid-hop, the aim chases two different
	 * floor blocks, and every placement is rejected for intersecting the body.
	 */
	private static final double PILLAR_CENTER_MARGIN = 0.15;

	private final MinecraftClient client;
	private final AutoMineConfig config;
	private final SimInput input;
	private final BlockBreaker breaker;
	private final BlockPlacer placer;
	private final Selection selection;

	private List<BlockPos> path;
	private BlockPos pathGoal;
	private int pathIndex;
	private int pathAge;

	private int stallTicks;
	/** Ticks spent stationary on the CURRENT blocking block; fresh per block. */
	private int digTicks;
	private BlockPos lastDigTarget;
	private int centerTicks;
	/** Where we last reported ARRIVED, so small drift there doesn't retract it. */
	private BlockPos arrivedAt;
	private double lastDistSq = Double.MAX_VALUE;
	/** True while towering up: the climb must be finished before anything else. */
	private boolean climbing;
	/**
	 * The horizontal axis we're currently boring along, held until the offset on it
	 * runs out. Recomputing "whichever axis is furthest" every tick made the tunnel
	 * alternate axes step by step and come out as a diagonal staircase.
	 */
	private Direction.Axis digAxis;

	/** Y range of the layer being worked on; nothing outside it may be broken. */
	private int digMinY = Integer.MIN_VALUE;
	private int digMaxY = Integer.MAX_VALUE;

	/**
	 * The lava-adjacent cell {@link #digStep} refused to break on the way
	 * somewhere. The engine reads this after a BLOCKED result and plugs the lava
	 * so the route can be cut after all — without it, a face on the far side of a
	 * lava pocket was simply unreachable and never got dug.
	 */
	private BlockPos lavaObstacle;

	public MoveController(MinecraftClient client, AutoMineConfig config, SimInput input,
			BlockBreaker breaker, Selection selection) {
		this.client = client;
		this.config = config;
		this.input = input;
		this.breaker = breaker;
		this.placer = new BlockPlacer(client);
		this.selection = selection;
	}

	public void reset() {
		stallTicks = 0;
		digTicks = 0;
		lastDigTarget = null;
		centerTicks = 0;
		arrivedAt = null;
		lastDistSq = Double.MAX_VALUE;
		path = null;
		pathGoal = null;
		pathIndex = 0;
		pathAge = 0;
		climbing = false;
		digAxis = null;
		placer.reset();
	}

	/**
	 * Whether a tower-up is still in progress. Interrupting one to go dig something
	 * drops the player straight back into the hole, so callers must let it finish.
	 */
	public boolean isClimbing() {
		return climbing;
	}

	/**
	 * Stand a block underneath and hop onto it, the way a person climbs out of a
	 * hole they dug by mistake. Used when the player has ended up <em>below</em> the
	 * layer being worked: from down there every aim sits a row low, so getting back
	 * up matters more than whatever was being dug.
	 *
	 * @return false when there is nothing solid in the hotbar to build with.
	 */
	public boolean pillarUp(ClientPlayerEntity player) {
		return tryPillarTick(player);
	}

	/**
	 * Run one tick of towering up, if possible: centre the body over one column,
	 * then drive the paced placer. @return true when the tick was consumed by the
	 * climb; false when placing is off or there is nothing to build with.
	 */
	private boolean tryPillarTick(ClientPlayerEntity player) {
		if (!config.allowPlace) {
			return false;
		}
		// The mixin breaks whatever the crosshair rests on, and we're about to look
		// straight down at the block we intend to stand on.
		breaker.cancel();
		// Fallen between two blocks: get the whole body over ONE column before any
		// hop, or the placement never lands (see PILLAR_CENTER_MARGIN).
		if (centerForPillar(player)) {
			return true;
		}
		boolean[] jump = {false};
		if (!placer.tick(player, jump)) {
			climbing = false;
			return false;
		}
		climbing = true;
		input.set(0.0F, 0.0F, jump[0], false);
		return true;
	}

	/**
	 * If the body is straddling a block seam while on the ground, creep onto the
	 * centre of the current column and report true — the pillar hop must wait.
	 */
	private boolean centerForPillar(ClientPlayerEntity player) {
		if (!player.isOnGround()) {
			return false; // mid-hop: the placer's follow-the-body logic owns this
		}
		BlockPos feet = player.getBlockPos();
		double dx = feet.getX() + 0.5 - player.getX();
		double dz = feet.getZ() + 0.5 - player.getZ();
		if (Math.abs(dx) <= PILLAR_CENTER_MARGIN && Math.abs(dz) <= PILLAR_CENTER_MARGIN) {
			return false; // squarely over one column — clear to hop
		}
		climbing = true; // part of the climb: don't let the engine steal the tick
		creepInPlace(player, dx, dz);
		return true;
	}

	/** The cell whose adjacent lava stopped the last {@link #moveTo}, or null. */
	public BlockPos lavaObstacle() {
		return lavaObstacle;
	}

	public Result moveTo(BlockPos target) {
		lavaObstacle = null; // only ever describes the CURRENT call's failure
		ClientPlayerEntity player = client.player;
		World world = client.world;
		if (player == null || world == null) {
			return Result.BLOCKED;
		}

		BlockPos feet = player.getBlockPos();
		// The climb is over the moment we're no longer below where we're headed.
		if (feet.getY() >= target.getY()) {
			climbing = false;
		}

		if (feet.equals(target)) {
			// Already settled here: hold that verdict while we're anywhere near the
			// middle. Re-testing the tight arrival threshold every tick made ordinary
			// jostle read as "left the spot", which cancelled the cell being dug and
			// swung the view back down at our own feet.
			if (arrivedAt != null && arrivedAt.equals(target)) {
				if (horizontalDistSq(player, target) <= ARRIVE_KEEP * ARRIVE_KEEP) {
					input.stop();
					return Result.ARRIVED;
				}
				arrivedAt = null;
			}
			// Creep to the middle before saying we're there, so every face is dug from
			// the same spot — but don't insist forever.
			if (horizontalDistSq(player, target) <= ARRIVE_CENTERED * ARRIVE_CENTERED
					|| ++centerTicks > CENTER_LIMIT) {
				input.stop();
				reset();
				arrivedAt = target.toImmutable();
				return Result.ARRIVED;
			}
			// Standing on the destination already: nudge onto its middle <b>without
			// turning</b>. Turning here was a wasted head movement the user could see —
			// the view would swing down at our own feet and then straight back up to the
			// block being dug, which is the "quay 2 lần" they reported. The only thing
			// allowed to rotate the view is aiming at what we are about to break.
			breaker.cancel();
			creepInPlace(player,
					target.getX() + 0.5 - player.getX(),
					target.getZ() + 0.5 - player.getZ());
			return Result.MOVING;
		}
		centerTicks = 0;
		arrivedAt = null;

		if (trackStall(player, target)) {
			input.stop();
			reset();
			return Result.BLOCKED;
		}

		if (followWalkableRoute(player, world, feet, target)) {
			return Result.MOVING;
		}

		// No open route: cut our own way there.
		return digToward(player, world, feet, target);
	}

	// ---- walking an open route ----

	private boolean followWalkableRoute(ClientPlayerEntity player, World world, BlockPos feet, BlockPos target) {
		boolean stale = path == null
				|| !target.equals(pathGoal)
				|| pathIndex >= path.size()
				|| ++pathAge > REPATH_INTERVAL;
		if (stale) {
			path = LocalPath.find(world, feet, target, Math.max(1, 3));
			pathGoal = target;
			pathIndex = 0;
			pathAge = 0;
		}
		if (path == null || path.isEmpty()) {
			return false;
		}

		// Skip nodes we've already reached (the player may have slid through several).
		while (pathIndex < path.size() && path.get(pathIndex).equals(feet)) {
			pathIndex++;
		}
		if (pathIndex >= path.size()) {
			path = null;
			return false;
		}

		BlockPos node = path.get(pathIndex);
		// A node we're not adjacent to means we've drifted off the route.
		if (Math.abs(node.getX() - feet.getX()) > 1 || Math.abs(node.getZ() - feet.getZ()) > 1) {
			path = null;
			return false;
		}

		boolean lastNode = pathIndex == path.size() - 1;
		boolean stepUp = node.getY() > feet.getY();
		stepTowardCenter(player, node, lastNode, stepUp);
		return true;
	}

	/**
	 * Walk at {@code node}, jumping if it's a step up.
	 *
	 * <p>Far out we point the view where we're going, which is how a person walks.
	 * Close in we <b>stop turning</b> and side-step instead: the direction to a
	 * centre you are all but standing on is noise, so re-aiming at it every tick
	 * makes the player pirouette. Holding the view also leaves the aim where the
	 * engine wants it — on the face about to be dug.
	 *
	 * <p>Sneaking is never used. It was here to stop the bot walking off ledges and to
	 * settle it on a block centre, but the user does not want the crouch, so approach
	 * accuracy is left entirely to {@link #creepInPlace} and the arrival thresholds.
	 */
	private void stepTowardCenter(ClientPlayerEntity player, BlockPos node, boolean precise, boolean stepUp) {
		double dx = node.getX() + 0.5 - player.getX();
		double dz = node.getZ() + 0.5 - player.getZ();
		double distSq = dx * dx + dz * dz;

		breaker.cancel(); // purely walking; don't let the mixin break anything

		if (precise && distSq <= HOLD_YAW_RANGE * HOLD_YAW_RANGE) {
			creepInPlace(player, dx, dz);
			return;
		}

		player.setYaw(Rotations.yawTo(dx, dz));
		boolean creep = precise && distSq <= CREEP_RANGE * CREEP_RANGE;
		boolean sprint = config.allowSprint && !creep && !stepUp && distSq > 4.0;
		input.set(1.0F, 0.0F, stepUp && player.isOnGround(), sprint);
	}

	/**
	 * Nudge onto the centre without turning: the offset is resolved against the
	 * view we're already holding, so it comes out as forward/back and strafe.
	 */
	private void creepInPlace(ClientPlayerEntity player, double dx, double dz) {
		double yaw = Math.toRadians(player.getYaw());
		double sin = Math.sin(yaw);
		double cos = Math.cos(yaw);
		// Minecraft yaw: forward is (-sin, cos), and +strafe is to the player's left.
		double forward = dz * cos - dx * sin;
		double strafe = dz * sin + dx * cos;

		input.set(axisInput(forward), axisInput(strafe), false, false);
	}

	private static float axisInput(double offset) {
		if (Math.abs(offset) < CENTER_DEADZONE) {
			return 0.0F;
		}
		return offset > 0 ? 1.0F : -1.0F;
	}

	// ---- carving a way through ----

	private Result digToward(ClientPlayerEntity player, World world, BlockPos feet, BlockPos target) {
		boolean overColumn = feet.getX() == target.getX() && feet.getZ() == target.getZ();

		// Descending: dig straight down once we're over the right column.
		if (overColumn && feet.getY() > target.getY()) {
			input.stop();
			BlockPos below = feet.down();
			return BlockUtil.passable(world, below) ? Result.MOVING : digStep(below);
		}

		if (!overColumn) {
			Direction step = stepDirection(boringAxis(feet, target), feet, target);
			BlockPos ahead = feet.offset(step);
			// Wholly below the layer, headed up, and the way forward is walled off:
			// those wall cells may NOT be dug (they're beneath the layer), so no
			// horizontal route exists at this height. Build up out of the pit first;
			// the walk continues at a legal height. This was the "lọt xuống mà không
			// thấy bắc lên": the mover shoulder-charged the pit wall until the stall
			// tripped, and never placed a single block.
			if (feet.getY() < digMinY && target.getY() > feet.getY()
					&& (!BlockUtil.passable(world, ahead) || !BlockUtil.passable(world, ahead.up()))
					&& tryPillarTick(player)) {
				return Result.MOVING;
			}
			// Aim at the cell level with the chest and let the 3x3 slice take the whole
			// column with it. Clearing the column cell by cell from the top down is what
			// made the head rear up and drop on every single step of the tunnel — the
			// "đầu cứ hướng lên trên, lặp lại liên tục" the user reported. One aim at the
			// middle is both fewer swings and no vertical head movement at all.
			int middle = clampToLayer(feet.getY() + 1);
			BlockPos midCell = new BlockPos(ahead.getX(), middle, ahead.getZ());
			if (!BlockUtil.passable(world, midCell)) {
				input.stop();
				return digStep(midCell);
			}
			// The body only needs one more cell: the one at the feet. Dig it only when
			// it ALONE still blocks the step — the chest-height swing above usually took
			// it (3x3), and the old bottom-up order aimed at the floor FIRST, which was
			// the head-dip right after every face ("đào tâm xong lại cúi").
			BlockPos floorCell = new BlockPos(ahead.getX(), clampToLayer(feet.getY()), ahead.getZ());
			if (!BlockUtil.passable(world, floorCell)) {
				input.stop();
				return digStep(floorCell);
			}
			breaker.cancel();
			// Face straight down the tunnel we're cutting, not at the target off to one
			// side — heading diagonally is what scraped the player along the walls.
			player.setYaw(Rotations.yawTo(step.getOffsetX(), step.getOffsetZ()));
			input.set(1.0F, 0.0F, false, false);
			return Result.MOVING;
		}
		digAxis = null;

		// Right column but too low: clear the ceiling, then tower up out of the hole.
		if (feet.getY() < target.getY()) {
			BlockPos above = feet.up().up();
			// Only clear the ceiling when a climb is not already under way. Digging in the
			// middle of a tower-up is the other half of the tool-swapping the user saw:
			// digStep selects a pickaxe, the next tick's placer.tick selects a block again,
			// and the two fight over the hand every tick so the placement never lands.
			// Finish the hop first — the ceiling is still there to deal with afterwards.
			if (!climbing && !BlockUtil.passable(world, above)) {
				input.stop();
				return digStep(above);
			}
			if (tryPillarTick(player)) {
				return Result.MOVING;
			}
			// Nothing to build with. If the ceiling is what's stopping us, dig it now that
			// no climb is in progress to fight over the hand.
			climbing = false;
			if (!BlockUtil.passable(world, above)) {
				input.stop();
				return digStep(above);
			}
			return Result.BLOCKED; // nothing to build with and nothing to climb
		}

		input.stop();
		return Result.MOVING;
	}

	/** Keep {@code y} inside the layer we're allowed to break in. */
	private int clampToLayer(int y) {
		return Math.max(digMinY, Math.min(digMaxY, y));
	}

	/**
	 * Restrict what clearing a path is allowed to break to the layer currently
	 * being mined. Without this the mover would tunnel through the layers below on
	 * its way somewhere, which is what made the dig look so scattered.
	 */
	public void setLayerBounds(int minY, int maxY) {
		this.digMinY = minY;
		this.digMaxY = maxY;
	}

	/** Mine a block that is in the way, refusing anything outside the selection or layer. */
	private Result digStep(BlockPos pos) {
		World world = client.world;
		if (!selection.contains(pos)) {
			return Result.BLOCKED; // never dig outside the marked box
		}
		if (pos.getY() < digMinY || pos.getY() > digMaxY) {
			return Result.BLOCKED; // and never outside the layer we're on
		}
		if (!BlockUtil.isBreakable(world, pos)) {
			return Result.BLOCKED;
		}
		if (config.avoidLava && BlockUtil.lavaAdjacent(world, pos)) {
			lavaObstacle = pos.toImmutable(); // the engine can plug this and retry
			return Result.BLOCKED;
		}
		breaker.tickArmed(pos, config.reachDistance);
		return Result.MOVING;
	}

	// ---- helpers ----

	private boolean trackStall(ClientPlayerEntity player, BlockPos target) {
		double distSq = horizontalDistSq(player, target)
				+ Math.abs(player.getBlockY() - target.getY());
		// Standing still because we're mining the block in the way IS progress:
		// digStep armed the breaker last tick, and a hard block simply takes time.
		// Each new blocking block gets its own patience budget.
		BlockPos digging = breaker.aiming();
		if (digging != null && !digging.equals(lastDigTarget)) {
			digTicks = 0;
		}
		lastDigTarget = digging;
		if (lastDistSq - distSq > 0.0015) {
			stallTicks = 0;
			digTicks = 0;
		} else if (digging != null && ++digTicks <= DIG_PATIENCE) {
			stallTicks = 0;
		} else {
			stallTicks++;
		}
		lastDistSq = distSq;
		return stallTicks > STALL_LIMIT;
	}

	private static double horizontalDistSq(ClientPlayerEntity player, BlockPos pos) {
		double dx = pos.getX() + 0.5 - player.getX();
		double dz = pos.getZ() + 0.5 - player.getZ();
		return dx * dx + dz * dz;
	}

	/**
	 * The axis to bore along. Whichever one is further off is chosen when a tunnel
	 * starts, and then <b>held</b> until the offset on it is gone — so the route is
	 * one straight run and a square corner rather than a diagonal staircase.
	 */
	private Direction.Axis boringAxis(BlockPos feet, BlockPos target) {
		if (digAxis != null && offsetOn(digAxis, feet, target) != 0) {
			return digAxis;
		}
		int offX = target.getX() - feet.getX();
		int offZ = target.getZ() - feet.getZ();
		digAxis = Math.abs(offX) >= Math.abs(offZ) ? Direction.Axis.X : Direction.Axis.Z;
		return digAxis;
	}

	private static int offsetOn(Direction.Axis axis, BlockPos feet, BlockPos target) {
		return axis == Direction.Axis.X
				? target.getX() - feet.getX()
				: target.getZ() - feet.getZ();
	}

	/** One step from {@code feet} along {@code axis}, toward the target. */
	private static Direction stepDirection(Direction.Axis axis, BlockPos feet, BlockPos target) {
		int offset = offsetOn(axis, feet, target);
		if (axis == Direction.Axis.X) {
			return offset >= 0 ? Direction.EAST : Direction.WEST;
		}
		return offset >= 0 ? Direction.SOUTH : Direction.NORTH;
	}
}
