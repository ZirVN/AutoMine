package com.automine.mine;

import com.automine.config.AutoMineConfig;
import com.automine.util.AutoEat;
import com.automine.util.BlockBreaker;
import com.automine.util.BlockPlacer;
import com.automine.util.BlockUtil;
import com.automine.util.SimInput;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.Input;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Runs the dig layer by layer, top down. Walks to each face's stand position
 * and swings once at the middle cell — the pickaxe takes a 3x3 slice, so that one
 * hit clears the face. The aim never leaves a face centre during mining, which
 * keeps the head level and the route orderly.
 *
 * <p>It never gives up and pauses itself. A block it can't get to is parked and
 * retried later; if it defeats every approach it is finally counted as skipped so
 * the job can move on.
 *
 * <p><b>One face at a time, and nowhere else.</b> Each face runs through
 * {@link Phase} in order: walk to the stand position, reposition if the middle
 * can't be seen, line the crosshair up on the middle, and only then take out the
 * pickaxe. The pickaxe comes out <em>after</em> the live crosshair
 * is confirmed to be on that exact cell — never before — and once a face is started
 * it is never abandoned for another. Being unable to reach it right now means stand
 * still and keep working at it, not move along.
 */
public final class QuarryEngine {
	public enum State {IDLE, RUNNING, PAUSED, DONE}

	/**
	 * The steps a single face goes through, in order. Never skips ahead: the pickaxe
	 * only comes out in {@link #DIG_LOCKED}, which can only be entered from
	 * {@link #AIM_LOCK} once the real crosshair is on the target cell.
	 */
	private enum Phase {
		/** Walking to the spot this face is dug from. */
		APPROACH,
		/** Can't see the middle from here: walk to a stance that can. */
		REPOSITION,
		/** Standing still, turning until the crosshair genuinely rests on the middle. */
		AIM_LOCK,
		/** Confirmed on target: pickaxe out, vanilla breaking. */
		DIG_LOCKED
	}

	/** Faces the cursor may skip past (already clear) in a single tick. */
	private static final int SKIP_BUDGET = 4096;
	/** How long a lock may go without progress before the HUD says so. Never skips. */
	private static final int SLOW_NOTE_TICKS = 200;
	/** Ticks a sweep leftover may resist before it's parked and the sweep moves on. */
	private static final int SWEEP_TARGET_LIMIT = 900;
	/** "Phạm vi xung quanh gần": how far around the trigger cell water gets filled. */
	private static final int FLUID_RADIUS = 4;
	/** Ticks one fluid cell may resist before it's parked. */
	private static final int FLUID_TARGET_LIMIT = 200;
	/** Two bubbles: each bubble is 30 air out of a 300 maximum. */
	private static final int AIR_LOW = 60;
	/** Another account this close to a face means it's theirs — work elsewhere. */
	private static final double PLAYER_AVOID_RADIUS = 5.0;
	/** How far outside the box another player still counts as "digging here". */
	private static final int PLAYER_NEAR_BOX = 8;

	private final MinecraftClient client;
	private final AutoMineConfig config;
	private final SimInput input = new SimInput();
	private final BlockBreaker breaker;

	private final Selection selection;
	private MoveController mover;
	private QuarryPlan plan;

	private State state = State.IDLE;
	private Input savedInput;
	/**
	 * True while the current PAUSED state was entered by auto-eat rather than the
	 * user. Only such a pause may be auto-resumed once chewing ends — resuming any
	 * PAUSED state is what made a manual /pause undo itself one tick later.
	 */
	private boolean autoPaused;

	private BlockPos activeTarget;
	/** Blocks that beat every approach this layer; cleared on the next layer. */
	private final Set<BlockPos> givenUp = new HashSet<>();

	private int mined;
	private int skipped;
	private boolean wasClimbing;
	/** Which step of the current face we're on. */
	private Phase phase = Phase.APPROACH;
	/** The face centre {@link #phase} belongs to; when this changes the phase restarts. */
	private BlockPos phaseFace;
	/** Ticks spent in the current phase, for the HUD and the slow-work note. */
	private int phaseTicks;
	/** Where {@link Phase#REPOSITION} is currently walking to, if anywhere. */
	private BlockPos repositionTarget;
	/**
	 * Approach the current face via its own column ({@link QuarryPlan#entryPos})
	 * instead of the normal stand cell. Switched on when the stand cell can't be
	 * reached — at a row start it lies outside the box, buried under rock the
	 * mover must not break; the face column is inside and always diggable.
	 */
	private boolean useEntryStance;
	/**
	 * Stances already tried for the current face and found wanting, so the search for a
	 * spot that can see the middle moves outward instead of pacing between two cells.
	 */
	private final Set<BlockPos> triedStances = new HashSet<>();
	/** Ticks spent climbing back into the layer after dropping below it. */
	private int recoverTicks;
	/**
	 * Latched while climbing back into the layer. Held across the whole climb so a jump
	 * in progress — which lifts the player a block and briefly looks like success — can't
	 * flip the engine back into digging and swap the pickaxe in mid-placement.
	 */
	private boolean recovering;
	private boolean warnedNoBlocks;
	/** Set when the face scan skipped work because another account was on it. */
	private boolean dodgedPlayer;
	private String note = "";

	// ---- layer sweep (vét tầng): dig every leftover before descending ----
	/** The leftover being swept; held until it's air or given up (re-picking every
	 *  tick made the sweep ping-pong between targets — an old, fixed bug). */
	private BlockPos sweepTarget;
	private BlockPos sweepStance;
	private int sweepTicks;

	// ---- fluid filling (lấp nước / dung nham) ----
	/** Centre of the water patch being filled; null = no water zone open. */
	private BlockPos fluidCenter;
	/** The fluid cell being filled right now (water in a zone, or a lava plug). */
	private BlockPos fluidTarget;
	private BlockPos fluidStance;
	private int fluidTicks;
	/** Towering up for air; cleared once the head is out and the air bar is full. */
	private boolean breathing;
	/** Fluid cells (and zone centres) that beat us; cleared per layer. */
	private final Set<BlockPos> fluidGivenUp = new HashSet<>();
	/** Paced click source for fluid fills — separate from the mover's tower placer. */
	private final BlockPlacer filler;

	public QuarryEngine(MinecraftClient client, AutoMineConfig config, Selection selection) {
		this.client = client;
		this.config = config;
		this.selection = selection;
		this.breaker = new BlockBreaker(client);
		this.filler = new BlockPlacer(client);
	}

	public State state() {
		return state;
	}

	public boolean isActive() {
		return state == State.RUNNING || state == State.PAUSED;
	}

	public QuarryPlan plan() {
		return plan;
	}

	public BlockPos activeTarget() {
		return activeTarget;
	}

	/** The fluid cell being sealed right now, for the renderer. Null when none. */
	public BlockPos fluidTarget() {
		return fluidTarget;
	}

	/**
	 * The cells of the face being worked on right now, for the renderer to draw.
	 */
	public List<BlockPos> faceCells() {
		if (state != State.RUNNING || plan == null || plan.areLayerFacesDone()) {
			return List.of();
		}
		return plan.faceCells();
	}

