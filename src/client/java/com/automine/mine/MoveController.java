package com.automine.mine;

import com.automine.config.AutoMineConfig;
import com.automine.util.BlockBreaker;
import com.automine.util.BlockPlacer;
import com.automine.util.BlockUtil;
import com.automine.util.Rotations;
import com.automine.util.SimInput;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
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
	 *
	 * <p>12 tick (0.6s), hạ từ 30: mọi nơi cần đứng CHUẨN TÂM giờ đều có máy căn
	 * riêng phía engine (chốt căn giếng, luật thẳng hàng của mặt), nên nấn ná ở
	 * đây chỉ là đứng dậm chân — "di chuyển nhanh như người bình thường" nghĩa là
	 * tới nơi thì vào việc luôn.
	 */
	private static final int CENTER_LIMIT = 12;
	/** Within this distance we creep, for accuracy. */
	private static final double CREEP_RANGE = 1.2;
	/** Đứng ì bấy nhiêu tick (không đào, không nhích) thì tuyên bố TẮC — 2.5s,
	 *  hạ từ 4s: kẹt là nhả BLOCKED sớm cho engine xoay bài khác (dọn chướng
	 *  ngại, 3 nhát thẳng, đổi chỗ đứng) thay vì đứng nhìn tường thêm. */
	private static final int STALL_LIMIT = 50;
	private static final int REPATH_INTERVAL = 40;
	/**
	 * Lệch hướng dưới ngần này thì KHÔNG quay đầu — xem {@link #faceWalkDirection}.
	 *
	 * <p>8°: qua một block chỉ trôi ngang ~0.14, mà tới gần node góc lệch tự phình
	 * ra nên vẫn được chỉnh trước khi lạc. Đổi lại, cả đoạn đường thẳng đầu đứng
	 * yên tuyệt đối thay vì rung từng tick.
	 */
	private static final float WALK_YAW_DEADZONE = 8.0F;
	/** Tốc độ quay khúc cua (độ/tick) — góc vuông hết ~8 tick, mượt như tay người. */
	private static final float WALK_TURN_DEGREES = 12.0F;
	/** Gần node hơn ngần này thì hướng tới nó là nhiễu, đừng quay theo. */
	private static final double NO_TURN_RANGE = 0.75;
	/**
	 * Cua gắt hơn ngần này thì ĐỨNG LẠI QUAY rồi mới chạy tiếp.
	 *
	 * <p>Quay mượt mà vẫn ghì phím tiến nghĩa là suốt khúc cua thân chạy theo
	 * hướng CŨ — cua 180° là gần một giây lao ngược, đủ để rơi xuống hố vừa đào.
	 * Người chơi thật cũng khựng lại nửa nhịp khi phải quay ngoắt.
	 */
	private static final float SHARP_TURN_DEGREES = 50.0F;
	/**
	 * Mũi phải nằm trong nón này quanh hướng đục thì mới được ghì phím tiến vào
	 * vách. Lấy đúng con số 40° mà {@code QuarryEngine.pressIntoTarget} đang dùng
	 * cho cùng một việc — ghì thân vào block đang bổ — để hai nơi cư xử như một.
	 */
	private static final float WALK_DIG_CONE_DEGREES = 40.0F;
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
	/**
	 * Bấm đi tới bấy nhiêu tick mà thân không nhúc nhích thì coi là kẹt và KÊ
	 * BLOCK qua chỗ hụt. 6 tick ≈ 0.3 giây: đủ chắc là kẹt thật, chưa đủ lâu để
	 * thành đứng hình.
	 */
	private static final int SNAG_BRIDGE_TICKS = 6;
	/** Dưới ngần này block mỗi tick coi như đứng yên (đi bộ thường ~0.13/tick). */
	private static final double SNAG_MOVE_EPS = 0.0016;
	/** Kê block tối đa chừng này tick rồi buông, khỏi ôm tick thành đứng hình. */
	private static final int BRIDGE_PATIENCE = 40;

	/**
	 * Failed hops in a row before the roof overhead is taken seriously. Three, per
	 * the user: a block that merely looks solid often still leaves room for a
	 * capped jump to squeeze the placement in, so digging any earlier just waves
	 * the pickaxe about.
	 */
	private static final int HOPS_BEFORE_CEILING_DIG = 3;
	/** How long one ceiling block may take before the column is written off (10s). */
	private static final int CEILING_DIG_LIMIT = 200;
	/**
	 * Ceilings broken back-to-back without a single block going down underneath.
	 * A climb is supposed to gain height; boring straight up forever means this
	 * column is hopeless (a pit under a deep overhang) and another one is nearer.
	 */
	private static final int MAX_CEILING_DIGS = 6;

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
	 * Latched: the climb is in its DIGGING half, chewing the block over its head.
	 *
	 * <p>Everything about the ceiling dig hangs off this one flag, and that is the
	 * whole point. The bug the user kept hitting ("vừa cầm cúp vừa cầm block khiến
	 * bị lặp lại và không làm được gì") is a hand fought over by two pieces of code
	 * in the same tick: the placer selects a block slot every tick it runs, and
	 * {@code tickArmed} selects the pickaxe every tick it runs, so alternating
	 * between them swaps the hotbar twice a tick and neither the placement nor the
	 * swing ever completes. With this flag, a climbing tick belongs to exactly one
	 * of the two — pickaxe until the roof is gone, block after — and the swap
	 * happens once per phase instead of once per tick.
	 */
	private boolean breakingCeiling;
	/** The block over the head being removed, while {@link #breakingCeiling}. */
	private BlockPos ceilingTarget;
	private int ceilingTicks;
	/** Ceilings dug in a row without a successful hop in between. */
	private int ceilingDigs;
	/**
	 * The horizontal axis we're currently boring along, held until the offset on it
	 * runs out. Recomputing "whichever axis is furthest" every tick made the tunnel
	 * alternate axes step by step and come out as a diagonal staircase.
	 */
	private Direction.Axis digAxis;

	/** Floor of the layer being worked on: below this means "climb, don't burrow". */
	private int digMinY = Integer.MIN_VALUE;
	/**
	 * Hàng GIỮA của tầng — đúng cái hàng mà mặt 3x3 lấy tâm.
	 *
	 * <p>Đào đường đi bám theo hàng này chứ không theo tầm ngực của thân: bổ vào
	 * ô giữa thì cúp 3x3 ăn trọn cột 3 ô (dưới - giữa - trên) của tầng, y hệt
	 * một nhát đào mặt 9 ô. Nhờ vậy đường hầm mở ra cũng cao đúng 3 ô, mắt luôn
	 * nhìn ngang, không còn cảnh cúi xuống bổ ô dưới chân.
	 */
	private int digAimY = Integer.MIN_VALUE;
	/** Tầm với engine bơm vào (đã gộp tầm server); 0 = chưa có, dùng config. */
	private double digReach;

	/** Vị trí tick trước, chỉ dùng cho việc phát hiện "đang đi mà không nhúc nhích". */
	private double lastWalkX = Double.NaN;
	private double lastWalkZ;
	/** Số tick liên tiếp bấm đi tới mà thân đứng yên. */
	private int snagTicks;
	/**
	 * Đang quay dở một khúc cua: phải quay cho xong mới thôi.
	 *
	 * <p>Không có chốt này thì bộ lọc {@link #WALK_YAW_DEADZONE} sẽ nhả tay ngay
	 * khi còn lệch 8° — đầu dừng lửng lơ giữa khúc cua rồi tick sau lại thấy "lệch
	 * quá" mà quay tiếp, thành ra giật cục đúng cái thứ đang muốn bỏ.
	 */
	private boolean turningCorner;
	/** Số tick liên tiếp đang kê block gỡ kẹt. */
	private int bridgeTicks;






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
		breakingCeiling = false;
		ceilingTarget = null;
		ceilingTicks = 0;
		ceilingDigs = 0;
		digAxis = null;
		snagTicks = 0;
		bridgeTicks = 0;
		turningCorner = false;
		lastWalkX = Double.NaN;
		placer.reset();
	}

	/**
	 * Những ô mà mod TỰ KÊ ra gần đây (trụ leo lên, viên bắc qua chỗ hụt sàn).
	 *
	 * <p>Engine tra sổ này trước khi chọn ô để đào: kê xong rồi đào lại đúng viên
	 * đó là tự rút sàn dưới chân mình, rơi xuống, kê lại, đào lại — vòng lặp user
	 * báo ("đặt block xong lại đào block đó cứ lặp lại").
	 */
	public java.util.Set<BlockPos> placedCells() {
		return placer.placedCells();
	}

	/** Quên sổ ô tự kê (sang tầng mới / bắt đầu lại). */
	public void forgetPlaced() {
		placer.forgetPlaced();
	}

	/**
	 * Whether a tower-up is still in progress. Interrupting one to go dig something
	 * drops the player straight back into the hole, so callers must let it finish.
	 */
	public boolean isClimbing() {
		return climbing;
	}

	/** True while the climb is chewing the roof above the head, for the status line. */
	public boolean isBreakingCeiling() {
		return breakingCeiling;
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
	 * Run one tick of towering up. A climb has two halves and a tick belongs to
	 * exactly one of them:
	 *
	 * <ul>
	 *   <li><b>Placing</b> — block in hand, look down, hop, drop one underneath.</li>
	 *   <li><b>Digging</b> — after {@link #HOPS_BEFORE_CEILING_DIG} hops that came
	 *       back down with nothing placed, the head is under a roof: hold still,
	 *       look UP, take the pickaxe out and break the block above. When it is
	 *       gone the hand goes back to the block and the climb carries on upward
	 *       until it reaches the dig point — the user's "nhảy 3 lần mà không đặt
	 *       được thì hướng lên trên, cầm cúp phá, xong quay về cầm block đặt tiếp".</li>
	 * </ul>
	 *
	 * @return true when the tick was consumed by the climb; false when placing is
	 *         off, there is nothing to build with, or this column is hopeless and
	 *         the caller should relocate.
	 */
	private boolean tryPillarTick(ClientPlayerEntity player) {
		if (!config.allowPlace) {
			return false;
		}
		// DIGGING half. Latched, so it keeps the hand until the roof is actually
		// gone — never a tick of pickaxe followed by a tick of block.
		if (breakingCeiling) {
			return tickCeilingDig(player);
		}
		// Head under a roof: switch halves rather than hopping into it forever.
		if (placer.failedHops() > HOPS_BEFORE_CEILING_DIG) {
			if (startCeilingDig(player)) {
				return tickCeilingDig(player);
			}
			// Nothing breakable up there (outside the box, bedrock, lava above):
			// this column simply can't rise — let the caller find a nicer one.
			climbing = false;
			return false;
		}
		// PLACING half. The mixin breaks whatever the crosshair rests on, and we're
		// about to look straight down at the block we intend to stand on.
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
		if (placer.failedHops() == 0) {
			ceilingDigs = 0; // the tower is rising again — the roof budget resets
		}
		climbing = true;
		input.set(0.0F, 0.0F, jump[0], false);
		return true;
	}

	/**
	 * Take aim at the block over the head, if there is one worth breaking.
	 *
	 * @return true when the climb has switched to its digging half.
	 */
	private boolean startCeilingDig(ClientPlayerEntity player) {
		World world = client.world;
		if (world == null || ceilingDigs >= MAX_CEILING_DIGS) {
			return false;
		}
		// Standing at Y the body fills Y and Y+1, so the block that stops a jump —
		// and the one the placement needs out of the way — is Y+2.
		BlockPos above = player.getBlockPos().up(2);
		if (BlockUtil.passable(world, above) || !BlockUtil.isBreakable(world, above)) {
			return false;
		}
		if (!selection.contains(above, TRAVEL_DIG_MARGIN)) {
			return false; // outside the job: not ours to break, even to get out
		}
		breakingCeiling = true;
		ceilingTarget = above.toImmutable();
		ceilingTicks = 0;
		ceilingDigs++;
		return true;
	}

	/**
	 * One tick of the digging half: stand still, aim up, let vanilla chew. Nothing
	 * here touches the placer, so the hotbar keeps the pickaxe for the whole dig.
	 */
	private boolean tickCeilingDig(ClientPlayerEntity player) {
		World world = client.world;
		if (world == null || ceilingTarget == null) {
			return endCeilingDig(false);
		}
		climbing = true;  // still a climb: the engine must not steal the tick
		input.stop();     // no hopping into a block we are in the middle of breaking

		if (BlockUtil.passable(world, ceilingTarget)) {
			// Roof is open. Hand the tick back to the placing half with a clean
			// slate, so the three-failed-hops counter doesn't instantly send us
			// back here on a column that can now rise.
			placer.reset();
			return endCeilingDig(true);
		}
		if (++ceilingTicks > CEILING_DIG_LIMIT || !breaker.tickArmed(ceilingTarget, reach())) {
			return endCeilingDig(false);
		}
		return true;
	}

	/**
	 * Leave the digging half. {@code keepClimbing} false means the column is a
	 * dead end and the caller should relocate.
	 */
	private boolean endCeilingDig(boolean keepClimbing) {
		breakingCeiling = false;
		ceilingTarget = null;
		ceilingTicks = 0;
		breaker.cancel();
		if (!keepClimbing) {
			climbing = false;
		}
		return keepClimbing;
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

	public Result moveTo(BlockPos target) {
		ClientPlayerEntity player = client.player;
		World world = client.world;
		if (player == null || world == null) {
			return Result.BLOCKED;
		}

		// A ceiling dig in progress outranks everything, including a walkable route
		// that happens to exist: half of it is a pickaxe swing that any other branch
		// would cancel, and a dig cancelled every other tick never finishes.
		if (breakingCeiling) {
			return tickCeilingDig(player) ? Result.MOVING : Result.BLOCKED;
		}

		BlockPos feet = player.getBlockPos();
		// CÚ LEO CHỈ XONG KHI ĐÃ ĐẶT CHÂN XUỐNG ĐẤT ở độ cao đích, không phải
		// lúc chân THOÁNG ngang đích giữa cú nhảy.
		//
		// Bản cũ nhả cờ ngay khi feet.getY() >= đích — mà đỉnh mỗi cú nhảy đều
		// chạm mốc đó một hai tick. Engine thấy "hết leo" là giật tick sang ngắm
		// mặt đào (rút CÚP, input.stop giết luôn cú nhảy), viên block đang chờ đặt
		// bị bỏ rơi, thân rơi lại xuống, lại leo, lại bị giật — chính là cảnh
		// "block-cúp bị lặp lại khiến không đặt được" user quay được khi đứng
		// dưới mặt đào đúng 1 block. Chốt theo isOnGround thì cả cú nhảy thuộc
		// về cú leo, không ai chen được vào giữa.
		if (climbing && feet.getY() >= target.getY() && player.isOnGround()) {
			climbing = false;
		}
		// Trụ đang xây dở thì SỞ HỮU TRỌN TICK — kể cả mấy tick lơ lửng trên
		// không. Đi tìm đường bộ hay đào ngang lúc này là mất nhịp nhảy-đặt của
		// placer (nó đang ở pha RISING) và cái tay lại bị giành.
		if (climbing) {
			if (tryPillarTick(player)) {
				return Result.MOVING;
			}
			// Trụ không lên nổi (hết block, trần chặn quá cữ): trả BLOCKED cho
			// engine tìm chỗ đứng khác — tryPillarTick đã tự hạ cờ.
			return Result.BLOCKED;
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

		// (Trụ đang xây dở đã được chặn ở đầu hàm — tới đây là chắc chắn không leo.)

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
			// Đi vòng qua chỗ TRỐNG vẫn hơn là bổ xuyên đá: user muốn "ra chỗ
			// trống mà đi cho dễ, chỗ nào khó mới cần đào tới đó". Nên ngưỡng bỏ
			// lộ trình được nới rộng — chỉ khi đường bộ dài gấp ba lần đường chim
			// bay mới coi là vòng vo vô ích và chuyển sang khoan thẳng. Đi bộ 4
			// block nhanh hơn đào 1 block deepslate rất nhiều.
			if (path != null && !path.isEmpty()) {
				int straight = Math.abs(target.getX() - feet.getX())
						+ Math.abs(target.getY() - feet.getY())
						+ Math.abs(target.getZ() - feet.getZ());
				if (path.size() > straight * 3 + 12) {
					path = null;
				}
			}
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
		// Sprint is judged against the GOAL, not the next node: nodes sit one
		// block apart, so the old per-node distance test could never trip and the
		// bot walked everywhere. Far out = auto-run; inside ~3 blocks ease off and
		// walk in — "chạy nhanh tới chỗ, tới nơi thì đi chậm, cứ thế lặp lại".
		// Chạy sớm hơn: trước phải cách đích >3 block mới cho sprint, giờ chỉ cần
		// >1.6 block. Trong hầm hẹp, đoạn 2-3 block giữa hai mặt đào chiếm phần
		// lớn thời gian di chuyển — đi bộ hết cả đoạn đó là chậm thấy rõ.
		boolean farFromGoal = horizontalDistSq(player, target) > 2.5;
		stepTowardCenter(player, node, lastNode, stepUp, farFromGoal);
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
	private void stepTowardCenter(ClientPlayerEntity player, BlockPos node, boolean precise, boolean stepUp,
			boolean farFromGoal) {
		double dx = node.getX() + 0.5 - player.getX();
		double dz = node.getZ() + 0.5 - player.getZ();
		double distSq = dx * dx + dz * dz;

		breaker.cancel(); // purely walking; don't let the mixin break anything

		if (precise && distSq <= HOLD_YAW_RANGE * HOLD_YAW_RANGE) {
			// Tới sát đích rồi: bỏ luôn khúc cua đang quay dở. Giữ chốt ở đây là
			// tự cho phép mình quay đầu ngay trên ô đích — đúng cái xoay tại chỗ
			// mà {@link #HOLD_YAW_RANGE} sinh ra để dập.
			turningCorner = false;
			creepInPlace(player, dx, dz);
			return;
		}

		// Sát node thì hướng tới nó chỉ còn là nhiễu (lệch vài phần trăm block ra
		// góc mấy chục độ) — đang quay dở khúc cua thì quay cho xong, còn không
		// thì cứ đi thẳng, node kế tiếp mới là thứ quyết định hướng.
		if (turningCorner || distSq >= NO_TURN_RANGE * NO_TURN_RANGE) {
			if (faceWalkDirection(player, dx, dz) > SHARP_TURN_DEGREES) {
				// Cua ngoắt: đứng lại quay cho xong đã. Ghì tiến trong lúc thân còn
				// hướng cũ là chạy ngược đường suốt khúc cua.
				input.stop();
				snagTicks = 0; // đứng để quay, không phải kẹt — đừng bắt kê block
				return;
			}
		}

		// Kẹt vì hụt sàn thì kê một viên rồi bước qua (không nhảy — cơ chế nhảy
		// đã bỏ theo yêu cầu user).
		if (bridgeIfSnagged(player, Direction.getFacing(dx, 0.0, dz))) {
			input.stop();
			return;
		}

		boolean creep = precise && distSq <= CREEP_RANGE * CREEP_RANGE;
		boolean sprint = config.allowSprint && farFromGoal && !creep && !stepUp;
		input.set(1.0F, 0.0F, stepUp && player.isOnGround(), sprint);
	}

	/**
	 * Hướng mặt theo đường đi — <b>CHỈ QUAY KHI RẼ</b>.
	 *
	 * <p>Luật của user: "cần di chuyển tới chỗ rẽ mới xoay, không phải cứ xoay đầu
	 * hoài". Bản cũ mỗi tick lại {@code setYaw} về đúng tâm node kế tiếp: node cách
	 * nhau đúng một block nên chỉ cần thân trôi vài phần trăm là góc đã đổi, và đầu
	 * rung liên tục suốt quãng đường thẳng — dấu hiệu bot rõ nhất mà người ngoài
	 * nhìn thấy.
	 *
	 * <p>Hai tầng lọc:
	 * <ul>
	 *   <li>lệch dưới {@link #WALK_YAW_DEADZONE} thì KHÔNG đụng vào đầu — đi thẳng
	 *       hơi xiên vẫn tới nơi, vì tới gần node góc lệch tự lớn dần rồi mới chỉnh,
	 *       đúng kiểu người chơi chạy đường dài;</li>
	 *   <li>đã quyết định rẽ thì {@link #turningCorner} chốt lại, quay mượt
	 *       {@link #WALK_TURN_DEGREES} độ mỗi tick cho tới khi xong khúc cua — không
	 *       bẻ ngoặt một phát, cũng không bỏ dở giữa chừng.</li>
	 * </ul>
	 *
	 * @return góc còn phải quay TRƯỚC tick này (độ) — người gọi dùng để biết khi
	 *         nào nên đứng lại quay cho xong ({@link #SHARP_TURN_DEGREES}).
	 */
	private float faceWalkDirection(ClientPlayerEntity player, double dx, double dz) {
		float desired = Rotations.yawTo(dx, dz);
		float diff = Math.abs(MathHelper.wrapDegrees(desired - player.getYaw()));
		if (!turningCorner) {
			if (diff < WALK_YAW_DEADZONE) {
				return 0.0F; // vẫn coi như đúng hướng — giữ nguyên đầu
			}
			turningCorner = true;
		}
		turningCorner = Rotations.stepYawTo(player, desired, WALK_TURN_DEGREES);
		return diff;
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

	/**
	 * Đang bấm đi tới mà thân không nhúc nhích thì trả true đúng MỘT tick để
	 * nhảy — hệt như người chơi gặp bậc thềm hay góc tường thì nhảy phát qua.
	 *
	 * <p>Chỉ gọi từ các nhánh ĐI BỘ. Nhánh căn giữa ({@code creepInPlace}) cố ý
	 * nhích rất chậm, gọi ở đó thì tick nào cũng thấy "đứng yên" và bot sẽ nhảy
	 * loi choi ngay trên ô đích.
	 */
	/**
	 * Đang đi mà đứng yên quá lâu thì BẮC BLOCK qua chỗ hụt, không nhảy.
	 *
	 * <p>Cơ chế nhảy đã bỏ hẳn theo yêu cầu user: nhảy vừa không giải quyết được
	 * hố/khe (nhảy qua rồi vẫn rơi), vừa nhìn rõ là bot. Thay vào đó, khi kẹt thì
	 * nhìn xuống ô sàn ngay trước mặt: hụt sàn thì kê một viên rồi bước qua, đó
	 * là cách người chơi thật đi qua chỗ trống.
	 *
	 * @return true khi tick này đã bị việc kê block chiếm — người gọi không nên
	 *         bấm phím đi nữa.
	 */
	private boolean bridgeIfSnagged(ClientPlayerEntity player, Direction step) {
		double dx = player.getX() - lastWalkX;
		double dz = player.getZ() - lastWalkZ;
		boolean first = Double.isNaN(lastWalkX);
		lastWalkX = player.getX();
		lastWalkZ = player.getZ();

		if (first || dx * dx + dz * dz > SNAG_MOVE_EPS) {
			snagTicks = 0;
			return false;
		}
		if (++snagTicks < SNAG_BRIDGE_TICKS || !config.allowPlace) {
			return false;
		}

		World world = client.world;
		if (world == null || step == null) {
			return false;
		}

		// Ô SÀN ngay trước mặt: hụt (khí/nước/dung nham) thì kê một viên vào đó.
		BlockPos gap = player.getBlockPos().offset(step).down();
		if (!world.getBlockState(gap).isReplaceable()) {
			bridgeTicks = 0;
			return false; // không phải hụt sàn — để phần đào/đổi đường lo
		}
		// Kê mãi không xong (không có block, không có mặt tựa, server chặn) thì
		// buông ra cho phần đi/đào bên dưới chạy — ôm tick vô hạn ở đây là biến
		// thành đứng hình, thứ user vừa phàn nàn.
		if (++bridgeTicks > BRIDGE_PATIENCE) {
			return false;
		}
		breaker.cancel(); // kê block thì tay cầm block, không cầm cúp

		if (placer.fillCell(player, gap, config.reachDistance)) {
			return true;
		}
		bridgeTicks = 0;
		return false;
	}

	// ---- carving a way through ----

	private Result digToward(ClientPlayerEntity player, World world, BlockPos feet, BlockPos target) {
		boolean overColumn = feet.getX() == target.getX() && feet.getZ() == target.getZ();

		// BÊN DƯỚI ĐÍCH thì LEO TRƯỚC, tuyệt đối không đào ngang.
		//
		// Đây là luật user chốt: "phải cho nó 100% đặt block lên trên ngang với
		// đúng tâm thì mới dừng cầm cúp và đi đào tới chỗ đó". Trước đây, khi
		// đứng thấp hơn đích mà đường trước mặt bị chặn, mover đào ô ngang tầm
		// ngực (rút CÚP) rồi tick sau lại nhảy đặt block (rút BLOCK) — hai việc
		// giành nhau cái tay, hotbar đổi qua lại mỗi tick nên chẳng cái nào xong:
		// đúng cảnh "vừa đào vừa đặt" trong ảnh. Giờ chừng nào chân còn thấp hơn
		// đích thì chỉ có một việc duy nhất được phép chạy — xây trụ. Chỉ khi
		// trụ không lên nổi (hết block, hoặc bị trần chặn quá số lần cho phép)
		// mới trả tick lại cho phần đào/đi bên dưới.
		if (feet.getY() < target.getY() && tryPillarTick(player)) {
			return Result.MOVING;
		}

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
			// ĐÀO ĐƯỜNG = Y HỆT ĐÀO MẶT 9 Ô (yêu cầu của user: "cách đào nó sao
			// chép phải y hệt lúc đào 9 ô"). Luôn bổ vào ô ở HÀNG GIỮA TẦNG phía
			// trước — cùng cái hàng mà mặt 3x3 lấy tâm — nên một nhát ăn trọn cột
			// 3 ô của tầng và mắt luôn nhìn ngang.
			//
			// Bản trước bổ theo tầm ngực của THÂN rồi, nếu ô dưới chân còn kẹt,
			// bổ thêm phát nữa vào ô sát chân: chính hai nhát đó là cảnh "đào rồi
			// cúi, đào rồi cúi". Giờ bỏ hẳn nhát cúi — cột đã bị nhát giữa lấy
			// sạch, nếu vì lý do gì đó vẫn còn thì đồng hồ kẹt sẽ lo (đổi chỗ
			// đứng), chứ không cúi gằm xuống chân.
			int aimY = digAimY != Integer.MIN_VALUE ? digAimY : feet.getY() + 1;
			BlockPos boreCell = new BlockPos(ahead.getX(), aimY, ahead.getZ());
			BlockPos chestCell = new BlockPos(ahead.getX(), feet.getY() + 1, ahead.getZ());
			boolean boreBlocked = !BlockUtil.passable(world, boreCell);
			boolean chestBlocked = !BlockUtil.passable(world, chestCell);

			if (boreBlocked || chestBlocked) {
				// VỪA ĐI VỪA ĐÀO: ghì phím tiến vào bức tường đang đục thay vì
				// đứng khựng. Thân ép vào đá nên crosshair không rời block, vỡ
				// phát nào bước vào phát đó — đúng kiểu người chơi đục hầm.
				//
				// KHÔNG setYaw ở đây: ngay dưới, digStep sẽ ngắm vào chính ô đang
				// chắn — mà ô đó nằm đúng hướng đi. Ghi yaw trước rồi để breaker
				// ghi đè trong cùng một tick là hai lệnh quay chồng nhau, đầu giật
				// một nhịp thừa mỗi lần chạm tường.
				//
				// BÙ LẠI PHẢI CÓ CÁI NÓN NÀY. Bỏ setYaw mà vẫn ghì tiến vô điều
				// kiện thì phím tiến chạy theo yaw CŨ: lệch bao nhiêu độ là bấy
				// nhiêu vận tốc trượt ngang, thân lết dọc vách, tia ngắm quét khỏi
				// block và vanilla xoá sạch tiến độ đập. Chỉ ghì khi mũi đã gần
				// vuông góc với vách; còn lệch thì đứng yên một hai tick cho
				// breaker ngắm xong đã — đứng im lúc đó cũng là "đầu không xoay".
				boolean facingWall = Math.abs(MathHelper.wrapDegrees(
						Rotations.yawTo(step.getOffsetX(), step.getOffsetZ()) - player.getYaw()))
						<= WALK_DIG_CONE_DEGREES;

				// Cùng chuẩn tốc độ với đào mặt 9 ô (user: "lấy cái đào + di chuyển
				// của 9 ô lắp vào các cái đào khi di chuyển tới ô đỏ"): đích còn xa
				// thì SPRINT — tường vỡ nhát nào là lao vào nhát đó bằng đúng tốc độ
				// chạy, không lững thững đi bộ qua từng khúc hầm.
				boolean sprint = facingWall && config.allowSprint
						&& horizontalDistSq(player, target) > 2.5;

				input.set(facingWall ? 1.0F : 0.0F, 0.0F, false, sprint);

				// Ưu tiên hàng giữa tầng, nhưng CHỈ khi thật sự bổ tới được nó.
				// Nếu không (đứng thấp/cao hơn tầng, bị vật khác che), rơi về ô
				// ngay tầm ngực. Trước đây cứ nhắm mù vào hàng giữa: với không
				// tới thì breaker im, mà digStep vẫn báo MOVING — engine tưởng
				// đang làm việc nên ĐỨNG IM luôn, đúng cảnh user gặp.
				Result dug;

				if (boreBlocked && breaker.canReach(boreCell, reach())) {
					dug = digStep(boreCell);
				} else if (chestBlocked) {
					dug = digStep(chestCell);
				} else {
					dug = digStep(boreCell); // để digStep tự báo BLOCKED nếu chịu
				}
				// Không bổ được thì cũng đừng ôm lệnh tiến vừa bấm: người gọi sẽ
				// xoay cách khác, mà phím tiến còn kẹt là thân vẫn lầm lũi đi theo
				// hướng cũ suốt tick đó.
				if (dug == Result.BLOCKED) {
					input.stop();
				}
				return dug;
			}
			breaker.cancel();
			// Face straight down the tunnel we're cutting, not at the target off to one
			// side — heading diagonally is what scraped the player along the walls.
			// Hướng hầm là bốn hướng chính, nên qua bộ lọc khúc cua thì đầu chỉ động
			// đúng lúc ĐỔI TRỤC đào, còn chạy dọc hầm là bất động.
			if (faceWalkDirection(player, step.getOffsetX(), step.getOffsetZ()) > SHARP_TURN_DEGREES) {
				// Đổi trục hầm là cua vuông góc trở lên: quay xong hẵng bước, kẻo
				// bước ngang vào vách rồi lại tưởng kẹt mà đi kê block.
				input.stop();
				snagTicks = 0;
				return Result.MOVING;
			}
			// Kẹt vì hụt sàn trong hầm (đào trúng hang) thì kê viên rồi đi tiếp.
			if (bridgeIfSnagged(player, step)) {
				input.stop();
				return Result.MOVING;
			}
			// Open stretch: auto-run while the goal is still far, walk the last bit.
			boolean sprint = config.allowSprint && horizontalDistSq(player, target) > 2.5;
			input.set(1.0F, 0.0F, false, sprint);
			return Result.MOVING;
		}
		digAxis = null;

		// Right column but too low: tower up out of the hole. BLOCK ONLY — the
		// pickaxe never comes out for a climb ("vứt cái cúp đi, chỉ để bắc block
		// thôi"). A climb that can't proceed (no blocks, or hops keep failing
		// under a roof) reports BLOCKED, and the engine's reposition machinery
		// walks to a nicer column and pillars there instead ("bị kẹt thì tìm chỗ
		// khác đẹp để bắc lên").
		if (feet.getY() < target.getY()) {
			if (tryPillarTick(player)) {
				return Result.MOVING;
			}
			climbing = false;
			return Result.BLOCKED;
		}

		input.stop();
		return Result.MOVING;
	}

	/**
	 * The floor of the layer currently being mined. Not a wall for travel-digging
	 * (digStep ranges the whole box plus its margin) — it only tells the mover
	 * when it is beneath the layer and must climb rather than burrow. The old
	 * upper bound was a write-only field that read as load-bearing; gone.
	 */
	public void setLayerFloor(int minY) {
		this.digMinY = minY;
	}

	/** Hàng giữa của tầng — đường đào của mọi nhát bổ ngang. Xem {@link #digAimY}. */
	public void setDigAimY(int y) {
		this.digAimY = y;
	}

	/**
	 * Tầm với thật sự dùng khi đào đường — engine bơm vào mỗi tick.
	 *
	 * <p>Server mine cho tầm với dài hơn 4.5 của config (cúp riêng của server).
	 * Lõi đào mặt 9 ô vốn đã dùng {@code max(config, getBlockInteractionRange())},
	 * còn phần đào đường thì kẹt ở 4.5 — nên cùng một bức tường, mặt 9 ô bổ được
	 * từ xa mà đào đường phải lết sát mới bổ. Dùng chung một con số thì hai bên
	 * mới "y hệt" như user yêu cầu.
	 */
	public void setReach(double reach) {
		this.digReach = Math.max(config.reachDistance, reach);
	}

	private double reach() {
		return digReach > 0 ? digReach : config.reachDistance;
	}

	/**
	 * How far outside the box travel-digging may stray.
	 *
	 * <p>MỘT block, không phải hai. Lý do lề này tồn tại là ô đứng đầu dãy nằm
	 * đúng MỘT block ngoài rìa — lề 2 cho phép khoét thêm một lớp nữa quanh vùng,
	 * đúng cái user nhìn thấy ("nó cứ đào ra ngoài 1-2 block"). Kẹt thật sự thì
	 * đã có thang cứu ba-nhát-thẳng bổ từ mép vào trong, không cần khoét rộng.
	 */
	private static final int TRAVEL_DIG_MARGIN = 1;

	/**
	 * Mine a block that is in the way. Travel-digging is deliberately loose, per
	 * the user: "đào thừa ra ngoài cũng không sao, cứ đào xong rồi tiếp tục hướng
	 * đi của mình". The cells that wedge a row change live just OUTSIDE the box —
	 * a row-start stand cell sits one block past the rim — and refusing to break
	 * them is what froze the bot at "hàng N · 1/71" every time a new row began.
	 * The margin only stops it wandering off into the wider map.
	 */
	private Result digStep(BlockPos pos) {
		World world = client.world;
		if (!selection.contains(pos, TRAVEL_DIG_MARGIN)) {
			return Result.BLOCKED; // far outside the job — not ours to break
		}
		if (!BlockUtil.isBreakable(world, pos)) {
			return Result.BLOCKED;
		}
		// ĐÀO ĐƯỜNG DÙNG ĐÚNG LỐI ĐÀO CỦA MẶT 9 Ô: ngắm trước, chỉ rút cúp khi
		// crosshair THẬT SỰ đã nằm trên ô đó (yêu cầu user: "đào 100% y hệt lúc
		// đào 9 ô"). Bổ khi crosshair chưa tới nơi thì mixin cũng không cho vung,
		// chỉ tổ vung cúp trong không khí.
		//
		// Tầm với là reach() — TẦM SERVER, cùng con số mà chỗ CHỌN ô (canReach ở
		// digToward) đã dùng. Bản trước chọn bằng tầm dài rồi bổ bằng 4.5 cứng:
		// ô nằm trong khoảng 4.5..tầm-server được chọn xong lại bị chính hàm này
		// trả BLOCKED — đúng nghịch lý "mặt 9 ô bổ được từ xa mà đào đường phải
		// lết sát mới bổ" ghi ở setReach.
		if (crosshairOn(pos)) {
			if (!breaker.tickArmed(pos, reach())) {
				return Result.BLOCKED;
			}
			return Result.MOVING;
		}
		// Báo thật: ngắm không tới thì KHÔNG được nói là đang đi. Bản trước luôn
		// trả MOVING nên engine tưởng đang tiến triển và ĐỨNG IM chờ mãi.
		if (!breaker.aimOnly(pos, reach())) {
			return Result.BLOCKED;
		}
		return Result.MOVING;
	}

	/** Crosshair thật có đang nằm đúng trên {@code pos} không. */
	private boolean crosshairOn(BlockPos pos) {
		return client.crosshairTarget instanceof BlockHitResult hit
				&& hit.getType() == HitResult.Type.BLOCK
				&& hit.getBlockPos().equals(pos);
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