	/** The middle cell of that face — what the aim should be on. */
	public BlockPos faceCenter() {
		if (state != State.RUNNING || plan == null || plan.areLayerFacesDone()) {
			return null;
		}
		return plan.faceCenter();
	}

	/** The block vanilla should be breaking right now, or null. Read by the mixin. */
	public BlockPos breakingTarget() {
		return breaker.aiming();
	}

	public int mined() {
		return mined;
	}

	public String statusLine() {
		return switch (state) {
			case IDLE -> "chưa chạy";
			case DONE -> "xong · đã đào " + mined + " block";
			case PAUSED -> "tạm dừng · " + (plan != null ? plan.describe() : "");
			case RUNNING -> plan.describe()
					+ " · " + Math.round(plan.progress() * 100) + "%"
					+ " · đào " + mined
					+ (skipped > 0 ? " · bỏ qua " + skipped : "")
					+ (note.isEmpty() ? "" : " · " + note);
		};
	}

	// ---- control ----

	/** @return an error message, or null when it started. */
	public String start() {
		ClientPlayerEntity player = client.player;
		if (player == null || client.world == null) {
			return "chưa vào thế giới";
		}
		// /start must never silently throw a run away. Typing it while the dig is
		// already going used to fall through to "build a fresh plan" — a full
		// restart from the top layer for pressing the key twice. Same for PAUSED.
		if (state == State.RUNNING) {
			return "đang đào rồi — /pause để tạm dừng, /stop để dừng";
		}
		if (state == State.PAUSED) {
			resume();
			message("chạy tiếp từ chỗ đang dừng");
			return null;
		}
		if (!selection.isComplete()) {
			return "chưa đủ 2 điểm — dùng /sel 1 và /sel 2";
		}

		// An unfinished plan for the same box resumes exactly where it left off —
		// the plan's cursor (layer/row/step) was never thrown away by /stop.
		boolean resuming = plan != null && !plan.isDone() && plan.matchesSelection(selection);
		if (resuming) {
			message("tiếp tục đào từ chỗ cũ — " + plan.describe());
			// The world may have changed while stopped: re-approach from scratch,
			// but leave the plan cursor alone.
			phaseFace = null;
			restartFace();
			clearTarget();
		} else {
			// Tạo plan mới
			stop();
			boolean mirrored = shouldMirror(client.world);
			if (mirrored) {
				message("có người đang đào phía đầu kia — bắt đầu từ góc đối diện");
			}
			plan = new QuarryPlan(selection, config.layerHeight, config.passWidth, mirrored);
			mover = new MoveController(client, config, input, breaker, selection);
			mined = 0;
			skipped = 0;
			wasClimbing = false;
			phase = Phase.APPROACH;
			phaseFace = null;
			phaseTicks = 0;
			repositionTarget = null;
			triedStances.clear();
			recoverTicks = 0;
			recovering = false;
			warnedNoBlocks = false;
			note = "";
			givenUp.clear();
			clearTarget();
		}

		// Sweep and fluid work is all re-derived from the world, so starting (fresh
		// or resumed) always begins it from scratch — stale targets from a previous
		// run would point at blocks that may long since be gone.
		resetSweepAndFluids();

		savedInput = player.input;
		player.input = input;
		state = State.RUNNING;
		return null;
	}

	private void resetSweepAndFluids() {
		sweepTarget = null;
		sweepStance = null;
		sweepTicks = 0;
		fluidCenter = null;
		fluidTarget = null;
		fluidStance = null;
		fluidTicks = 0;
		breathing = false;
		fluidGivenUp.clear();
		filler.reset();
	}

	public void pause() {
		// A deliberate pause always belongs to whoever asked last: even when auto-eat
		// paused first, a manual /pause during the chew must stick afterwards.
		autoPaused = false;
		if (state == State.RUNNING) {
			state = State.PAUSED;
			input.stop();
			breaker.cancel();
		}
	}

	/** Pause on auto-eat's behalf; {@link #isAutoPaused()} lets it resume this one. */
	public void pauseForEating() {
		if (state == State.RUNNING) {
			pause();
			autoPaused = true;
		}
	}

	/** Whether the current pause was auto-eat's doing, so only that may be undone automatically. */
	public boolean isAutoPaused() {
		return autoPaused;
	}

	public void resume() {
		if (state == State.PAUSED) {
			state = State.RUNNING;
			autoPaused = false;
			givenUp.clear(); // give everything another go
			phaseFace = null; // re-derive the face from scratch after a pause
			restartFace();
			clearTarget();
			return;
		}
		// /resume (or the menu's "Tiếp tục") after /stop: people expect it to carry
		// on, but the engine is IDLE then and this method used to no-op silently
		// while the command still printed "chạy tiếp" — the dig looked dead. From
		// IDLE the only correct way back in is start(), whose resume path picks the
		// plan cursor up exactly where /stop left it.
		if (state == State.IDLE && plan != null && !plan.isDone()) {
			String error = start();
			if (error != null) {
				message("§c" + error);
			}
		}
	}

	public void stop() {
		releaseInput();
		breaker.cancel();
		activeTarget = null;
		autoPaused = false;
		AutoEat.reset(); // whatever was mid-chew, its state must not leak into the next run
		state = plan != null && plan.isDone() ? State.DONE : State.IDLE;
	}

	private void releaseInput() {
		ClientPlayerEntity player = client.player;
		if (player != null && savedInput != null) {
			player.input = savedInput;
		}
		savedInput = null;
		input.stop();
	}

	private void finish() {
		message("xong! Đã đào " + mined + " block"
				+ (skipped > 0 ? ", bỏ qua " + skipped + " block không phá được" : "") + ".");
		releaseInput();
		breaker.cancel();
		activeTarget = null;
		state = State.DONE;
	}

	// ---- tick ----

	public void tick() {
		if (state != State.RUNNING) {
			return;
		}
		ClientPlayerEntity player = client.player;
		World world = client.world;
		if (player == null || world == null) {
			stop();
			return;
		}
		if (player.input != input) {
			savedInput = player.input;
			player.input = input;
		}

		// The block we were working on just broke.
		if (activeTarget != null && world.getBlockState(activeTarget).isAir()) {
			mined++;
			// Drop the finished block and nothing else. Both modes then pick their next
			// target on the very next tick and aim straight at it.
			//
			// Face mode used to detour through POST_BREAK here: stop for five ticks,
			// disarm, then re-derive the phase, which sent it back through AIM_LOCK and
			// started the aim search from scratch. That extra pass is the "đào xong lại
			// hướng đi đâu" — a whole phase whose only visible effect was an extra head
			// movement between two swings. The stance is unchanged after a break, so
			// there is nothing to settle and nothing to re-decide.
			activeTarget = null;
			breaker.cancel();
		}


		// Set layer bounds for movement controller
		mover.setLayerBounds(plan.layerBottom(), plan.layerTop());

		// Fluids come first: water pools BELOW the layer floor, so if the recovery
		// check ran before this, it would tower the player out of the very pool
		// being filled and the two would fight forever.
		if (tickFluidFill(player, world)) {
			return;
		}

		// Fallen below the layer: getting back up comes before everything else. Letting
		// anything else run first stopped the input mid-climb — it cancelled the very
		// jump the placement depends on, so the climb could never finish.
		if (recoverFromBelowLayer(player)) {
			return;
		}

		// Just finished climbing out: line the crosshair back up before touching anything.
		if (wasClimbing && !mover.isClimbing()) {
			enterPhase(Phase.AIM_LOCK);
		}
		wasClimbing = mover.isClimbing();

		// Tell the player once why a climb can't happen, rather than hopping forever.
		if (config.allowPlace && !warnedNoBlocks
				&& mover.isClimbing() && !BlockPlacer.hasBuildingBlock(player)) {
			warnedNoBlocks = true;
			message("§ehotbar không có block đặc nào để xây trụ leo lên — bỏ đá cuội/deepslate vào hotbar.");
		}

		tickPhases(player, world);
	}

	/**
	 * Faces first, then the layer sweep: every leftover block of the layer is dug
	 * before the plan is allowed to descend, so reaching the next layer means the
	 * one above is provably clean — the user's "vét sạch mới xuống tầng tiếp".
	 */
	private void tickPhases(ClientPlayerEntity player, World world) {
		if (!plan.areLayerFacesDone()) {
			tickFaces(world);
			return;
		}
		if (tickLayerSweep(player, world)) {
			return; // still leftovers in this layer
		}

		// Layer verified clean — descend.
		int finishedLayer = plan.layerIndex() + 1;
		clearTarget();
		givenUp.clear();      // per-layer scope: the next layer gets a fresh slate
		fluidGivenUp.clear(); // new layer can expose new (and retryable) fluids
		if (plan.nextLayer()) {
			message("tầng " + finishedLayer + " sạch — xuống tầng " + (plan.layerIndex() + 1));
			note = "";
		} else {
			// Hết tất cả tầng - XONG!
			finish();
		}
	}

	/**
	 * Dig whatever the face pass left standing in this layer — clipped edge faces,
	 * gravel that poured in, faces skipped for having gaps. One leftover at a time,
	 * nearest first, and the target is <b>held</b> until it is air or given up:
	 * re-picking "nearest" every tick swapped targets mid-route and ping-ponged.
	 *
	 * @return true while this layer still has work; false when it is clean.
	 */
	private boolean tickLayerSweep(ClientPlayerEntity player, World world) {
		if (!config.sweepLayer) {
			return false;
		}
		if (sweepTarget != null && !needsDigging(world, sweepTarget)) {
			sweepTarget = null; // dug (or no longer eligible) — pick the next one
		}
		if (sweepTarget == null) {
			sweepTarget = nearestLeftover(world, player);
			if (sweepTarget == null) {
				return false; // layer is clean
			}
			sweepTicks = 0;
			sweepStance = null;
			triedStances.clear();
			clearTargetKeepingStance();
			mover.reset();
		}

		// Fluids around the leftover get sealed before the pickaxe goes near it.
		if (startFluidWork(world, sweepTarget)) {
			return true;
		}

		// Park only when NOT actively chewing it: a hard block mid-break is work
		// in progress, however long it takes, not a reason to walk away.
		if (++sweepTicks > SWEEP_TARGET_LIMIT && activeTarget == null) {
			givenUp.add(sweepTarget);
			skipped++;
			sweepTarget = null;
			return true;
		}

		if (breaker.canReach(sweepTarget, config.reachDistance)) {
			input.stop();
			workTarget(sweepTarget);
			note = "vét sót tầng";
			return true;
		}

		// Can't see it from here: walk to a stance that can.
		if (sweepStance == null) {
			sweepStance = standNear(world, player, sweepTarget);
			if (sweepStance == null) {
				givenUp.add(sweepTarget); // no stance anywhere sees it
				skipped++;
				sweepTarget = null;
				return true;
			}
			mover.reset();
		}
		note = "vét sót — di chuyển";
		MoveController.Result result = mover.moveTo(sweepStance);
		if (result == MoveController.Result.BLOCKED) {
			// Same as the face approach: lava in the way gets plugged, then retried.
			BlockPos lava = mover.lavaObstacle();
			if (lava != null && startFluidWork(world, lava)) {
				note = "lấp dung nham mở đường";
				mover.reset();
				return true;
			}
		}
		if (result != MoveController.Result.MOVING) {
			triedStances.add(sweepStance); // arrived-but-blind or blocked: don't re-pick it
			sweepStance = null;
		}
		return true;
	}

	/**
	 * Aim at {@code target} and, once the real crosshair rests on it, dig it —
	 * the sweep's compact version of AIM_LOCK/DIG_LOCKED. Same safety order as
	 * the face machine: the pickaxe only comes out after {@link #crosshairOn}.
	 */
	private void workTarget(BlockPos target) {
		if (crosshairOn(target)) {
			if (!target.equals(activeTarget)) {
				setTarget(target);
			}
			if (!breaker.tickArmed(target, config.reachDistance)) {
				breaker.disarm();
			}
		} else {
			breaker.disarm();
			activeTarget = null;
			breaker.aimOnly(target, config.reachDistance);
		}
	}

	/** The closest block in this layer that still needs digging, or null. */
	private BlockPos nearestLeftover(World world, ClientPlayerEntity player) {
		BlockPos feet = player.getBlockPos();
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		for (int y = plan.layerTop(); y >= plan.layerBottom(); y--) {
			for (int t = plan.minTravel(); t <= plan.maxTravel(); t++) {
				for (int c = plan.minCross(); c <= plan.maxCross(); c++) {
					BlockPos pos = plan.posAt(t, c, y);
					if (!needsDigging(world, pos)) {
						continue;
					}
					double dist = pos.getSquaredDistance(feet);
					// Leftovers beside another account go to the back of the queue:
					// they'll usually dig those themselves. Taken only when they're
					// all that's left — someone has to, and they may have moved on.
					if (otherPlayerNear(pos, PLAYER_AVOID_RADIUS)) {
						dist += 1.0e9;
					}
					if (dist < bestDist) {
						bestDist = dist;
						best = pos.toImmutable();
					}
				}
			}
		}
		return best;
	}

	// ---- fluid filling: lấp nước quanh vùng, lấp dung nham chỗ đào ----

	/**
	 * Open fluid work for the dig target {@code target} if it needs any.
	 *
	 * <p>Two different treatments, per the user's spec. <b>Lava</b> is only ever
	 * plugged where it blocks the dig: the cells directly touching the block about
	 * to be broken, nothing wider. <b>Water</b> opens a whole zone: every water
	 * cell within {@link #FLUID_RADIUS} gets filled and the dig does not resume
	 * until a full scan confirms the patch is dry ("xác nhận hết rồi mới đào tiếp").
	 *
	 * @return true when fluid work was (or already is) pending — the caller should
	 *         stop and let {@link #tickFluidFill} run instead of digging.
	 */
	private boolean startFluidWork(World world, BlockPos target) {
		if (!config.fillFluids || breathing) {
			return false;
		}
		if (fluidCenter != null || fluidTarget != null) {
			return true; // already busy
		}
		// Lava touching the cell we want to break: plug exactly those cells.
		for (Direction dir : Direction.values()) {
			BlockPos neighbor = target.offset(dir);
			if (BlockUtil.isLava(world, neighbor) && !fluidGivenUp.contains(neighbor)) {
				fluidTarget = neighbor.toImmutable();
				fluidTicks = 0;
				fluidStance = null;
				triedStances.clear();
				return true;
			}
		}
		// Water on or around it: fill the nearby patch before digging in it.
		if (!fluidGivenUp.contains(target) && waterNear(world, target)) {
			fluidCenter = target.toImmutable();
			return true;
		}
		return false;
	}

	/**
	 * Fluid check for the whole face, not just its middle: one swing of the 3x3
	 * pickaxe takes all nine cells, so lava beside a <em>corner</em> cell floods
	 * the hole just as surely as lava beside the centre. Only in-box cells are
	 * checked — cells hanging outside the rim are not ours to dig around.
	 */
	private boolean startFluidWorkForFace(World world) {
		for (BlockPos cell : plan.faceCells()) {
			if (selection.contains(cell) && startFluidWork(world, cell)) {
				return true;
			}
		}
		return false;
	}

	/** Any water in the 3x3x3 cube around {@code pos} (including pos itself)? */
	private boolean waterNear(World world, BlockPos pos) {
		for (int dx = -1; dx <= 1; dx++) {
			for (int dy = -1; dy <= 1; dy++) {
				for (int dz = -1; dz <= 1; dz++) {
					if (BlockUtil.isWater(world, pos.add(dx, dy, dz))) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/**
	 * One tick of fluid work. Owns the player completely while active: no digging,
	 * no recovery, no face phases — those resume only once this returns false.
	 *
	 * <p>Air management, exactly as asked: at two bubbles the player pillars up
	 * ("đặt block lên thở"), breathes until the bar is full, then goes back down
	 * and finishes the patch. The pillar blocks land inside the water zone, which
	 * makes them fill — the layer sweep digs them again later if they're in the box.
	 *
	 * @return true while fluid work is running.
	 */
	private boolean tickFluidFill(ClientPlayerEntity player, World world) {
		if (!config.fillFluids) {
			fluidCenter = null;
			fluidTarget = null;
			breathing = false;
			return false;
		}
		if (fluidCenter == null && fluidTarget == null && !breathing) {
			return false;
		}

		// Never swing anything while fluid work owns the view.
		breaker.cancel();
		activeTarget = null;

		// Air first: two bubbles left means stop filling and get to a breather.
		if (!breathing && player.isSubmergedInWater() && player.getAir() <= AIR_LOW) {
			breathing = true;
			fluidStance = null;
			mover.reset();
		}
		if (breathing) {
			if (player.isSubmergedInWater()) {
				// Tower up out of the water; no blocks (or placing off) → swim for it.
				if (!mover.pillarUp(player)) {
					input.set(0.0F, 0.0F, true, false);
					warnNoBlocks("§ehết block xây trụ thở — đang bơi lên.");
				}
				note = "còn " + (player.getAir() / 30) + " bọt khí — lên thở";
				return true;
			}
			if (player.getAir() < player.getMaxAir()) {
				input.stop();
				note = "đang thở (" + (player.getAir() / 30) + "/10)";
				return true;
			}
			breathing = false; // full again — back down to finish the patch
			mover.reset();
		}

		// Current cell done (block landed, or fluid drained on its own)?
		if (fluidTarget != null && !world.getBlockState(fluidTarget).isReplaceable()) {
			fluidTarget = null;
			fluidStance = null;
			filler.reset();
		}
		if (fluidTarget == null) {
			if (fluidCenter == null) {
				return false; // lava plug done — straight back to digging
			}
			fluidTarget = nextWaterCell(world, player);
			if (fluidTarget == null) {
				// Nothing placeable left. Only a full re-scan may close the zone —
				// this is the user's "xác nhận hết rồi mới di chuyển tới chỗ đào".
				if (waterInZone(world)) {
					message("§ecòn nước không lấp được quanh "
							+ fluidCenter.toShortString() + " — bỏ qua, đào tiếp");
					fluidGivenUp.add(fluidCenter); // don't re-open this zone forever
				} else {
					message("đã lấp hết nước — quay lại đào");
				}
				fluidCenter = null;
				return false;
			}
			fluidTicks = 0;
			fluidStance = null;
			triedStances.clear();
		}

		// The mover may need to cut a step while walking to the pour spot; let it
		// work down to the water being filled without opening the layers below it.
		mover.setLayerBounds(
				Math.min(plan.layerBottom(), fluidTarget.getY() - 1), plan.layerTop());

		if (++fluidTicks > FLUID_TARGET_LIMIT) {
			fluidGivenUp.add(fluidTarget);
			fluidTarget = null;
			return true;
		}
		if (!BlockPlacer.hasBuildingBlock(player)) {
			input.stop();
			warnNoBlocks("§ehotbar không có block đặc để lấp — bỏ đá cuội/deepslate vào hotbar.");
			note = "chờ block để lấp";
			return true;
		}

		// In position? Aim at a visible support face and click through the crosshair.
		if (filler.fillCell(player, fluidTarget, config.reachDistance)) {
			input.stop();
			note = fluidCenter != null ? "lấp nước" : "lấp dung nham";
			return true;
		}

		// Nothing clickable from here: walk somewhere closer.
		if (fluidStance == null) {
			fluidStance = standNearFluid(world, player, fluidTarget);
			if (fluidStance == null) {
				fluidGivenUp.add(fluidTarget);
				fluidTarget = null;
				return true;
			}
			mover.reset();
		}
		note = "tới chỗ lấp";
		MoveController.Result result = mover.moveTo(fluidStance);
		if (result != MoveController.Result.MOVING) {
			triedStances.add(fluidStance);
			fluidStance = null;
		}
		return true;
	}

	/**
	 * The next water cell of the zone to fill: <b>lowest first</b>, then nearest.
	 * Bottom-up is what makes the whole patch fillable — every cell on the floor
	 * has solid support below it, and each filled cell becomes the support for
	 * the one above.
	 */
	private BlockPos nextWaterCell(World world, ClientPlayerEntity player) {
		BlockPos feet = player.getBlockPos();
		BlockPos best = null;
		int bestY = Integer.MAX_VALUE;
		double bestDist = Double.MAX_VALUE;
		for (int x = fluidCenter.getX() - FLUID_RADIUS; x <= fluidCenter.getX() + FLUID_RADIUS; x++) {
			for (int z = fluidCenter.getZ() - FLUID_RADIUS; z <= fluidCenter.getZ() + FLUID_RADIUS; z++) {
				for (int y = fluidCenter.getY() - FLUID_RADIUS; y <= fluidCenter.getY() + FLUID_RADIUS; y++) {
					BlockPos pos = new BlockPos(x, y, z);
					if (!nearSelection(pos) || fluidGivenUp.contains(pos)) {
						continue;
					}
					if (!BlockUtil.isWater(world, pos) || !world.getBlockState(pos).isReplaceable()) {
						continue; // waterlogged solids get dug, not filled
					}
					if (!BlockPlacer.hasSolidNeighbor(world, pos)) {
						continue; // nothing to place against yet — a lower fill will fix that
					}
					double dist = pos.getSquaredDistance(feet);
					if (y < bestY || (y == bestY && dist < bestDist)) {
						bestY = y;
						bestDist = dist;
						best = pos.toImmutable();
					}
				}
			}
		}
		return best;
	}

	/** Any water left in the zone at all, placeable or not? The zone only closes clean on false. */
	private boolean waterInZone(World world) {
		for (int x = fluidCenter.getX() - FLUID_RADIUS; x <= fluidCenter.getX() + FLUID_RADIUS; x++) {
			for (int z = fluidCenter.getZ() - FLUID_RADIUS; z <= fluidCenter.getZ() + FLUID_RADIUS; z++) {
				for (int y = fluidCenter.getY() - FLUID_RADIUS; y <= fluidCenter.getY() + FLUID_RADIUS; y++) {
					BlockPos pos = new BlockPos(x, y, z);
					if (nearSelection(pos) && BlockUtil.isWater(world, pos)) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/** Inside the selection stretched by one block — enough to seal rim sources. */
	private boolean nearSelection(BlockPos pos) {
		return pos.getX() >= selection.minX() - 1 && pos.getX() <= selection.maxX() + 1
				&& pos.getY() >= selection.minY() - 1 && pos.getY() <= selection.maxY() + 1
				&& pos.getZ() >= selection.minZ() - 1 && pos.getZ() <= selection.maxZ() + 1;
	}

	/**
	 * A spot to pour from: standable, near the fluid cell, not the cell itself
	 * (a block cannot be placed into the space the body occupies). Unlike
	 * {@link #standNear} there is no line-of-sight vetting — the ray test cannot
	 * land on a fluid cell (fluids have no outline shape), so {@code fillCell}'s
	 * own visible-support check is the judge once we get there.
	 */
	private BlockPos standNearFluid(World world, ClientPlayerEntity player, BlockPos target) {
		BlockPos feet = player.getBlockPos();
		BlockPos best = null;
		double bestScore = Double.MAX_VALUE;
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				for (int dy = -1; dy <= 2; dy++) {
					BlockPos candidate = target.add(dx, dy, dz);
					// The body must stay clear of the cell being filled: feet in it,
					// head in it, or floating right on top of it all get the placement
					// rejected by the server for intersecting the player.
					if (candidate.equals(target) || candidate.up().equals(target)
							|| candidate.down().equals(target)) {
						continue;
					}
					if (triedStances.contains(candidate) || !BlockUtil.standable(world, candidate)) {
						continue;
					}
					double score = candidate.getSquaredDistance(target) * 2
							+ candidate.getSquaredDistance(feet);
					if (score < bestScore) {
						bestScore = score;
						best = candidate.toImmutable();
					}
				}
			}
		}
		return best;
	}

	/**
	 * Fallen through the floor of the layer we're working — usually a mis-dig, or
	 * gravel giving way. From down here every aim is a row too low and the dig looks
	 * scattered, so build back up to the layer floor before doing anything else.
	 *
	 * <p><b>Only a block is held down here, never the pickaxe.</b> Enforced by returning
	 * true for the whole recovery, so {@link #tickPhases} — the only thing that ever
	 * arms the breaker or picks a tool — does not run until we are properly back up.
	 *
	 * <p>The state is <b>latched</b> in {@link #recovering}, and this is the fix for the
	 * tool-swapping the user reported ("cứ swap giữa block + cúp khiến ko thể đặt
	 * được"). Testing {@code getBlockY()} fresh each tick was wrong, because the hop
	 * itself lifts the player a whole block: mid-jump the test said "back in the layer",
	 * recovery switched off, the dig phases ran and selected the pickaxe, then the player
	 * landed back in the hole and recovery selected a block again — a swap every single
	 * hop, and the placement never had a stable hand to go out with. Now recovery only
	 * ends once the player is <b>on the ground</b> at or above the layer floor, so a jump
	 * in progress can never be mistaken for a finished climb.
	 *
	 * <p>There is no time limit and no fallback to walking: it stands its ground and
	 * keeps building, and waits to be resupplied if the hotbar runs dry.
	 *
	 * @return true when this tick was spent recovering.
	 */
	private boolean recoverFromBelowLayer(ClientPlayerEntity player) {
		if (!config.allowPlace) {
			// Building is switched off, so "stand and wait for blocks" would wait
			// forever. Let the normal mover walk or dig a way back up instead.
			recovering = false;
			return false;
		}
		// While the engine is NAVIGATING somewhere — approaching a face, walking to
		// a reposition stance, or walking the sweep — being below the layer is the
		// mover's business, not ours: moveTo walks the ground to the goal's column
		// and pillars up right THERE. Towering up in place here was the standstill
		// loop in hollowed-out mines: step toward a floating face, fall off the
		// ledge, climb back up where we fell (not under the face), step off again.
		boolean navigating = (!plan.areLayerFacesDone()
				&& (phase == Phase.APPROACH || phase == Phase.REPOSITION))
				|| sweepStance != null;
		if (navigating) {
			recovering = false;
			return false;
		}
		boolean belowLayer = player.getBlockY() < plan.layerBottom();
		if (!recovering && !belowLayer) {
			return false;
		}
		if (recovering && !belowLayer && player.isOnGround()) {
			// Genuinely back up: standing on something solid at the right height. Line the
			// crosshair up again from scratch before the pickaxe is allowed out.
			recovering = false;
			recoverTicks = 0;
			enterPhase(Phase.AIM_LOCK);
			return false;
		}
		recovering = true;

		recoverTicks++;
		activeTarget = null;
		breaker.cancel(); // no pickaxe down here, and nothing for the mixin to swing at

		if (!mover.pillarUp(player)) {
			// Nothing to build with. Stand still and wait to be resupplied rather than
			// walking out and digging from the wrong height.
			input.stop();
			warnNoBlocks("§ebị lọt xuống dưới tầng mà hotbar không có block đặc nào để kê — bỏ đá cuội vào hotbar.");
			note = "lọt xuống — chờ block để kê lên";
			return true;
		}
		note = "lọt xuống — kê block leo lên (" + (recoverTicks / 20) + "s)";
		return true;
	}

	/** Move to a new step of the current face, resetting its budget. */
	private void enterPhase(Phase next) {
		if (phase != next) {
			phase = next;
			phaseTicks = 0;
		}
	}

	/**
	 * Start the current face again from the top.
	 *
	 * <p>Deliberately leaves {@link #phaseFace} alone. Clearing it here was a bug: the
	 * caller in {@link #tickFaces} compares the plan's face centre against
	 * {@code phaseFace} to notice a genuinely new face, so nulling it made that check
	 * fire on the very next tick and reset the phase <em>again</em> — the face never got
	 * past {@link Phase#APPROACH}, which is why the dig behaved exactly like the old
	 * version. Whoever changes which face is being worked sets {@code phaseFace}.
	 */
	private void restartFace() {
		phase = Phase.APPROACH;
		phaseTicks = 0;
		repositionTarget = null;
		useEntryStance = false;
		triedStances.clear();
	}

	/**
	 * Whether the player's <b>real</b> crosshair rests on {@code pos} — the same
	 * {@code crosshairTarget} the game itself uses to decide what a click hits, and the
	 * same one {@code MinecraftClientMixin} gates force-breaking on.
	 *
	 * <p>This is the "100%" test, and it deliberately replaces the old angle check.
	 * {@link Rotations#turnTo} reports success within 2.5 degrees, which at a few
	 * blocks' distance is easily a whole cell out — with a 3x3 tool that means the
	 * slice lands off-centre. A raycast cannot be off by a fraction of a cell: either
	 * the ray lands in that cell or it does not.
	 *
	 * <p>Note on timing: the engine ticks at {@code END_CLIENT_TICK}, after the game
	 * has already updated the crosshair and run {@code handleBlockBreaking} this tick.
	 * So this reads the aim as of the rotation set last tick — which is exactly the aim
	 * breaking used this tick. The lag can only ever delay arming, never arm on a
	 * crooked aim, which is the safe direction.
	 */
	private boolean crosshairOn(BlockPos pos) {
		return client.crosshairTarget instanceof BlockHitResult hit
				&& hit.getType() == HitResult.Type.BLOCK
				&& hit.getBlockPos().equals(pos);
	}

	/**
	 * Normal mining, one face at a time and strictly in order. Each face runs through
	 * {@link Phase}: get to the stand position, find another stance if the middle
	 * can't be seen, line the crosshair up on it, and only then take out the pickaxe.
	 *
	 * <p>Two rules make this different from what it replaced, both the user's:
	 * <ol>
	 *   <li><b>The pickaxe comes out last.</b> Not when we arrive, not when the angle
	 *       looks close — only once {@link #crosshairOn} confirms the real crosshair is
	 *       on that exact cell.</li>
	 *   <li><b>A face is never abandoned.</b> Being unable to reach it <em>now</em> is
	 *       not a reason to walk off to another face; we stand still and keep at it. The
	 *       only advance is when the face has genuinely no work left, which is what
	 *       stops the loop being infinite.</li>
	 * </ol>
	 *
	 * <p>Only the middle cell is ever aimed at. The pickaxe takes a 3x3 slice, so
	 * hitting the centre takes the whole face; the ring is the final sweep's job.
	 */
	private void tickFaces(World world) {
		// Faces with nothing to do are stepped over — this is the one advance that
		// remains, and without it the cursor would sit on an empty face forever.
		dodgedPlayer = false;
		int examined = 0;
		while (!faceHasWork(world) && !plan.areLayerFacesDone() && examined++ < SKIP_BUDGET) {
			plan.advance();
		}
		if (!faceHasWork(world)) {
			note = dodgedPlayer ? "né acc khác — để vét sau" : "kiểm tra tầng";
			return;
		}

		BlockPos centre = plan.faceCenter();
		if (!centre.equals(phaseFace)) {
			// A genuinely different face: start it from the beginning. This is the only
			// place phaseFace is set, so the reset happens exactly once per face — an
			// earlier version also cleared it inside restartFace(), which re-triggered
			// this branch every tick and pinned the face on APPROACH forever.
			restartFace();
			clearTarget();
			phaseFace = centre.toImmutable();
		}
		phaseTicks++;

		// The cell being worked right now — the centre until it breaks, then the
		// rest of the face row by row, all from the same stance.
		BlockPos cell = faceWorkCell(world);
		if (cell == null) {
			return; // face finished this very tick; the cursor advances next tick
		}

		switch (phase) {
			case APPROACH -> approachFace(world, centre);
			case REPOSITION -> repositionForCentre(world, cell);
			case AIM_LOCK -> aimAtCentre(world, cell);
			case DIG_LOCKED -> digLocked(world, cell);
		}
	}

	/**
	 * Only dig a face while the view to its middle is essentially <b>level</b>.
	 * Reachable is not enough: from a tower top or the box surface every middle
	 * sits well below the eyes, and "digging whatever is in reach" from up there
	 * was a steep nod down(-left, as the row trails away) after every single
	 * swing — the "đào xong lại cúi xuống bên trái". Standing at the stance
	 * height always passes, however thin the layer, so this can never ping-pong
	 * with APPROACH: from the proper stance the geometry is as level as it gets.
	 */
	private boolean faceAimLevel(ClientPlayerEntity player, BlockPos centre) {
		if (player.getBlockY() == plan.layerBottom()) {
			return true;
		}
		Vec3d eye = player.getEyePos();
		double dy = centre.getY() + 0.5 - eye.y;
		double horizontal = Math.hypot(centre.getX() + 0.5 - eye.x, centre.getZ() + 0.5 - eye.z);
		return Math.abs(Math.toDegrees(Math.atan2(dy, horizontal))) <= 30.0;
	}

	/** Walk to the spot this face is dug from. Never gives up on the face. */
	private void approachFace(World world, BlockPos centre) {
		// Already in range and sight of the middle, WITH a level view? Dig from
		// RIGHT HERE — the canonical stand cell is a nicety for walking rows, not
		// a requirement ("đào ngay trước mặt nếu có block luôn"). Two big wins:
		// on solid rows, several faces get eaten from one stance and the walk in
		// between happens through the finished, open tunnel — no boring, no dips;
		// and floating ore blobs become workable at all, because insisting on the
		// mid-air stand cell meant stepping into the void after every face. The
		// level check keeps this shortcut honest: high ground never digs down.
		ClientPlayerEntity player = client.player;
		if (player != null && breaker.canReach(centre, config.reachDistance)
				&& faceAimLevel(player, centre)) {
			input.stop();
			enterPhase(Phase.AIM_LOCK);
			return;
		}
		BlockPos goal = useEntryStance ? plan.entryPos() : plan.standPos();
		MoveController.Result result = mover.moveTo(goal);
		if (result == MoveController.Result.MOVING) {
			note = mover.isClimbing() ? "đang leo lên"
					: useEntryStance ? "đào giếng vào mặt" : "tới chỗ đứng";
			activeTarget = null;
			breaker.cancel(); // Đảm bảo không đào khi đang di chuyển
			return;
		}
		if (result == MoveController.Result.BLOCKED) {
			// Blocked by lava on the way? Plug it and try the same route again —
			// without this, a face on the far side of a lava pocket simply never
			// got approached ("9 ô ở xa" mà không tự đào tới được).
			BlockPos lava = mover.lavaObstacle();
			if (lava != null && startFluidWork(world, lava)) {
				note = "lấp dung nham mở đường";
				mover.reset();
				return;
			}
			if (!useEntryStance) {
				// The proper stand cell can't be reached — at a row start it sits
				// outside the box under rock we may not break. Drop down the face's
				// own column instead; the descent eats the middle, the cursor moves
				// on, and the next faces are worked normally from inside the row.
				useEntryStance = true;
				mover.reset();
				note = "đào giếng vào mặt";
				return;
			}
			// Can't walk the planned route. Try another stance that can see the middle
			// before resorting to building, which is what turns the head down.
			note = "không tới được chỗ đứng — tìm chỗ khác";
			enterPhase(Phase.REPOSITION);
			return;
		}
		// Đã đến nơi - dừng input để BlockBreaker có thể kiểm soát rotation.
		// Luôn vào AIM_LOCK: còn việc thì ngắm tâm, hết việc thì đứng yên cho
		// tickFaces đẩy con trỏ đi — cả hai đều là "đứng im và để mắt lên mặt đào".
		input.stop();
		enterPhase(Phase.AIM_LOCK);
	}

	/**
	 * Stand still and turn until the real crosshair lands on the middle of the face.
	 * Nothing is broken here: {@link BlockBreaker#aimOnly} leaves
	 * {@link BlockBreaker#aiming()} null, so {@code shouldForceBreaking()} is false and
	 * the mixin cannot swing.
	 */
	private void aimAtCentre(World world, BlockPos cell) {
		input.stop();
		activeTarget = null;

		if (!needsDigging(world, cell)) {
			// Gone while we were lining up. Nothing to do: tickFaces hands over the
			// face's next cell (or advances the cursor) on the very next tick.
			return;
		}
		if (startFluidWorkForFace(world)) {
			return; // seal the water/lava first; tickFluidFill owns the next ticks
		}
		if (!breaker.aimOnly(cell, config.reachDistance)) {
			// No line to it from here. Walk to a spot that can see it — moving is the
			// natural answer to "can't see it", and it keeps the view level. Building was
			// tried here before and was wrong twice over: it aims at the floor, which
			// dipped the head after every swing, and when it ran out of cells the engine
			// simply stood still forever ("Kẹt: chưa ngắm được tâm").
			enterPhase(Phase.REPOSITION);
			return;
		}
		if (crosshairOn(cell)) {
			enterPhase(Phase.DIG_LOCKED);
			return;
		}
		note = phaseTicks > SLOW_NOTE_TICKS
				? "ngắm mãi chưa được (" + (phaseTicks / 20) + "s)"
				: "ngắm ô đào";
	}

	/**
	 * Can't see the middle from here, so go and stand somewhere that can — the smart
	 * movement the user asked for ("tìm cách di chuyển thông minh mà đào") in place of
	 * building a scaffold and staring at the floor.
	 *
	 * <p>Candidate stances come from {@link #standNear}, which already vets each one by
	 * raycasting from that spot's eye height, so we only ever walk somewhere the block
	 * is genuinely visible from. Every stance we have already tried and failed at is
	 * remembered in {@link #triedStances}, so the search moves on instead of pacing
	 * between the same two spots.
	 *
	 * <p>Only when no stance anywhere can see it do we fall back to building — that is
	 * the one time a downward look is genuinely needed, which is exactly the rule the
	 * user set: turn the head down only when a block really must be placed.
	 */
	private void repositionForCentre(World world, BlockPos cell) {
		ClientPlayerEntity player = client.player;
		if (player == null) {
			return;
		}
		if (breaker.canReach(cell, config.reachDistance)) {
			enterPhase(Phase.AIM_LOCK); // it came into view on the way
			return;
		}
		if (repositionTarget == null) {
			repositionTarget = standNear(world, player, cell);
			if (repositionTarget == null) {
				// Every stance has been tried. Leave the face to the final sweep rather than
				// standing here forever — and rather than building, which the user has ruled
				// out ("bỏ cái đặt điểm tựa đi").
				note = "không có chỗ đứng nào thấy tâm — để vét sót lo";
				plan.advance();
				restartFace();
				return;
			}
			mover.reset(); // fresh route to the new stance
		}

		note = "đi tìm chỗ đứng thấy tâm";
		MoveController.Result result = mover.moveTo(repositionTarget);
		if (result == MoveController.Result.ARRIVED) {
			triedStances.add(repositionTarget);
			repositionTarget = null;
			enterPhase(Phase.AIM_LOCK);
		} else if (result == MoveController.Result.BLOCKED) {
			triedStances.add(repositionTarget); // can't get there; don't pick it again
			repositionTarget = null;
		}
	}

	/**
	 * Confirmed on target: pickaxe out and let vanilla break it. If the crosshair drifts
	 * off we {@link BlockBreaker#disarm()} and go back to lining up, which keeps the aim
	 * point stable so the next lock settles on the same spot.
	 */
	private void digLocked(World world, BlockPos cell) {
		input.stop();

		if (!needsDigging(world, cell)) {
			// Gone. Nothing to settle and nothing to re-decide: the stance has not moved, so
			// stay put — tickFaces hands over the face's next cell (an adjacent block, a
			// small turn) or advances the cursor on the very next tick.
			breaker.disarm();
			activeTarget = null;
			return;
		}
		if (startFluidWorkForFace(world)) {
			// Fluid crept in mid-dig (a swing opened a wall). Stop the pickaxe at once.
			breaker.disarm();
			activeTarget = null;
			return;
		}
		if (!crosshairOn(cell)) {
			breaker.disarm();
			activeTarget = null;
			enterPhase(Phase.AIM_LOCK);
			return;
		}
		if (!cell.equals(activeTarget)) {
			setTarget(cell);
		}
		if (!breaker.tickArmed(cell, config.reachDistance)) {
			breaker.disarm();
			note = "mất đường ngắm — tìm chỗ đứng khác";
			enterPhase(Phase.REPOSITION);
			return;
		}
		// A hard block is work in progress, not a failure: say so and keep going. The
		// old code abandoned the face here after TICKS_PER_BLOCK.
		note = phaseTicks > SLOW_NOTE_TICKS
				? "đào lâu (" + (phaseTicks / 20) + "s) — vẫn đang đào"
				: "đào mặt " + plan.faceWidth() + "x" + plan.faceHeight();
	}

	/**
	 * Warn about missing building blocks, then let the caller stand and wait. The flag
	 * clears itself once the hotbar has blocks again, so a second dry spell later in the
	 * run is reported too instead of failing silently.
	 */
	private void warnNoBlocks(String text) {
		if (BlockPlacer.hasBuildingBlock(client.player)) {
			warnedNoBlocks = false;
			return;
		}
		if (!warnedNoBlocks) {
			warnedNoBlocks = true;
			message(text);
		}
	}

	/**
	 * Is this face worth stopping at? <b>Only when all nine cells are solid.</b>
	 *
	 * <p>This is the user's rule, and it replaces the old patch-the-middle behaviour
	 * outright: "tập trung vào 9 block, cái nào ko có 9 block thì bỏ qua luôn". A full
	 * face is one clean swing at the middle that takes the whole 3x3. A face with gaps
	 * is skipped entirely and its leftovers are collected by the final sweep, which is
	 * free to approach them from any angle.
	 *
	 * <p>Two problems disappear with it. Placing a block into a gap meant looking at the
	 * floor to place it, which is the head-dipping after every swing the user kept
	 * seeing. And a partly-empty face gave the 3x3 nothing solid to centre on, so the
	 * bot worked the ring cell by cell and wandered off the tidy row order.
	 */
	private boolean faceHasWork(World world) {
		if (plan.areLayerFacesDone()) {
			return false;
		}
		if (faceWorkCell(world) == null) {
			return false; // every cell of it is already gone (or not ours to dig)
		}
		// Another account is standing at this face: it's theirs. Step past it —
		// whatever they leave behind is collected by this layer's sweep.
		if (otherPlayerNear(plan.faceCenter(), PLAYER_AVOID_RADIUS)) {
			dodgedPlayer = true;
			return false;
		}
		return true;
	}

	/**
	 * The cell of the current face to dig: <b>the middle, and only ever the
	 * middle</b>. The user's pickaxe takes the whole 3x3 in that one swing —
	 * "đã đào tâm 9 ô thì không đào ở đó bao giờ nữa". The ring cells often
	 * survive on the client for a few ticks after the swing (the server's AoE
	 * confirms late), and an earlier version that "finished the face" cell by
	 * cell kept turning left to dig a block that was already gone. Anything the
	 * swing genuinely leaves behind is the layer sweep's job, at the end.
	 */
	private BlockPos faceWorkCell(World world) {
		BlockPos centre = plan.faceCenter();
		return needsDigging(world, centre) ? centre : null;
	}

	// ---- helpers ----

	private void setTarget(BlockPos pos) {
		activeTarget = pos.toImmutable();
	}

	private void clearTarget() {
		clearTargetKeepingStance();
		if (mover != null) {
			mover.reset();
		}
	}

	/**
	 * Drop the block being worked on but <b>keep the stance</b> — specifically the
	 * mover's record of having arrived at the stand position.
	 *
	 * <p>This split fixes the double head-turn the user reported ("đào ở giữa xong lại
	 * hướng lên trên, đầu cứ quay 2 lần"). {@link #clearTarget()} runs the instant a
	 * block breaks, and the {@code mover.reset()} inside it wiped {@code arrivedAt}; the
	 * next tick the mover therefore no longer believed it was standing on the right
	 * block, so it re-aimed the view at the ground underfoot before the engine turned it
	 * back up at the next thing to dig. Two visible turns where one was wanted. After a
	 * break we are still standing exactly where we chose to stand, so there is nothing
	 * to reset.
	 */
	private void clearTargetKeepingStance() {
		activeTarget = null;
		breaker.cancel();
	}

	/**
	 * Whether another player (one of the user's other accounts, usually) stands
	 * within {@code radius} of {@code pos}. Their patch — we work somewhere else.
	 */
	private boolean otherPlayerNear(BlockPos pos, double radius) {
		if (!config.avoidPlayers || client.world == null || pos == null) {
			return false;
		}
		for (PlayerEntity other : client.world.getPlayers()) {
			if (other == client.player) {
				continue;
			}
			if (other.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)
					<= radius * radius) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Decide the dig direction for a fresh plan: if another account is already
	 * working near the default starting corner (min/min), start from the opposite
	 * one so the two eat toward each other instead of fighting over the same rows.
	 */
	private boolean shouldMirror(World world) {
		if (!config.avoidPlayers || world == null) {
			return false;
		}
		double defaultCorner = Double.MAX_VALUE;
		double mirroredCorner = Double.MAX_VALUE;
		for (PlayerEntity other : world.getPlayers()) {
			if (other == client.player) {
				continue;
			}
			BlockPos pos = other.getBlockPos();
			if (pos.getX() < selection.minX() - PLAYER_NEAR_BOX || pos.getX() > selection.maxX() + PLAYER_NEAR_BOX
					|| pos.getZ() < selection.minZ() - PLAYER_NEAR_BOX || pos.getZ() > selection.maxZ() + PLAYER_NEAR_BOX
					|| pos.getY() < selection.minY() - PLAYER_NEAR_BOX || pos.getY() > selection.maxY() + PLAYER_NEAR_BOX) {
				continue; // not at the dig site at all
			}
			int top = selection.maxY();
			defaultCorner = Math.min(defaultCorner,
					other.squaredDistanceTo(selection.minX() + 0.5, top + 0.5, selection.minZ() + 0.5));
			mirroredCorner = Math.min(mirroredCorner,
					other.squaredDistanceTo(selection.maxX() + 0.5, top + 0.5, selection.maxZ() + 0.5));
		}
		// Mirror only when someone is genuinely closer to our default corner.
		return defaultCorner < mirroredCorner;
	}

	private boolean needsDigging(World world, BlockPos pos) {
		// The 3x3 slice around an aim point can reach past the rim, so cells are no
		// longer guaranteed to be inside the box — check before treating one as ours.
		if (!selection.contains(pos) || givenUp.contains(pos) || world.getBlockState(pos).isAir()) {
			return false;
		}
		if (!BlockUtil.isBreakable(world, pos)) {
			return false; // bedrock and friends
		}
		if (config.avoidLava && BlockUtil.lavaAdjacent(world, pos)) {
			// With fillFluids the lava next to it gets plugged before the swing, so
			// the cell still counts as work; without it, skip it as before.
			return config.fillFluids;
		}
		return true;
	}

	/**
	 * A spot to stand to work on {@code target}, <b>preferring one block back</b> rather
	 * than right up against it.
	 *
	 * <p>Standing in the cell touching the target is what the user hit at the end of the
	 * job ("lao vào tâm giữa sao đặt được"): from there the block is at your feet or
	 * inside your own body, so there is no room to place into the cell that needs a
	 * middle, and no angle to aim at it either. A gap of one cell puts the whole 3x3 in
	 * front of the eyes with space to place — while staying comfortably inside the 4.5
	 * block reach.
	 *
	 * <p>Adjacent spots are still accepted as a fallback, and standing on top of the
	 * block last of all, so a leftover wedged in a corner can still be dealt with.
	 */
	private BlockPos standNear(World world, ClientPlayerEntity player, BlockPos target) {
		// Two passes: one cell back first, then touching. The first spot found in the
		// earlier pass always wins, however far the player currently is from it.
		for (int gap = 2; gap >= 1; gap--) {
			BlockPos best = null;
			double bestScore = Double.MAX_VALUE;
			for (Direction dir : Direction.Type.HORIZONTAL) {
				for (int dy = -1; dy <= 1; dy++) {
					BlockPos candidate = target.offset(dir, gap).up(dy);
					if (triedStances.contains(candidate)) {
						continue; // been there, couldn't work from it
					}
					if (!BlockUtil.standable(world, candidate)) {
						continue;
					}
					// A stance that cannot see the block is no use, whatever its distance.
					if (!breaker.canReachFrom(candidate, target, config.reachDistance)) {
						continue;
					}
					// Prefer the stance whose *eyes* sit level with the block, which is the
					// one a block lower than the target: eye height is ~1.62, so feet at
					// target.y - 1 puts the eyes at y+0.62 against a block centre of y+0.5 —
					// dead level, and the head never tips. Feet level with the target already
					// look down about 30 degrees, and feet above it look down far more. The
					// old code did the opposite: it *penalised* the level stance (dy == 0 got
					// +4) and so kept choosing the highest spot available, which is why every
					// swing in the sweep was taken looking at the floor.
					double score = candidate.getSquaredDistance(player.getBlockPos())
							+ switch (dy) {
								case -1 -> 0;   // eyes level with the block
								case 0 -> 24;   // looking down a little
								default -> 96;  // looking down steeply
							};
					if (score < bestScore) {
						bestScore = score;
						best = candidate;
					}
				}
			}
			if (best != null) {
				return best;
			}
		}
		// Standing on top of it works too, when nothing beside it does.
		BlockPos above = target.up();
		if (!triedStances.contains(above) && BlockUtil.standable(world, above)) {
			return above;
		}
		return null;
	}

	private void message(String text) {
		if (client.player != null) {
			client.player.sendMessage(Text.literal("§b[AutoMine] §r" + text), false);
		}
	}
}
