package com.automine.mine;

import com.automine.config.AutoMineConfig;
import com.automine.util.AutoEat;
import com.automine.util.BlockBreaker;
import com.automine.util.BlockPlacer;
import com.automine.util.BlockUtil;
import com.automine.util.Rotations;
import com.automine.util.SimInput;
import net.minecraft.block.FallingBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.Input;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
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
	/**
	 * The face-escape watchdog: a face that has held the engine this long without
	 * the pickaxe actually chewing anything is degenerate — wedged inside it, no
	 * angle, whatever — and gets parked for the sweep so the route keeps moving.
	 * Ticks only accumulate while NOT actively breaking a block, so hard rock
	 * that simply takes long is never abandoned mid-bite.
	 */
	private static final int FACE_STUCK_TICKS = 160;
	/**
	 * The quick rescue: 1.5s of being stuck near a face means stop being clever
	 * and go STRAIGHT at the 3x3 in front — switch the goal to the face's own
	 * column, eat that face and the next two with the level rule waived
	 * (frontDigsLeft = 3), then rejoin the planned route. The user's final form
	 * of this ladder: "bị kẹt trong 1.5s thì lập tức chuyển sang 9 ô trước mặt,
	 * di chuyển đúng tâm ô đó, đào 3 lần để tới hướng tiếp tục vừa nãy" —
	 * replacing the older cell-by-cell red-cell chew.
	 */
	private static final int FACE_RESCUE_TICKS = 30;
	/**
	 * The rescue ladder only arms within this range of the face. Far away the one
	 * and only job is TRAVELLING there — a 1.5s timer firing mid-journey made the
	 * bot stop and chew whatever wall cell happened to be near, which read as
	 * "đứng im đào chỗ không liên quan". "Phải đi đúng tới cái chỗ 9 ô mới thực
	 * hiện cái chức năng 1.5s."
	 */
	private static final double RESCUE_NEAR_SQ = 6.0 * 6.0;
	/** How long a lock may go without progress before the HUD says so. Never skips. */
	private static final int SLOW_NOTE_TICKS = 200;
	/** Ticks a sweep leftover may resist before it's parked and the sweep moves on. */
	private static final int SWEEP_TARGET_LIMIT = 900;
	/**
	 * Nghỉ giữa hai ô vét. Hạ 10 → 4 tick (0.2 giây) theo yêu cầu "đào nhanh hơn
	 * chút như người bình thường": vẫn còn một nhịp thở giữa hai nhát nên không
	 * ra kiểu máy bắn liên thanh, nhưng không còn đứng đợi nửa giây mỗi block.
	 */
	private static final int SWEEP_REST_TICKS = 4;
	/** "Phạm vi 10 block": how far around the standing spot one sweep station covers. */
	private static final double SWEEP_STATION_RADIUS_SQ = 10.0 * 10.0;
	/**
	 * Độ dốc tối đa (so với phương ngang) được phép khi vét từ chỗ đang đứng.
	 * Rộng hơn luật 30° của mặt đào một chút vì ô sót nằm rải khắp ba tầng của
	 * layer, nhưng vẫn đủ chặt để không cúi gằm xuống chân.
	 */
	private static final double SWEEP_LEVEL_DEGREES = 40.0;
	/**
	 * Lệch ngang tối đa so với đường tâm của mặt đào mà vẫn được bổ thẳng vào
	 * tâm. Nửa block: quá mức này thì mặt phẳng 3x3 của cúp bắt đầu xẻ sang cột
	 * bên cạnh thay vì ăn đúng 9 ô của mặt.
	 */
	private static final double FACE_ALIGN_TOLERANCE = 0.22;
	/**
	 * Lệch trong khoảng này thì TRƯỢT VỀ TÂM chứ không đi lại từ đầu.
	 *
	 * <p>Siết {@link #FACE_ALIGN_TOLERANCE} xuống 0.22 (user: "xác định 100% đúng
	 * tâm giữa 9 ô mới đào") mà không có bước này thì mỗi lần lệch nhẹ là cả cỗ
	 * máy quay về APPROACH, tính lại lộ trình, đi vòng — đứng hình chứ không phải
	 * chính xác hơn. Lệch dưới một block thì chỉ cần bấm phím ngang vài tick.
	 */
	private static final double CENTRE_NUDGE_RANGE = 1.4;
	/** Vùng chết khi trượt canh tâm — nhỏ hơn lúc đi đường để nhích được từng chút. */
	private static final double CENTRE_NUDGE_DEADZONE = 0.04;
	/**
	 * Khoảng lùi tối thiểu khỏi mặt phẳng của khối 9 ô trước khi được bổ.
	 *
	 * <p>0.7 block: đủ để thân đứng hẳn ngoài mặt (ô đứng chuẩn cách tâm đúng 1
	 * block), nhưng không đòi hỏi tới mức đứng xa quá tầm với.
	 */
	private static final double FACE_STANDOFF = 0.7;
	/** Bổ thẳng đủ chừng này mặt ở đầu mỗi dãy rồi mới cho bước đi. */
	private static final int STRAIGHT_DIGS_PER_ROW = 3;
	/**
	 * Nạp sẵn bấy nhiêu ô ứng viên cho ba nhát đầu dãy.
	 *
	 * <p>Rộng hơn ba: ô sát bên thường đã bị mặt 3x3 cuối dãy CŨ ăn mất, nạp đúng
	 * ba là hụt ngay một nhát ("3 nhát 27 ô" thành "2 nhát 18 ô").
	 */
	private static final int STRAIGHT_SCAN_DEPTH = 6;
	/** Trần thời gian cho MỘT nhát trong ba nhát đầu dãy (30 giây). */
	private static final int STRAIGHT_DIG_LIMIT = 600;
	/** Chướng ngại nằm ngoài vùng chừng này block thì không phá (giống travel-dig:
	 *  1 block — đủ cho ô đứng đầu dãy sát rìa, không khoét thêm lớp ngoài vùng). */
	private static final int OBSTACLE_DIG_MARGIN = 1;
	/**
	 * Bám một block tối đa 60 giây rồi mới coi là "lì".
	 *
	 * <p>Rộng tay có lý do: nhả khoá là mất sạch tiến độ đập của vanilla (bỏ
	 * {@code aiming} thì mixin thôi ghì chuột trái), nên ngưỡng phải cao hơn mọi
	 * block đào chậm nhưng LƯƠNG THIỆN — obsidian với cúp sắt đã ~625 tick.
	 */
	private static final int LOCK_DIG_LIMIT = 1200;
	/** Ô "lì" bị né bấy nhiêu tick (30 giây) trước khi được chọn lại. */
	private static final int STUBBORN_COOLDOWN = 600;
	/** Viên tự kê nằm trong bán kính này (bình phương) thì coi như còn đang đỡ chân. */
	private static final double SCAFFOLD_KEEP_RADIUS_SQ = 4.0;
	/** Gần hơn ngần này thì thôi ghì tiến — đứng sát quá dễ rơi vào hố vừa mở. */
	private static final double WALK_DIG_MIN_DIST = 1.1;
	/** Chênh cao tối đa (mắt ↔ tâm block) còn coi là "ngang tầm" để vừa đi vừa đào. */
	private static final double WALK_DIG_MAX_RISE = 1.6;
	/** Nón phía trước còn được ghì tiến; ngoài nón thì đứng ngắm cho crosshair về đúng chỗ. */
	private static final double WALK_DIG_CONE_DEGREES = 40.0;
	/** Lệch dưới ngần này thì thôi bấm phím trục đó — vùng chết chống rung quanh tâm. */
	private static final double WALK_DIG_DEADZONE = 0.18;
	/** Xa hơn ngần này thì cho chạy nhanh; sát mặt đá thì thôi, chạy vào tường chỉ nảy ra. */
	private static final double WALK_DIG_SPRINT_DIST = 2.2;
	/**
	 * Số nhát bổ THẲNG XUỐNG khi rơi tầng.
	 *
	 * <p>BA nhát, không phải hai (user đổi 2026-08-19): trên server mine block
	 * hay bị GHOST — client thấy vỡ, server trả lại — nên "3 nhát tính ra ăn
	 * chắc 2 mức". Không ghost thì nhát ba cũng không thừa: ăn nốt hàng sàn của
	 * tầng, chân đáp đúng tầm ô đứng chuẩn (layerBottom) của mặt đào. Chạm đáy
	 * vùng thì vòng kiểm {@code selection.contains} tự dừng sớm, không lố.
	 */
	private static final int LAYER_DESCEND_DIGS = 3;
	/**
	 * Chờ tối đa bấy nhiêu tick cho thân tụt xuống sau mỗi nhát bổ chân.
	 *
	 * <p>Rơi một mức mất chừng 5-6 tick, nên 1.5 giây là rộng rãi. Có trần vì
	 * đứng chàng hảng giữa hai cột thì thân KHÔNG rơi bao giờ — lúc đó thà trả
	 * tick cho máy chính (nó biết đi lại) còn hơn đứng như trời trồng.
	 */
	/**
	 * Lệch tối đa (mỗi trục, block) so với tâm Ô ĐỨNG mà vẫn được bổ mở giếng.
	 *
	 * <p>Luật user: "phải ĐI RA GIỮA, đi ngang đúng chỗ đào mới bổ xuống 2 nhát".
	 * 0.15 — cùng con số căn-cột của trụ leo: thân 0.6 đứng lệch hơn thế là đã
	 * lấn sang cột bên, mất thế thẳng hàng với cột giếng phía trước.
	 */
	private static final double DESCEND_CENTER_MARGIN = 0.15;
	/** Căn giữa tối đa bấy nhiêu tick (3s) rồi bổ luôn — đằng nào cũng đúng cột. */
	private static final int DESCEND_CENTER_LIMIT = 60;
	/**
	 * Chờ thân tụt xuống sau mỗi nhát bổ chân tối đa bấy nhiêu tick (1.5s).
	 *
	 * <p>Rơi một mức mất ~5-6 tick; có trần vì đứng chàng hảng hai cột thì không
	 * bao giờ rơi — lúc đó trả máy chính còn hơn đứng như trời trồng.
	 */
	private static final int DESCEND_WAIT_LIMIT = 30;
	/** Nghỉ bấy nhiêu tick sau khi đáp đáy giếng rồi mới ngẩng đầu vào việc. */
	private static final int DESCEND_SETTLE_TICKS = 5;
	/**
	 * A layer entering the sweep with at least this many blocks still standing is
	 * not "leftovers" — it is a layer with real faces (the server mine REGENERATES,
	 * so a resumed plan's cursor can claim "faces done" over freshly refilled
	 * rock). Reopen the face pass instead of pecking it block by block — the
	 * user's "có tâm đào đàng hoàng sao lại đi đào kiểu vét".
	 */
	private static final int SWEEP_REOPEN_THRESHOLD = 20;
	/** Số tick được phép mất tầm ô sót trước khi bỏ mục tiêu (chống quay đầu 2 lần). */
	private static final int SWEEP_BLIND_GRACE = 10;
	/**
	 * Ô sót có từ bấy nhiêu HÀNG XÓM trở lên (trong khối 3x3x3 quanh nó) thì cả
	 * cụm được đối xử như MỘT MẶT 9 Ô: đào liền mạch, vừa đi vừa đào, không nghỉ
	 * nhịp giữa hai nhát.
	 *
	 * <p>Luật user (kèm ảnh mấy vách 6-9 block còn trơ giữa tầng): "mấy chỗ như
	 * này tự động đào như kiểu đang đào 9 ô, vì nó có 6 ô 9 ô chứ không phải vét
	 * sót 1-2-3 block — đào thực hiện như bình thường, vừa đi vừa đào". Bốn hàng
	 * xóm nghĩa là cụm từ ~5 block trở lên; cụm 3 block thẳng hàng thì ô giữa chỉ
	 * thấy 2 — vẫn là vét sót đúng nghĩa, giữ nhịp thong thả cũ.
	 */
	private static final int SWEEP_FACE_CLUMP_NEIGHBORS = 4;
	/**
	 * Bổ xong một nhát vét thì chừa cả khối quanh đó bấy nhiêu tick.
	 *
	 * <p>8 tick (0.4 giây), không phải 20. Sổ này chỉ cần sống lâu bằng độ trễ
	 * server xác nhận phần AoE của nhát vừa bổ — vài tick là đủ. Để 20 thì bổ
	 * xong một nhát là phần còn lại của CHÍNH CỤM ĐÓ bị giấu cả giây: máy chọn
	 * cụm khác xa hơn, đi sang, sổ hết hạn, cụm cũ hiện lại, đi ngược về — đúng
	 * cảnh "đào xong lại di chuyển ra sau rồi lại lên đào" user quay được. Đi kèm
	 * là luật ĐỨNG CHỜ ở {@code tickLayerSweep}: ô trong trạm chỉ bị sổ này giấu
	 * thì đứng yên đợi hết hạn chứ không được bỏ đi.
	 */
	private static final int SWEPT_ZONE_TICKS = 8;

	/**
	 * One quiet second after /start: take the whole box in (điểm 1 → điểm 2),
	 * pick the right face, and only THEN move out — no hasty towers, no swings
	 * ("load toàn bộ điểm 1-điểm 2 đừng làm gì vội, sau đó vọt tới đúng điểm đào").
	 */
	private static final int STARTUP_GRACE_TICKS = 20;

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
	/**
	 * Mọi ô thuộc các mặt 3x3 đã đào xong trong tầng này.
	 *
	 * <p>Đào xong một mặt là xong hẳn: lượt vét cuối tầng sẽ không mò lại chỗ đó
	 * nữa ("đã xác định đào chỗ đó 9 block xong thì không lặp lại"). Ngoại lệ duy
	 * nhất là sỏi/cát rơi xuống lấp vào — {@link #needsDigging} vẫn nhận những
	 * block rơi được ({@code FallingBlock}) trong vùng này. Xoá sạch mỗi khi
	 * xuống tầng mới.
	 */
	private final Set<BlockPos> doneCells = new HashSet<>();

	private int mined;
	private int skipped;
	private boolean wasClimbing;
	/** Which step of the current face we're on. */
	private Phase phase = Phase.APPROACH;
	/** The face centre {@link #phase} belongs to; when this changes the phase restarts. */
	private BlockPos phaseFace;
	/** Ticks spent in the current phase, for the HUD and the slow-work note. */
	private int phaseTicks;
	/** Idle ticks on the current face (not counting active breaking); see FACE_STUCK_TICKS. */
	private int faceTicks;
	/** Remaining ticks of the quiet look-around right after /start. */
	private int startupTicks;
	/** Whether the body genuinely moved since last tick — travel counts as work. */
	private boolean movedThisTick;
	private double lastTickX, lastTickY, lastTickZ;
	/** Where {@link Phase#REPOSITION} is currently walking to, if anywhere. */
	private BlockPos repositionTarget;
	/**
	 * Approach the current face via its own column ({@link QuarryPlan#entryPos})
	 * instead of the normal stand cell. Switched on when the stand cell can't be
	 * reached — at a row start it lies outside the box, buried under rock the
	 * mover must not break; the face column is inside and always diggable.
	 */
	private boolean useEntryStance;
	/** Dãy của lần tick trước, để biết lúc plan vừa quay sang dãy mới. -1 = chưa có. */
	private int lastRow = -1;
	/** Số lần mặt hiện tại bị kẹt — xoay vòng cách tiếp cận, KHÔNG bao giờ bỏ mặt. */
	private int stuckRounds;
	/**
	 * Ba tâm mặt đầu dãy, xếp theo đúng hướng chạy — phải bổ HẾT rồi mới cho máy
	 * chính chạy tiếp.
	 *
	 * <p>Hàng đợi chứ không phải bộ đếm, vì bộ đếm trừ theo "con trỏ đổi mặt" —
	 * mà con trỏ nhảy qua cả những mặt vốn đã trống, nên đếm 3 mà thực tế chỉ bổ
	 * 2 nhát rồi bỏ đi ("đào có 2 lần rồi 1 lần nó bỏ đi"). Ô nào còn nằm trong
	 * hàng đợi là chưa vỡ: chỉ khi {@code needsDigging} bảo nó đã trống thì mới
	 * được lấy ra. Ba nhát, đúng ba block của ba đường thẳng, không thiếu cái nào.
	 */
	private final java.util.ArrayDeque<BlockPos> straightQueue = new java.util.ArrayDeque<>();
	/** Chỗ đứng đang nhắm tới cho nhát bổ-thẳng hiện tại (khỏi reset mover mỗi tick). */
	private BlockPos straightStance;
	/** Ô đang bổ trong hàng đợi bổ-thẳng — vỡ nó mới được tính là MỘT NHÁT thật. */
	private BlockPos straightWorking;
	/** Số nhát THẬT đã bổ ở đầu dãy (ô vốn trống sẵn không tính). */
	private int straightSwings;
	/** Mặt hiện tại đã dùng cú cứu "ba nhát thẳng" chưa — mỗi mặt đúng một lần. */
	private boolean straightRescueTried;
	/**
	 * Đang ĐỨNG DƯỚI tầng nhưng vẫn với tới mặt đào — đứng im ngay mép mà bổ.
	 *
	 * <p>Luật user 2026-08-19: "bắc lên mà phía trước bị lọt xuống thì đứng im,
	 * với tới trước mặt đào hết cỡ rồi mới di chuyển lúc bắc block lên; thấy
	 * không cần bắc thì đào như bình thường". Nghĩa là: lọt xuống thấp hơn tầng
	 * mà tâm mặt vẫn trong tầm với thì KHÔNG leo vội — đứng nguyên chỗ, bổ hết
	 * mặt này tới mặt khác chừng nào còn với tới; chỉ khi hết tầm mới nhường
	 * tick cho cú leo, và cú leo lúc đó độc quyền cái tay.
	 *
	 * <p>Cờ này tồn tại để {@link #recoverFromBelowLayer} biết đường NHƯỜNG:
	 * không có nó thì hễ phase rời APPROACH là recovery thấy "đang ở dưới tầng"
	 * liền giật tick đi kê block — cầm BLOCK trong khi máy mặt đang cầm CÚP, đúng
	 * vòng "block-cúp" cũ.
	 */
	private boolean edgeDigging;
	/** Đồng hồ an toàn cho hàng đợi trên: quá lâu thì nhả về máy chính. */
	private int straightTicks;
	/** Ô phải đứng lên (trên đầu cột mặt 9 ô tầng mới) trước khi bổ xuống. */
	private BlockPos descendStand;
	/** Đang trong lượt bổ xuống chân để tụt tầng (đã đứng đúng cột). */
	private boolean descendDigging;
	/** Số nhát THẬT đã bổ xuống — ô vốn đã trống sẵn không tính. */
	private int descendSwings;
	/** Ô đang bổ trong lượt tụt tầng — vỡ nó mới được tính một nhát. */
	private BlockPos descendWorking;
	private int descendTicks;
	/** Cột (X,Z) của Ô ĐỨNG (= cột giếng) — thân căn gọn giữa cột này trước khi bổ. */
	private BlockPos descendColumn;
	/** Số tick đứng chờ tụt xuống sau một nhát; trần {@link #DESCEND_WAIT_LIMIT}. */
	private int descendWaitTicks;
	/** Số tick đã nghỉ ở đáy giếng trước khi bàn giao — xem {@link #DESCEND_SETTLE_TICKS}. */
	private int descendSettleTicks;
	/** Số tick đã dùng để căn giữa; quá {@link #DESCEND_CENTER_LIMIT} thì bổ luôn. */
	private int descendCentreTicks;
	/** Chốt "đã căn xong" — đứng chết tại chỗ, chỉ nhả khi bị đẩy văng (>0.45). */
	private boolean descendCentred;
	/** Số tick đã bám vào block đang bổ dở — trần {@link #LOCK_DIG_LIMIT}. */
	private int lockedTicks;
	/**
	 * Ô bổ mãi không vỡ → số tick còn phải né trước khi thử lại.
	 *
	 * <p>CHỈ mấy hàm CHỌN mục tiêu tra sổ này ({@link #faceWorkCell},
	 * {@link #stationLeftover}, {@link #nearestLeftover}). Tuyệt đối không đưa
	 * vào {@code needsDigging}: hàm đó còn dùng để đếm block còn lại và kết luận
	 * "tầng đã sạch", giấu ô ở đó là âm thầm bỏ mặt đào — thứ user cấm.
	 */
	private final java.util.Map<BlockPos, Integer> stubborn = new java.util.HashMap<>();
	/**
	 * Ô nằm quanh chỗ VỪA BỔ trong lượt vét → còn phải chừa bấy nhiêu tick.
	 *
	 * <p>Lý do tồn tại nằm nguyên trong lời user: "đào xong tâm giữa nó lại cúi
	 * đầu xuống dưới 1 ô tâm giữa, kiểu block kia chưa hết vẫn đang trong trạng
	 * thái biến mất, nên một chỗ vét bị lặp lại 2 lần". Client thấy mấy ô mà nhát
	 * 3x3 vừa ăn biến mất TRỄ, nên trong vài tick đó chúng vẫn được tính là "block
	 * còn sót" và bị chọn làm mục tiêu kế tiếp — cùng một chỗ, thêm một cú cúi
	 * đầu. Chừa cả khối 3x3x3 quanh nhát vừa bổ đúng một giây là hết cảnh đó.
	 *
	 * <p>Giống {@link #stubborn}: CHỈ mấy hàm CHỌN mục tiêu tra sổ này, tuyệt đối
	 * không đưa vào {@code needsDigging} — giấu ở đó là âm thầm bỏ sót block.
	 */
	private final java.util.Map<BlockPos, Integer> justSwept = new java.util.HashMap<>();
	/**
	 * Faces left to dig STRAIGHT FROM WHERE WE STAND before returning to the
	 * planned route. Armed when an approach gets wedged (typically the start of a
	 * new row, standing at the old row's end): rather than well-digging, just eat
	 * the 3x3 in front, three faces deep, then pick the normal path back up —
	 * the user's "đổi sang 9 ô trước mặt và đào 3 lần rồi quay lại đúng đường".
	 */
	private int frontDigsLeft;
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
	private String note = "";

	// ---- layer sweep (vét tầng): dig every leftover before descending ----
	/** The leftover being swept; held until it's air or given up (re-picking every
	 *  tick made the sweep ping-pong between targets — an old, fixed bug). */
	private BlockPos sweepTarget;
	private BlockPos sweepStance;
	private int sweepTicks;
	/** Breather ticks between sweep leftovers — "đào từ từ thôi, không quá nhanh". */
	private int sweepRest;
	/** Số tick liên tiếp không với tới ô sót đang nhắm — xem SWEEP_BLIND_GRACE. */
	private int sweepBlindTicks;
	/** Mục tiêu vét hiện tại là CỤM TO (≥ {@link #SWEEP_FACE_CLUMP_NEIGHBORS} hàng
	 *  xóm) — đào kiểu mặt 9 ô: không nghỉ nhịp, được ghì tiến vào cụm. */
	private boolean sweepFaceMode;

	// ---- exp repair (quăng bình exp cho Mending hồi cúp) ----
	/** Ticks between bottle throws. 2 (user: "ném exp nhanh hơn gấp đôi") — orb
	 *  vẫn kịp bay vào vì Mending hút liên tục, chỉ là ném dày nhịp hơn. */
	private static final int EXP_THROW_INTERVAL = 2;
	/** Quét không thấy bình thì chờ túi đồ đồng bộ bấy nhiêu tick rồi quét lại
	 *  — một lần trượt sau chuỗi swap+ném không phải là "hết exp". */
	private static final int EXP_REFILL_GRACE = 20;
	/** Trần chờ hút orb exp quanh người sau khi sửa xong (10 giây). */
	private static final int ORB_DRAIN_LIMIT = 200;
	/** Hotbar slot of the tool being mended; only meaningful while repairing. */
	private int repairSlot = -1;
	private boolean repairing;
	private int throwTimer;
	/** Latched when the bottles ran dry, so the trigger doesn't spam retries. */
	private boolean expExhausted;
	/** Số tick đã chờ túi đồ đồng bộ trước khi dám tuyên "hết exp". */
	private int refillGraceTicks;
	/** Đang đứng hút nốt orb exp quanh người trước khi đào tiếp. */
	private boolean drainingOrbs;
	private int orbDrainTicks;
	/** Manual test countdown ("nút test ném"): throws left, 0 = not testing. */
	private int testThrowsLeft;
	/** Where the off hand's original item (usually the totem) was parked; -1 = none. */
	private int offhandReturnSlot = -1;
	/** The cluster the current relocation is aiming for; scopes the stance blacklist. */
	private BlockPos sweepRelocateTarget;
	/** Whether this layer's sweep already counted what's left (done once per entry). */
	private boolean sweepAssessed;
	/** Whether this layer's faces were already reopened once — prevents a reopen loop. */
	private boolean sweepReopenedLayer;


	public QuarryEngine(MinecraftClient client, AutoMineConfig config, Selection selection) {
		this.client = client;
		this.config = config;
		this.selection = selection;
		this.breaker = new BlockBreaker(client);
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
			// but leave the plan cursor alone. frontDigsLeft explicitly: a stale
			// armed counter waives the level rule for faces that have nothing to
			// do with the original wedge.
			phaseFace = null;
			frontDigsLeft = 0;
			// Hai máy con này PHẢI dọn theo, không thì trạng thái của lần chạy
			// trước sống sót qua /stop: cờ tụt tầng còn bật là tick đầu tiên sau
			// /start bổ thủng ngay sàn dưới chân — ở chỗ bất kỳ mình đang đứng —
			// còn hàng đợi bổ-thẳng thì bổ vào mấy ô của dãy cũ.
			endDescendDigs();
			straightQueue.clear();
			straightStance = null;
			straightWorking = null;
			straightSwings = 0;
			restartFace();
			clearTarget();
		} else {
			// Tạo plan mới
			stop();
			// KHỞI HÀNH TỪ GÓC GẦN NGƯỜI CHƠI. Bản cũ luôn xuất phát từ góc min:
			// ai đứng góc đối diện là thấy bot bỏ mép chỗ mình, lết cả hai chục
			// block sang đầu kia rồi mới đào — vừa chậm vừa ra cảnh "đào dịch vào
			// trong". Lật từng trục theo nửa gần hơn thì mặt 9 ô đầu tiên nằm
			// ngay mép cạnh chân mình, đào từ ngoài vào trong đúng nghĩa.
			boolean axisX = selection.sizeX() >= selection.sizeZ(); // cùng luật chọn trục với plan
			BlockPos feet = player.getBlockPos();
			int pt = axisX ? feet.getX() : feet.getZ();
			int pc = axisX ? feet.getZ() : feet.getX();
			int loT = axisX ? selection.minX() : selection.minZ();
			int hiT = axisX ? selection.maxX() : selection.maxZ();
			int loC = axisX ? selection.minZ() : selection.minX();
			int hiC = axisX ? selection.maxZ() : selection.maxX();
			plan = new QuarryPlan(selection, config.layerHeight, config.passWidth,
					Math.abs(pc - hiC) < Math.abs(pc - loC),
					Math.abs(pt - hiT) < Math.abs(pt - loT));
			mover = new MoveController(client, config, input, breaker, selection);
			mined = 0;
			skipped = 0;
			// -1 để dãy ĐẦU TIÊN cũng được tính là "vừa sang dãy mới" → cũng đào
			// giếng vào tâm ba nhát rồi mới chạy dãy, y như các dãy sau.
			lastRow = -1;
			wasClimbing = false;
			phase = Phase.APPROACH;
			phaseFace = null;
			phaseTicks = 0;
			repositionTarget = null;
			frontDigsLeft = 0;
			edgeDigging = false;
			triedStances.clear();
			recoverTicks = 0;
			recovering = false;
			warnedNoBlocks = false;
			note = "";
			givenUp.clear();
			doneCells.clear();
			straightQueue.clear();
			straightStance = null;
			endDescendDigs();
			clearTarget();
		}

		// Sweep work is all re-derived from the world, so starting (fresh
		// or resumed) always begins it from scratch — stale targets from a previous
		// run would point at blocks that may long since be gone.
		resetSweepState();
		startupTicks = STARTUP_GRACE_TICKS;

		savedInput = player.input;
		player.input = input;
		state = State.RUNNING;
		return null;
	}

	private void resetSweepState() {
		sweepTarget = null;
		sweepStance = null;
		sweepRelocateTarget = null;
		sweepTicks = 0;
		sweepRest = 0;
		sweepFaceMode = false;
		sweepAssessed = false;
		sweepReopenedLayer = false;
		repairing = false;
		expExhausted = false;
		refillGraceTicks = 0;
		drainingOrbs = false;
		orbDrainTicks = 0;
		throwTimer = 0;
		testThrowsLeft = 0;
		offhandReturnSlot = -1;
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

	/**
	 * Nhai xong táo thì chạy tiếp — KHÔNG được vứt việc đang làm dở.
	 *
	 * <p>Tách khỏi {@link #resume()} vì hàm kia dọn sạch mọi thứ (sổ ô đã đào, ba
	 * nhát đầu dãy, hai nhát tụt tầng, con trỏ mặt). Đúng cho /resume của người
	 * dùng sau khi tạm dừng lâu, nhưng auto-eat chỉ ngắt hai giây giữa chừng: dọn
	 * sạch ở đó là bắt bot làm lại từ đầu mỗi lần ăn táo.
	 */
	public void resumeFromEating() {
		if (state != State.PAUSED || !autoPaused) {
			return;
		}
		state = State.RUNNING;
		autoPaused = false;
		// pause() đã cancel breaker rồi, nên chỉ cần ngắm lại từ chỗ cũ.
		enterPhase(Phase.AIM_LOCK);
	}

	public void resume() {
		if (state == State.PAUSED) {
			state = State.RUNNING;
			autoPaused = false;
			givenUp.clear(); // give everything another go
			doneCells.clear();
			straightQueue.clear();
			straightStance = null;
			endDescendDigs();
			phaseFace = null; // re-derive the face from scratch after a pause
			frontDigsLeft = 0; // stale wedge state must not waive the level rule
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
		if (client.player != null) {
			restoreOffhand(client.player); // a /stop mid-repair must give the totem back
		}
		repairing = false;
		testThrowsLeft = 0;
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

	/** Manual test ("nút test ném"): throw ten exp bottles right now, running or not. */
	public void startExpTest() {
		ClientPlayerEntity player = client.player;
		if (player == null) {
			return;
		}
		repairing = true;
		repairSlot = player.getInventory().getSelectedSlot();
		testThrowsLeft = 10;
		throwTimer = 0;
		expExhausted = false;
		message("test ném exp: 10 bình…");
	}

	public void tick() {
		if (state != State.RUNNING) {
			// The throw test is allowed to run without a dig in progress.
			if (testThrowsLeft > 0 && client.player != null) {
				tickExpRepair(client.player);
			}
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

		// MẶC ĐỊNH MỖI TICK LÀ ĐỨNG YÊN. Phần nào muốn đi thì tự bấm lại phím
		// trong chính tick của nó (pressIntoTarget, pillarUp, moveTo… đều làm
		// vậy). Không có dòng này thì mọi nhánh `return` sớm — khoá đầu, tụt
		// tầng, đợi nước, watchdog — để nguyên lệnh tiến của tick trước, và bot
		// tiếp tục lao đi trong lúc đáng lẽ phải đứng.
		input.stop();

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
			//
			// disarm chứ KHÔNG cancel: cancel vứt luôn điểm ngắm đã chốt, nên ô
			// kế tiếp phải dò lại mặt để ngắm từ đầu và hay ra một điểm khác —
			// thành cú quay đầu thừa ngay sau mỗi nhát ("đào xong quay đầu 2
			// lần"). disarm chỉ thôi ghì chuột, giữ nguyên bộ nhớ ngắm.
			//
			// Sổ chừa ô-ma ghi cho MỌI NHÁT VỠ, không riêng nhát vét. Mấy ô mà lát
			// 3x3 vừa ăn còn hiện đặc trên client vài tick (server xác nhận trễ);
			// trước đây chỉ nhát vét mới ghi sổ, nên vừa đào mặt xong bước vào
			// lượt vét là nó chọn ngay một ô-ma làm mục tiêu — đầu quay tới nơi
			// thì ô biến mất, lại quay chỗ khác: đúng "xoay mấy chỗ ko có" user
			// bắt được. Ghi ở đây thì mọi kiểu đào đều được chừa như nhau.
			noteJustSwept(activeTarget);
			activeTarget = null;
			breaker.disarm();
		}


		// Sổ "lì" đếm lùi: hết hạn là ô đó được chọn lại như thường (không phải
		// bỏ qua vĩnh viễn — luật là KHÔNG BAO GIỜ bỏ mặt đào).
		if (!stubborn.isEmpty()) {
			stubborn.replaceAll((cell, left) -> left - 1);
			stubborn.values().removeIf(left -> left <= 0);
		}
		// Sổ "vừa bổ ở đây" cũng đếm lùi — xem {@link #justSwept}.
		if (!justSwept.isEmpty()) {
			justSwept.replaceAll((cell, left) -> left - 1);
			justSwept.values().removeIf(left -> left <= 0);
		}

		// ĐỒNG HỒ CỦA HAI MÁY CON ĐẾM Ở ĐÂY, KHÔNG ĐẾM TRONG HÀM CỦA CHÚNG.
		//
		// Lý do: một khi workTarget đã arm được block, {@code tickLockedDig} (chạy
		// TRƯỚC trong tick) chiếm trọn mọi tick sau đó — hàm của máy con chỉ được
		// gọi lại mỗi lần khoá 60 giây nhả ra. Đếm bên trong hàm là bị bỏ đói:
		// trần "30 giây" của lượt tụt tầng thực tế cần ~10 tiếng mới chạm. Đếm ở
		// đây thì thời gian là thời gian thật, ai chiếm tick cũng không lệch.
		if (descendStand != null || descendDigging) {
			descendTicks++;
		}
		if (!straightQueue.isEmpty()) {
			straightTicks++;
		}

		// A healthy walk is work: without this, an approach longer than the face
		// watchdog parked perfectly good faces mid-route ("kẹt 8s" while simply
		// travelling), and givenUp then hid them from the sweep as well.
		double movedSq = (player.getX() - lastTickX) * (player.getX() - lastTickX)
				+ (player.getY() - lastTickY) * (player.getY() - lastTickY)
				+ (player.getZ() - lastTickZ) * (player.getZ() - lastTickZ);
		movedThisTick = movedSq > 0.0025; // more than 0.05 blocks in one tick
		lastTickX = player.getX();
		lastTickY = player.getY();
		lastTickZ = player.getZ();

		// The quiet second after /start: scan the WHOLE box up front — the face
		// cursor runs past everything already dug and locks onto the first real
		// work — and only then move out. No hasty towers, no swings ("bấm /start
		// là lập tức quét full điểm, sau đó đã quét được thì đi tới đó và đào").
		if (startupTicks > 0) {
			startupTicks--;
			input.stop();
			breaker.cancel();

			// BỎ QUA MỌI TẦNG ĐÃ SẠCH trước đã.
			//
			// Không có bước này thì /start luôn bắt đầu ở tầng trên cùng của kế
			// hoạch — dù tầng đó đào xong từ đời nào. Thân đang đứng dưới hố sâu
			// nên bị coi là "lọt xuống dưới tầng", và việc đầu tiên bot làm là
			// BẮC TRỤ LEO NGƯỢC LÊN cái tầng trống trơn đó rồi mới lóp ngóp tìm
			// việc: đúng thứ user hỏi "tầng trên đào rồi lại bắc lên làm gì".
			// Quét từ trên xuống, tầng nào còn block mới dừng.
			int skippedLayers = 0;
			while (countLayerLeftovers(world, 1) == 0 && skippedLayers++ < plan.layerCount()) {
				int emptyLayer = plan.layerIndex() + 1;

				if (!plan.nextLayer()) {
					message("cả vùng đã sạch — không còn gì để đào");
					finish();
					return;
				}
				message("tầng " + emptyLayer + " đã sạch sẵn — bỏ qua, xuống tầng "
						+ (plan.layerIndex() + 1));
				clearTarget();
				straightQueue.clear();
				endDescendDigs();
			}

			// Mine hồi sinh / /stop giữa lượt vét: con trỏ nói "mặt xong" nhưng
			// tầng đầy block nguyên. Mở lại lượt mặt NGAY TRONG giây quét — để
			// chậm một tick là lượt vét mở hộ, nhưng lúc đó cú arm xuống-tầng
			// bên dưới đã lỡ, và máy mặt bò vào tầng từ ngoài đúng kiểu cũ.
			if (startupTicks == 0 && plan.areLayerFacesDone() && !sweepReopenedLayer
					&& countLayerLeftovers(world, SWEEP_REOPEN_THRESHOLD) >= SWEEP_REOPEN_THRESHOLD) {
				sweepReopenedLayer = true;
				plan.restartLayer();
				phaseFace = null;
				frontDigsLeft = 0;
				restartFace();
				clearTarget();
				message("tầng " + (plan.layerIndex() + 1)
						+ " còn nhiều block nguyên — đào lại theo tâm 9 ô");
			}

			if (!plan.areLayerFacesDone()) {
				int examined = 0;
				while (!faceHasWork(world) && !plan.areLayerFacesDone() && examined++ < SKIP_BUDGET) {
					plan.advance();
				}
			}

			// /START LÚC ĐANG ĐỨNG TRÊN NÓC TẦNG DỞ → vào tầng bằng ĐÚNG BÀI
			// XUỐNG TẦNG (user: "đang ở tầng 2, bật lại thì phải đi tới chỗ 9 ô
			// đỏ đào 2 nhát để xuống xong đào tiếp tục"): đi trên mặt tới cột ô 9
			// đỏ của mặt đầu tiên, cúi bổ hai nhát, rơi vào tầng, máy mặt chạy
			// tiếp. Không có bước này thì máy mặt tự tìm đường khoan xiên từ
			// ngoài vào — vừa xấu vừa hay khoét ra ngoài mép vùng. Chỉ chạy ở
			// tick CUỐI của giây quét, khi con trỏ đã trỏ đúng mặt còn việc.
			// Mốc so là HÀNG GIỮA của mặt, không phải nóc tầng: hộp đánh dấu từ
			// mặt sàn mine thì hàng trên cùng của tầng chính là khoảng khí đang
			// đi lại — chân bằng nóc tầng nhưng tâm mặt vẫn thấp hơn chân 1 block.
			// So với nóc là trượt cả trường hợp đó, và máy mặt bò + mổ sàn từng ô
			// suốt quãng đường tới mặt đầu tiên ("đào tới 9 ô đỏ rất chậm").
			if (startupTicks == 0 && !plan.areLayerFacesDone()
					&& player.getBlockY() > plan.faceCenter().getY()) {
				armDescendDigs(player);
				message("đứng trên tầng " + (plan.layerIndex() + 1)
						+ " — tới ô 9 đỏ, mở giếng rồi đào tiếp");
			}
			note = "quét vùng điểm 1 → điểm 2…";
			return;
		}

		// Tell the mover where this layer's floor is (climb-vs-burrow decisions)
		// and which row every horizontal swing should follow — the same middle row
		// the 3x3 face takes its centre from, so a bore looks exactly like a face
		// dig instead of a series of nods at the floor.
		mover.setLayerFloor(plan.layerBottom());
		mover.setDigAimY(plan.faceCenter().getY());
		// Cùng một tầm với cho cả mod: mặt 9 ô vốn dùng tầm server (dài hơn 4.5
		// của config), phần đào đường trước đây kẹt ở 4.5 nên phải lết sát mới
		// bổ được — nhìn ra là "đào tới ô đỏ thì chậm".
		mover.setReach(player.getBlockInteractionRange());

		// Tool first: a dead pickaxe ends the run harder than anything else. This
		// stands still and throws exp until Mending has it back at full.
		if (tickExpRepair(player)) {
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

		// KHOÁ ĐẦU VÀO ĐÚNG MỘT BLOCK: đang bổ dở cái nào thì bám cái đó tới khi
		// vỡ, không cho phần nào khác đổi mục tiêu giữa chừng.
		//
		// Đây là gốc của "đào tâm một chỗ hai lần, đầu đang thẳng tâm lại quay
		// sang phải": máy mặt đào và máy đào-đường cùng ngắm trong những tick xen
		// kẽ nhau, mỗi bên kéo mắt về điểm của mình — tiến độ đập của vanilla bị
		// reset mỗi lần crosshair rời block, nên cùng một tâm phải bổ lại từ đầu.
		if (tickLockedDig(world, player)) {
			return;
		}

		// Vừa xuống tầng: bổ hai nhát xuống chân trước đã.
		if (tickDescendDigs(world, player)) {
			return;
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
		doneCells.clear();    // mặt đã xong của tầng cũ không còn ý nghĩa
		mover.forgetPlaced(); // trụ/viên kê của tầng cũ: từ giờ được vét bình thường
		straightQueue.clear();
		sweepAssessed = false;
		sweepReopenedLayer = false;
		sweepFaceMode = false; // tầng mới không thừa hưởng cờ cụm của tầng cũ
		if (plan.nextLayer()) {
			message("tầng " + finishedLayer + " sạch — xuống tầng " + (plan.layerIndex() + 1));
			note = "";
			armDescendDigs(player);
		} else {
			// Hết tất cả tầng - XONG!
			finish();
		}
	}

	/**
	 * Dig whatever the face pass left standing in this layer — <b>station by
	 * station</b>, the user's rule: "đứng im một chỗ và vét hết sạch xung quanh
	 * phạm vi 10 block, sau đó mới chuyển sang vét chỗ khác". From the current
	 * standing spot every hittable leftover within the 10-block station radius is
	 * chewed (clusters first, one AoE swing per clump, a breath between blocks);
	 * only when nothing at all can be hit from here does the bot relocate — once —
	 * to a stance beside the next-nearest cluster, and repeats.
	 *
	 * @return true while this layer still has work; false when it is clean.
	 */
	private boolean tickLayerSweep(ClientPlayerEntity player, World world) {
		if (!config.sweepLayer) {
			return false;
		}
		// First look at what the sweep was actually handed: a whole field of fresh
		// rock (regenerated mine + resumed cursor) is FACE work, not leftovers.
		if (!sweepAssessed) {
			sweepAssessed = true;
			if (!sweepReopenedLayer
					&& countLayerLeftovers(world, SWEEP_REOPEN_THRESHOLD) >= SWEEP_REOPEN_THRESHOLD) {
				sweepReopenedLayer = true;
				sweepAssessed = false; // re-assess when the reopened faces finish
				plan.restartLayer();
				phaseFace = null;
				frontDigsLeft = 0;
				restartFace();
				clearTarget();
				message("tầng " + (plan.layerIndex() + 1)
						+ " còn nhiều block nguyên — đào lại theo tâm 9 ô");
				return true;
			}
		}
		// The station radius only means something if the arm can match it: use the
		// server-granted reach when it is longer than the config's 4.5 (mine
		// servers extend it through their pickaxes).
		double reach = Math.max(config.reachDistance, player.getBlockInteractionRange());

		if (sweepTarget != null && !diggableTarget(world, sweepTarget)) {
			// Sổ chừa ô-ma quanh nhát vừa vỡ đã được ghi TẬP TRUNG ở tick() (mọi
			// kiểu đào đều qua đó) — ở đây chỉ còn nhịp nghỉ và buông mục tiêu.
			// Cụm to đào liền mạch như mặt 9 ô — nhịp nghỉ chỉ dành cho vét lẻ.
			sweepRest = sweepFaceMode ? 0 : SWEEP_REST_TICKS;
			sweepTarget = null;
			sweepFaceMode = false; // cờ chết theo mục tiêu, không để thừa hưởng
			sweepBlindTicks = 0;
		} else if (sweepTarget != null && !breaker.canReach(sweepTarget, reach)) {
			// KHÔNG buông mục tiêu chỉ vì MỘT tick không với tới.
			//
			// Đây là gốc của "vét xong cứ quay đầu hai lần": canReach bắn tia từ
			// mắt, mà lúc đầu còn đang xoay thì tia hay bị chính ô sót bên cạnh
			// chắn — mục tiêu bị vứt, tick sau chọn lại (có khi đúng nó), đầu
			// quay qua rồi quay lại. Cho nó một cửa sổ để xoay xong đã, chỉ khi
			// mất tầm LIÊN TỤC mới đổi mục tiêu.
			if (++sweepBlindTicks > SWEEP_BLIND_GRACE) {
				sweepTarget = null;
				sweepFaceMode = false;
				sweepBlindTicks = 0;
			}
		} else {
			sweepBlindTicks = 0;
		}
		if (sweepTarget == null && !mover.isClimbing()) {
			// A breath between leftovers: the user wants the sweep unhurried —
			// snapping to a new block the same tick the last one broke read as
			// frantic ("đào từ từ thôi, không quá nhanh").
			if (sweepRest > 0) {
				sweepRest--;
				input.stop();
				note = "vét sót — từ từ";
				return true;
			}
			// NHÁT VỪA BỔ CÒN GIẤU Ô NGAY SÁT MÌNH (≤3 block — chính cái cụm đang
			// chén dở): đứng chờ server xác nhận rồi bổ tiếp TẠI CỤM NÀY, đừng vớ
			// một ô khác xa hơn trong trạm — quay đầu sang đó rồi 0.4 giây sau
			// quay lại đúng cụm cũ là hai cú ngoái thừa mỗi nhát (soi vòng 3).
			if (!justSwept.isEmpty() && stationHiddenByGrace(world, player, 9.0)) {
				input.stop();
				note = "vét sót — chờ xác nhận nhát vừa bổ";
				return true;
			}
			// STATION RULE: everything hittable from right here comes first —
			// but NOT while a tower is going up. Grabbing a leftover mid-climb
			// swaps the block out for the pickaxe halfway through a hop, which
			// is the loop the user hit; the relocation walk below carries the
			// climb to its end first.
			// Ưu tiên tuyệt đối ô nhìn NGANG được (giữ nguyên tư thế tâm giữa);
			// chỉ khi cả trạm không còn ô nào như thế mới cho phép cúi đầu —
			// "nếu cần cúi đầu thì mới cúi, chỉ khi cần đến".
			sweepTarget = stationLeftover(world, player, reach, false);

			if (sweepTarget == null) {
				sweepTarget = stationLeftover(world, player, reach, true);
			}
			if (sweepTarget != null) {
				sweepTicks = 0;
				// Cụm to hay ô lẻ? Quyết ngay lúc chọn — cách đào khác hẳn nhau
				// (xem SWEEP_FACE_CLUMP_NEIGHBORS).
				sweepFaceMode = leftoverNeighbors(world, sweepTarget) >= SWEEP_FACE_CLUMP_NEIGHBORS;
				clearTargetKeepingStance();
			}
		}
		if (sweepTarget != null) {
			// Chưa gặm được sau 45 giây thì ĐỔI CHỖ ĐỨNG rồi quay lại nó, chứ
			// không ghi sổ bỏ qua nữa ("kẹt thì phải đi tới đó hoặc đào tới đó").
			// Block vẫn nằm nguyên trong danh sách sót, vòng vét sau sẽ gặp lại.
			//
			// Bỏ điều kiện cũ {@code activeTarget == null}: hễ crosshair chạm được
			// block là workTarget đặt activeTarget, nên cửa thoát này bị chặn cứng
			// đúng lúc cần nhất. Và phải NÉ ô đó một lúc, không thì tick sau
			// stationLeftover chọn lại chính nó (nó còn được miễn luật nhìn-ngang
			// vì là sweepRelocateTarget) — vòng 45 giây lặp vô hạn.
			if (++sweepTicks > SWEEP_TARGET_LIMIT) {
				stubborn.put(sweepTarget.toImmutable(), STUBBORN_COOLDOWN);
				sweepRelocateTarget = sweepTarget;
				sweepTarget = null;
				sweepFaceMode = false;
				sweepStance = null;
				triedStances.clear();
				note = "ô sót cứng đầu — đổi chỗ đứng rồi vét lại";
				return true;
			}
			// VÉT NHƯ NGƯỜI THẬT: ĐỨNG NGUYÊN MỘT CHỖ, quơ sạch mọi ô với tới
			// được ở mọi góc, hết sạch quanh mình rồi mới bước đi (luật chốt của
			// user: "vét xung quanh chỗ đó HẾT rồi mới di chuyển ra chỗ khác").
			//
			// Luật căn-tâm-từng-ô cũ (đi ra đường tâm của cụm rồi mới bổ) đã gỡ:
			// nó bắt chân lắt nhắt di chuyển cho TỪNG Ô một trong trạm — chính là
			// cái "vét di chuyển hơi nhiều". Đổi lại, nhát lệch tâm có thể chừa
			// vài ô của cụm — không sao: chúng vẫn nằm trong danh sách sót, được
			// chọn lại NGAY TỪ CHỖ ĐANG ĐỨNG ở nhát sau, thêm nhát chứ không thêm
			// bước chân.
			input.stop();
			workTarget(sweepTarget, reach);
			note = "vét quanh chỗ đứng";
			return true;
		}

		// This station is swept clean — relocate once, to beside the next cluster.
		// The full-layer cluster scan only runs when a stance actually needs
		// picking; while walking it would be pure waste, every tick.
		if (sweepStance == null) {
			// TRẠM CHƯA CHẮC ĐÃ SẠCH — có thể chỉ đang bị sổ chừa GIẤU TẠM. Nhát
			// vừa bổ giấu cả khối quanh nó vài tick chờ server xác nhận; nếu bỏ
			// đi cụm khác ngay thì lúc sổ hết hạn, mấy ô sống sót của cụm cũ hiện
			// lại và máy đi ngược về — chính vòng "đào xong đi ra rồi quay lại"
			// user quay được. Đứng yên tối đa {@link #SWEPT_ZONE_TICKS} tick là
			// biết thật giả: ô ma thì biến mất (đi tiếp không phải quay lại), ô
			// thật thì đào ngay tại chỗ, khỏi bước nào thừa.
			if (!justSwept.isEmpty() && stationHiddenByGrace(world, player, SWEEP_STATION_RADIUS_SQ)) {
				input.stop();
				note = "vét sót — chờ xác nhận nhát vừa bổ";
				return true;
			}
			BlockPos next = nearestLeftover(world, player);
			if (next == null) {
				// CHỪA KHÔNG PHẢI LÀ BỎ. Nếu mấy ô còn lại của tầng đang nằm trong
				// vùng vừa bổ ({@link #justSwept}) thì đứng chờ hết hạn chừa rồi
				// xét lại — kết luận "tầng sạch" lúc này là xuống tầng trong khi
				// trên đầu vẫn còn block, đúng thứ luật cấm. Chờ tối đa đúng một
				// giây nên không có cửa đứng hình.
				if (!justSwept.isEmpty() && countLayerLeftovers(world, 1) > 0) {
					input.stop();
					note = "vét sót — chờ xác nhận nhát vừa bổ";
					return true;
				}
				return false; // layer is clean
			}
			// A stance is only barred for the target it failed FOR — the old
			// forever-growing blacklist ended up banning every stance ever
			// visited, and late leftovers whose one good stance had been used
			// before were falsely abandoned as "no stance sees it".
			if (!next.equals(sweepRelocateTarget)) {
				triedStances.clear();
				sweepRelocateTarget = next;
			}
			sweepStance = standNear(world, player, next);
			if (sweepStance == null) {
				// Không chỗ nào NHÌN THẤY nó thì ĐÀO TỚI NÓ: lấy luôn cột của
				// chính nó ở sàn tầng làm đích. Mover sẽ khoan đường vào tận nơi
				// (nhát ngang, hàng giữa tầng) rồi vét từ đó. Trước đây nhánh này
				// ghi sổ bỏ qua — thứ user cấm: "kẹt thì phải đi tới đó hoặc đào
				// tới đó".
				BlockPos fallback = new BlockPos(next.getX(), plan.layerBottom(), next.getZ());

				// Cột của chính nó CŨNG phải qua bộ lọc "đã thử rồi": không thì
				// mỗi tick lại chọn đúng cột đó, mover.reset() xoá sạch lộ trình,
				// và cả tầng bị quét lại từ đầu — vòng lặp vô hạn tốn CPU mà thân
				// không nhúc nhích.
				if (triedStances.contains(fallback)) {
					triedStances.clear(); // cạn cách rồi thì quay lại vòng đầu
					return true;
				}
				sweepStance = fallback;
				note = "không thấy ô sót — đào đường tới tận nơi";
			}
			mover.reset();
		}
		// Trên đường tới ô sót mà bị chắn thì dọn cái chắn (cúi đầu cũng bổ) —
		// "đi tới đó hoặc đào tới đó", không có cửa đứng im.
		if (sweepRelocateTarget != null
				&& digObstacleToward(world, player, sweepRelocateTarget, reach)) {
			return true;
		}

		note = "vét sót — chuyển chỗ";
		MoveController.Result result = mover.moveTo(sweepStance);
		if (result == MoveController.Result.BLOCKED) {
			// Trapped below the layer with nothing to build with: STAND AND WAIT
			// for a resupply instead of blacklisting stances and silently giving
			// leftovers up one by one from inside the pit.
			if (player.getBlockY() < plan.layerBottom() && config.allowPlace
					&& !BlockPlacer.hasBuildingBlock(player)) {
				input.stop();
				warnNoBlocks("§ebị lọt dưới tầng khi vét mà hết block kê — bỏ đá cuội vào hotbar.");
				note = "chờ block để leo lên vét tiếp";
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
	 * The best leftover to chew FROM RIGHT HERE: within the 10-block station
	 * radius, genuinely hittable, <b>seen level</b>, biggest cluster first, then
	 * nearest. The loops are bounded to the station box intersected with the
	 * layer — iterating the whole 70x70 layer with the radius filter inside cost
	 * ~15k cells per tick.
	 *
	 * <p>The level rule is the user's ("vét thì vét ở trên cao đúng tâm giữa
	 * block ở tầng đó"): a leftover that can only be hit by nodding down at your
	 * own feet gets left for a stance that sees it straight on, because the 3x3
	 * swing follows the view — a steep nod takes a slanted slab of cells, not the
	 * flat row the sweep is trying to finish.
	 *
	 * <p>The one exception is the block we <em>walked here for</em>
	 * ({@code sweepRelocateTarget}): once a stance has been chosen for it and
	 * reached, it gets dug at whatever angle. Without that exception a block
	 * wedged at floor level would be refused here, send the sweep off to pick a
	 * stance for it, be refused again on arrival, and bounce between two stances
	 * forever.
	 */
	private BlockPos stationLeftover(World world, ClientPlayerEntity player, double reach, boolean allowSteep) {
		BlockPos feet = player.getBlockPos();
		int midY = plan.faceCenter().getY(); // hàng giữa tầng — ưu tiên bổ vào đây
		int radius = (int) Math.sqrt(SWEEP_STATION_RADIUS_SQ) + 1;
		int minT = Math.max(plan.minTravel(),
				(plan.travelAxis == Direction.Axis.X ? feet.getX() : feet.getZ()) - radius);
		int maxT = Math.min(plan.maxTravel(),
				(plan.travelAxis == Direction.Axis.X ? feet.getX() : feet.getZ()) + radius);
		int minC = Math.max(plan.minCross(),
				(plan.travelAxis == Direction.Axis.X ? feet.getZ() : feet.getX()) - radius);
		int maxC = Math.min(plan.maxCross(),
				(plan.travelAxis == Direction.Axis.X ? feet.getZ() : feet.getX()) + radius);
		BlockPos best = null;
		int bestCluster = -1;
		double bestDist = Double.MAX_VALUE;
		for (int y = plan.layerTop(); y >= plan.layerBottom(); y--) {
			for (int t = minT; t <= maxT; t++) {
				for (int c = minC; c <= maxC; c++) {
					BlockPos pos = plan.posAt(t, c, y);
					double dist = pos.getSquaredDistance(feet);
					if (dist > SWEEP_STATION_RADIUS_SQ || !diggableTarget(world, pos)
							|| stubborn.containsKey(pos) || justSwept.containsKey(pos)) {
						continue; // ô "lì" / ô vừa bổ quanh đây: tạm né, sau vẫn vét lại
					}
					if (!allowSteep && !pos.equals(sweepRelocateTarget)
							&& !aimLevel(player, pos, SWEEP_LEVEL_DEGREES)) {
						continue; // lượt một: chỉ ô nhìn thẳng, giữ tư thế tâm giữa
					}
					if (!breaker.canReach(pos, reach)) {
						continue;
					}
					// Ô ở HÀNG GIỮA tầng luôn được ưu tiên tuyệt đối: bổ vào giữa
					// thì cúp 3x3 ăn luôn ô trên và ô dưới cùng cột, tức là dọn cả
					// cột trong một nhát — đúng ý "vét là phải đào chính giữa của
					// tầng đó, không phải ở dưới 1 block". Nhắm ô dưới thì vừa
					// phải cúi, vừa chỉ ăn được phần dưới.
					int cluster = leftoverNeighbors(world, pos) + (y == midY ? 100 : 0);
					if (cluster > bestCluster
							|| (cluster == bestCluster && dist < bestDist)) {
						bestCluster = cluster;
						bestDist = dist;
						best = pos.toImmutable();
					}
				}
			}
		}
		return best;
	}

	/**
	 * Aim at {@code target} and, once the real crosshair rests on it, dig it —
	 * the sweep's compact version of AIM_LOCK/DIG_LOCKED. Same safety order as
	 * the face machine: the pickaxe only comes out after {@link #crosshairOn}.
	 *
	 * <p>{@code reach} MUST be the same value the caller used to select the
	 * target. The sweep selects with the server-granted extended reach; aiming
	 * here with the plain config value left targets in the ring between the two
	 * latched but unaimable — 45 seconds of standing still per block, and with
	 * the crosshair already resting on one, {@code activeTarget} stayed set and
	 * the park's {@code activeTarget == null} gate could never fire at all.
	 */
	private void workTarget(BlockPos target, double reach) {
		if (crosshairOn(target)) {
			if (!target.equals(activeTarget)) {
				setTarget(target);
			}
			if (!breaker.tickArmed(target, reach)) {
				breaker.disarm();
				activeTarget = null; // not being chewed: let the watchdogs see that
				return;
			}
			// MỘT LỐI ĐÀO DUY NHẤT CHO CẢ MOD (user: "lấy cái đào 9 ô đỏ sao chép
			// toàn bộ vào, vì cái đó đào ngon"). Mặt 9 ô đỏ trước đây là chỗ duy
			// nhất có bước ghì tiến; giờ nó nằm ngay trong hàm này, nên vét sót,
			// ba nhát đầu dãy, dọn chướng ngại, tụt tầng — tất cả đều ngắm-khoá-bổ
			// và bước tới y hệt nhau. Bản thân pressIntoTarget đã tự đứng im khi
			// đang ở lượt vét hoặc khi nhát bổ hướng xuống chân, nên không có
			// đường nào bị đẩy đi sai.
			ClientPlayerEntity digger = client.player;

			if (digger != null) {
				pressIntoTarget(digger, target);
			}
		} else {
			breaker.disarm();
			activeTarget = null;
			breaker.aimOnly(target, reach);
		}
	}

	/**
	 * The next leftover to sweep: <b>the middle of the biggest nearby cluster</b>,
	 * not merely the closest block. The pickaxe takes a 3x3 with every swing, so a
	 * clump of two or three leftovers should be hit once at its centre and fall
	 * together — "nếu có 3 ô thì đào ở tâm giữa nó, đừng đào từng block". The cell
	 * with the most leftover neighbours IS the clump's middle (in a row of three,
	 * the middle sees two friends, the ends one); distance breaks ties.
	 */
	private BlockPos nearestLeftover(World world, ClientPlayerEntity player) {
		BlockPos feet = player.getBlockPos();
		int midY = plan.faceCenter().getY();
		BlockPos best = null;
		int bestCluster = -1;
		double bestDist = Double.MAX_VALUE;
		for (int y = plan.layerTop(); y >= plan.layerBottom(); y--) {
			for (int t = plan.minTravel(); t <= plan.maxTravel(); t++) {
				for (int c = plan.minCross(); c <= plan.maxCross(); c++) {
					BlockPos pos = plan.posAt(t, c, y);
					if (!diggableTarget(world, pos) || stubborn.containsKey(pos)
							|| justSwept.containsKey(pos)) {
						continue; // ô "lì" / ô vừa bổ quanh đây: tạm né, sau vẫn vét lại
					}
					// Cùng luật ưu tiên hàng giữa như stationLeftover: đi tới một
					// cụm thì cũng nên nhắm vào ô giữa của nó để một nhát ăn cả cột.
					int cluster = leftoverNeighbors(world, pos) + (y == midY ? 100 : 0);
					double dist = pos.getSquaredDistance(feet);
					if (cluster > bestCluster
							|| (cluster == bestCluster && dist < bestDist)) {
						bestCluster = cluster;
						bestDist = dist;
						best = pos.toImmutable();
					}
				}
			}
		}
		return best;
	}

	/**
	 * Trong bán kính {@code radiusSq} quanh chân có ô sót nào đang bị sổ chừa
	 * {@link #justSwept} giấu tạm không — nếu có thì lượt vét phải ĐỨNG CHỜ chứ
	 * không được kết luận "quanh đây sạch" rồi bỏ đi chỗ khác.
	 */
	private boolean stationHiddenByGrace(World world, ClientPlayerEntity player, double radiusSq) {
		BlockPos feet = player.getBlockPos();
		for (BlockPos pos : justSwept.keySet()) {
			if (pos.getSquaredDistance(feet) <= radiusSq
					&& diggableTarget(world, pos) && !stubborn.containsKey(pos)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Ghi cả khối 3x3x3 quanh nhát vét vừa bổ vào sổ chừa — xem {@link #justSwept}.
	 *
	 * <p>Lấy nguyên khối chứ không chỉ 9 ô của mặt phẳng: hướng nhìn lệch một chút
	 * là mặt phẳng 3x3 của cúp nghiêng theo, nên vùng bị ăn trải ra cả khối.
	 */
	private void noteJustSwept(BlockPos centre) {
		for (int dx = -1; dx <= 1; dx++) {
			for (int dy = -1; dy <= 1; dy++) {
				for (int dz = -1; dz <= 1; dz++) {
					justSwept.put(centre.add(dx, dy, dz), SWEPT_ZONE_TICKS);
				}
			}
		}
	}

	/** Blocks still standing in this layer, counted up to {@code cap} then stopped. */
	private int countLayerLeftovers(World world, int cap) {
		int count = 0;
		for (int y = plan.layerTop(); y >= plan.layerBottom(); y--) {
			for (int t = plan.minTravel(); t <= plan.maxTravel(); t++) {
				for (int c = plan.minCross(); c <= plan.maxCross(); c++) {
					if (needsDigging(world, plan.posAt(t, c, y)) && ++count >= cap) {
						return count;
					}
				}
			}
		}
		return count;
	}

	/** How many other leftovers sit in the 3x3x3 around {@code pos} — one swing's haul. */
	private int leftoverNeighbors(World world, BlockPos pos) {
		int count = 0;
		for (int dx = -1; dx <= 1; dx++) {
			for (int dy = -1; dy <= 1; dy++) {
				for (int dz = -1; dz <= 1; dz++) {
					if ((dx | dy | dz) != 0 && needsDigging(world, pos.add(dx, dy, dz))) {
						count++;
					}
				}
			}
		}
		return count;
	}

	/**
	 * Keep the pickaxe alive: when the held tool's remaining durability drops to
	 * the configured threshold, stop digging, bring experience bottles to the
	 * main hand (ONE stack at a time out of the backpack, per the user: "lấy ra
	 * từng 1 st cho phải tay bên phải"), and throw them at the ground until
	 * Mending has the tool back at FULL durability — only then dig on.
	 *
	 * @return true while this tick belongs to the repair.
	 */
	private boolean tickExpRepair(ClientPlayerEntity player) {
		boolean testing = testThrowsLeft > 0;
		if (!config.expRepair && !testing) {
			repairing = false;
			return false;
		}
		// PHA HÚT: sửa xong (hoặc hết bình) thì đứng im cho tới khi quanh người
		// không còn viên orb exp nào — orb tự bay vào người, Mending ăn nốt.
		// Có trần 10 giây phòng orb kẹt xó không bao giờ tới được.
		if (drainingOrbs) {
			boolean orbsLeft = client.world != null && !client.world.getEntitiesByClass(
					net.minecraft.entity.ExperienceOrbEntity.class,
					player.getBoundingBox().expand(4.0), orb -> true).isEmpty();
			if (orbsLeft && ++orbDrainTicks <= ORB_DRAIN_LIMIT) {
				input.stop();
				breaker.cancel();
				note = "chờ hút hết exp quanh người…";
				return true;
			}
			drainingOrbs = false;
			orbDrainTicks = 0;
			return false;
		}
		PlayerInventory inventory = player.getInventory();
		if (!repairing) {
			// Never interrupt a climb or a recovery placement — the oldest rule
			// in this codebase: whoever is placing owns the hand.
			if (mover.isClimbing() || recovering) {
				return false;
			}
			int slot = inventory.getSelectedSlot();
			ItemStack held = inventory.getStack(slot);
			if (!held.isDamageable()
					|| held.getMaxDamage() - held.getDamage() > config.expRepairThreshold) {
				return false;
			}
			if (expExhausted) {
				// Bottles ran dry earlier: only try again once some reappear.
				if (hotbarSlotOf(inventory, Items.EXPERIENCE_BOTTLE) < 0
						&& backpackSlotOf(inventory, Items.EXPERIENCE_BOTTLE) < 0) {
					return false;
				}
				expExhausted = false;
			}
			repairing = true;
			repairSlot = slot;
			throwTimer = 0;
			message("cúp còn " + (held.getMaxDamage() - held.getDamage())
					+ " độ bền — dừng đào, quăng exp hồi cúp");
		}

		ItemStack tool = inventory.getStack(repairSlot);
		// A test ignores durability: it throws its ten bottles no matter what.
		if (!testing && (tool.isEmpty() || !tool.isDamageable() || tool.getDamage() == 0)) {
			// Full again (or the tool vanished) — hand everything back, hút nốt
			// orb còn bay quanh người rồi mới đào (luật user: "chờ exp hết hẳn
			// đi rồi mới đào tiếp" — vừa đỡ phí exp vừa không vung cúp giữa đám
			// orb đang bám).
			inventory.setSelectedSlot(repairSlot);
			restoreOffhand(player);
			repairing = false;
			refillGraceTicks = 0;
			if (!tool.isEmpty() && tool.isDamageable() && tool.getDamage() == 0) {
				message("cúp đầy độ bền — hút nốt exp rồi đào tiếp");
			}
			drainingOrbs = true;
			orbDrainTicks = 0;
			return true;
		}

		input.stop();
		breaker.cancel();
		activeTarget = null;

		// The pickaxe STAYS in the main hand the whole time — per the user, the
		// tool only receives the repair while it is held ("phải cầm 2 vật phẩm
		// thì cúp mới nhận được sửa chữa") — and the bottles are thrown from the
		// OFF hand instead.
		if (inventory.getSelectedSlot() != repairSlot) {
			inventory.setSelectedSlot(repairSlot);
		}

		if (player.getOffHandStack().getItem() != Items.EXPERIENCE_BOTTLE) {
			// Bring the next stack to the off hand (slot-swap button 40 — the same
			// move as pressing F). Whatever lived there (usually the totem) parks
			// in the source slot and is swapped back when the repair ends.
			int sourceSlotId;
			int hotbar = hotbarSlotOf(inventory, Items.EXPERIENCE_BOTTLE);
			if (hotbar >= 0) {
				sourceSlotId = 36 + hotbar; // hotbar i -> PlayerScreenHandler slot 36+i
			} else {
				sourceSlotId = backpackSlotOf(inventory, Items.EXPERIENCE_BOTTLE);
			}
			if (sourceSlotId < 0) {
				if (testing) {
					message("§etest ném: không có bình exp nào trong người.");
					testThrowsLeft = 0;
					restoreOffhand(player);
					repairing = false;
					return false;
				}
				// KHÔNG TIN MỘT LẦN QUÉT TRƯỢT. Sau chuỗi swap + ném dày nhịp,
				// túi đồ client hay có vài tick chưa đồng bộ với server — quét
				// đúng tick đó là "không thấy bình" trong khi bình vẫn nằm đấy,
				// và bản cũ tuyên "hết exp" oan (user bắt được: ném 1 stack xong
				// kêu hết trong khi inventory vẫn còn). Đứng chờ quét lại tối đa
				// 1 giây; qua đó vẫn trống thì mới là hết thật.
				if (++refillGraceTicks <= EXP_REFILL_GRACE) {
					input.stop();
					note = "chờ túi đồ đồng bộ để lấy exp tiếp…";
					return true;
				}
				refillGraceTicks = 0;
				message("§ehết bình exp — cúp còn "
						+ (tool.getMaxDamage() - tool.getDamage()) + " độ bền.");
				expExhausted = true;
				restoreOffhand(player);
				repairing = false;
				// Chưa đào vội — còn orb bay quanh thì đứng hút cho hết đã.
				drainingOrbs = true;
				orbDrainTicks = 0;
				return true;
			}
			refillGraceTicks = 0;
			if (offhandReturnSlot < 0) {
				offhandReturnSlot = sourceSlotId;
			}
			client.interactionManager.clickSlot(player.playerScreenHandler.syncId,
					sourceSlotId, PlayerInventory.OFF_HAND_SLOT, SlotActionType.SWAP, player);
			note = "đưa 1 stack exp sang tay phụ";
			return true;
		}

		// Throw at the ground so the orbs pool right at the feet. Pitch only —
		// steering yaw toward a point underfoot is the known spin bug.
		player.setPitch(Math.min(90.0F, player.getPitch() + 30.0F));
		if (++throwTimer >= EXP_THROW_INTERVAL) {
			throwTimer = 0;
			ActionResult result = client.interactionManager.interactItem(player, Hand.OFF_HAND);
			if (result instanceof ActionResult.Success success
					&& success.swingSource() == ActionResult.SwingSource.CLIENT) {
				player.swingHand(Hand.OFF_HAND);
			}
			if (testing && --testThrowsLeft <= 0) {
				inventory.setSelectedSlot(repairSlot);
				restoreOffhand(player);
				repairing = false;
				message("test ném exp xong.");
				return true;
			}
		}
		note = testing
				? "test ném exp — còn " + testThrowsLeft + " bình"
				: "quăng exp hồi cúp — độ bền "
						+ (tool.getMaxDamage() - tool.getDamage()) + "/" + tool.getMaxDamage();
		return true;
	}

	/** Swap back whatever lived in the off hand before the repair (the totem). */
	private void restoreOffhand(ClientPlayerEntity player) {
		if (offhandReturnSlot >= 0 && client.interactionManager != null) {
			client.interactionManager.clickSlot(player.playerScreenHandler.syncId,
					offhandReturnSlot, PlayerInventory.OFF_HAND_SLOT, SlotActionType.SWAP, player);
			offhandReturnSlot = -1;
		}
	}

	private static int hotbarSlotOf(PlayerInventory inventory, Item item) {
		for (int slot = 0; slot < PlayerInventory.HOTBAR_SIZE; slot++) {
			if (inventory.getStack(slot).getItem() == item) {
				return slot;
			}
		}
		return -1;
	}

	/** Backpack slot (9..35) holding {@code item} — doubles as the click-slot id. */
	private static int backpackSlotOf(PlayerInventory inventory, Item item) {
		for (int slot = 9; slot < 36; slot++) {
			if (inventory.getStack(slot).getItem() == item) {
				return slot;
			}
		}
		return -1;
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
		// The sweep owns its own verticality end to end (stations only chew what
		// canReach confirms; relocation walks the floor and climbs at the right
		// column), so recovery never hijacks it — hijacking the FIRST tick after
		// a /start that resumed into the sweep was the mystery ladder the user
		// saw ("bật /start thì thấy nó bắc thang làm gì ko biết").
		// edgeDigging: đang CỐ Ý đứng dưới tầng để với lên đào (luật "đào hết cỡ
		// rồi mới bắc") — recovery mà giật tick lúc này là cầm BLOCK đè lên cái
		// tay đang cầm CÚP, tái sinh đúng vòng "block-cúp" vừa dập.
		boolean navigating = plan.areLayerFacesDone()
				|| phase == Phase.APPROACH || phase == Phase.REPOSITION
				|| sweepStance != null || edgeDigging;
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
		// NOTE: no blanket breaker.cancel() here — the climb itself decides. Its
		// placement path silences the breaker every tick already, and its ceiling
		// path ARMS it upward; cancelling from out here wiped that ceiling dig's
		// vanilla progress every single tick, so a roofed-over pit never opened
		// (one more variant of the place-vs-dig fight).
		if (!mover.pillarUp(player)) {
			if (!BlockPlacer.hasBuildingBlock(player)) {
				// Nothing to build with. Stand still and wait to be resupplied rather
				// than walking out and digging from the wrong height.
				input.stop();
				warnNoBlocks("§ebị lọt xuống dưới tầng mà hotbar không có block đặc nào để kê — bỏ đá cuội vào hotbar.");
				note = "lọt xuống — chờ block để kê lên";
				return true;
			}
			// Blocks in hand but THIS column can't rise (hops keep failing under a
			// roof): hand the tick back to the normal machine, whose reposition
			// walk finds a nicer column to pillar — "bị kẹt thì tìm chỗ khác đẹp".
			recovering = false;
			return false;
		}
		note = mover.isBreakingCeiling()
				? "lọt xuống — phá trần trên đầu để kê tiếp"
				: "lọt xuống — kê block leo lên (" + (recoverTicks / 20) + "s)";
		return true;
	}

	/** Move to a new step of the current face, resetting its budget. */
	private void enterPhase(Phase next) {
		if (phase != next) {
			phase = next;
			phaseTicks = 0;
		}
		// Rời cặp AIM_LOCK/DIG_LOCKED là hết lượt với-từ-mép: quay về đi lại
		// (APPROACH/REPOSITION) thì recovery và cú leo lấy lại quyền như thường.
		if (next == Phase.APPROACH || next == Phase.REPOSITION) {
			edgeDigging = false;
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
		faceTicks = 0;
		repositionTarget = null;
		// Giếng vào tâm phải SỐNG QUA nhiều mặt: đủ ba nhát mới thôi.
		//
		// Trước đây dòng này là {@code useEntryStance = false}, mà restartFace()
		// chạy MỖI LẦN đổi mặt — nên vừa quay dãy, đào được một hai nhát là cờ bị
		// xoá, đích đổi lại về ô đứng chuẩn ở cuối dãy cũ, thân đi ngược ra rồi
		// kẹt: đúng cảnh "đào 2 lần xong lại bị nhảy nữa". Giữ cờ theo
		// {@code frontDigsLeft} thì ba nhát mới thật sự là ba nhát.
		useEntryStance = frontDigsLeft > 0;
		straightRescueTried = false; // mặt mới thì lại được một cú cứu ba nhát
		edgeDigging = false; // mặt mới tự quyết lại chuyện với-từ-mép
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
		int examined = 0;
		while (!faceHasWork(world) && !plan.areLayerFacesDone() && examined++ < SKIP_BUDGET) {
			plan.advance();
		}
		if (!faceHasWork(world)) {
			note = "kiểm tra tầng";
			return;
		}

		BlockPos centre = plan.faceCenter();
		if (!centre.equals(phaseFace)) {
			// A genuinely different face: start it from the beginning. This is the only
			// place phaseFace is set, so the reset happens exactly once per face — an
			// earlier version also cleared it inside restartFace(), which re-triggered
			// this branch every tick and pinned the face on APPROACH forever.
			// Trừ TRƯỚC rồi mới restartFace: restartFace giữ cờ giếng theo
			// frontDigsLeft, nên trừ sau sẽ dư ra một mặt thứ tư vẫn bám giếng.
			// Trừ trước thì đúng ba mặt A-B-C, mặt D trả về lộ trình thẳng.
			if (frontDigsLeft > 0) {
				frontDigsLeft--; // one of the three in-front faces is done
			}
			// Mặt VỪA RỜI coi như xong: ghi cả 9 ô của nó vào sổ "đừng đào lại".
			// phaseFace là tâm mặt cũ, và con trỏ chỉ rời một mặt khi mặt đó hết
			// việc, nên đây đúng là lúc chốt.
			if (phaseFace != null) {
				markFaceDone(phaseFace);
			}
			restartFace();
			clearTarget();
			phaseFace = centre.toImmutable();
			stuckRounds = 0; // mặt mới, đếm lại từ đầu
		}

		// VỪA QUAY SANG DÃY MỚI: quên lộ trình cũ, đi về ĐÚNG Ô ĐỨNG của dãy mới
		// rồi mới đào — không có ngoại lệ nào cho phép bổ từ chỗ lệch.
		//
		// Bản trước bật "đào giếng vào mặt" ở đây (đích = cột của chính mặt đó).
		// Sai: đứng trong cột nghĩa là thân NẰM GIỮA khối 9 ô, bổ từ đó thì mặt
		// phẳng 3x3 lệch hẳn, mà mover cũng không settle được nên tự nhảy liên
		// tục — đúng cảnh trong ảnh user gửi. Giếng chỉ còn dùng cho nhánh cứu
		// kẹt, không phải cho việc quay dãy bình thường.
		//
		// Lần /start đầu tiên cũng rơi vào đây ({@code lastRow} khởi tạo -1) nên
		// mở màn là đi thẳng về ô đứng của mặt đầu dãy — ô này nằm ngay SÁT MÉP
		// vùng, đúng yêu cầu "mới đầu /start phải vào sát mép để đào".
		if (plan.rowIndex() != lastRow) {
			lastRow = plan.rowIndex();
			frontDigsLeft = 0;
			useEntryStance = false;
			armStraightDigs(centre);
			mover.reset();
			enterPhase(Phase.APPROACH);
			note = "sang dãy mới — bổ " + STRAIGHT_DIGS_PER_ROW + " nhát thẳng hàng";
		}

		// Ba nhát đầu dãy ĐI TRƯỚC MỌI THỨ: chưa xong thì máy chính (đi lại, đổi
		// chỗ đứng, đồng hồ kẹt) không được đụng vào, nên không thể bổ hai nhát
		// rồi bỏ đi như trước.
		if (client.player != null && tickStraightDigs(world, client.player)) {
			return;
		}

		phaseTicks++;

		// Escape hatch for degenerate faces (wedged inside the 3x3, no workable
		// angle): after 8 idle seconds, park it and keep the route moving —
		// "cứ đào xong rồi tiếp tục hướng đi của mình". Actively chewing a hard
		// block resets the clock, so slow rock is never abandoned.
		// "Stuck" means nothing is happening anywhere: no face target being
		// chewed, no block being bored through en route, no climb in progress,
		// and the body NOT moving. The climb test keeps the placement sacred
		// ("vừa đặt vừa lấy cúp" — the oldest bug family here); the movement test
		// keeps honest travel from being punished as a stall.
		boolean working = activeTarget != null || breaker.aiming() != null
				|| mover.isClimbing() || movedThisTick;
		faceTicks = working ? 0 : faceTicks + 1;
		if (faceTicks > FACE_STUCK_TICKS) {
			// KẸT THÌ ĐỔI CÁCH TIẾP CẬN, KHÔNG BỎ MẶT ĐÀO. Luật user: "kẹt thì
			// phải đi tới đó hoặc đào tới đó". Bản trước cứ 8 giây không nhúc
			// nhích là ghi mặt đó vào sổ bỏ qua rồi đẩy con trỏ đi — nên chỗ khó
			// bị chừa lại nguyên khối, đúng thứ user không chấp nhận.
			//
			// Ba cách xoay vòng, hết vòng thì quay lại cách đầu, mãi mãi:
			//   1. Khoan thẳng vào cột tâm của mặt (đào giếng vào).
			//   2. Tìm chỗ đứng khác nhìn thấy tâm.
			//   3. Quên lộ trình cũ, tính lại đường đi từ đầu.
			faceTicks = 0;
			stuckRounds++;
			clearTargetKeepingStance();

			switch (stuckRounds % 3) {
				case 1 -> {
					frontDigsLeft = 3;
					useEntryStance = true;
					mover.reset();
					enterPhase(Phase.APPROACH);
					message("§ekẹt " + (FACE_STUCK_TICKS / 20) + "s — khoan thẳng vào cột tâm");
				}
				case 2 -> {
					triedStances.clear();
					mover.reset();
					enterPhase(Phase.REPOSITION);
					message("§ekẹt — tìm chỗ đứng khác để với tới tâm");
				}
				default -> {
					triedStances.clear();
					// frontDigsLeft PHẢI về 0 trước: restartFace() suy ra
					// useEntryStance từ nó, nên bỏ sót dòng này thì lần đổi mặt kế
					// tiếp bật lại chế độ giếng, đúng thứ nhánh này vừa tắt đi.
					frontDigsLeft = 0;
					useEntryStance = false;
					mover.reset();
					enterPhase(Phase.APPROACH);
					message("§ekẹt — tính lại đường đi tới mặt");
				}
			}
			return;
		}

		// 1.5s stuck → front-dig mode, ngay lập tức: goal chuyển sang chính cột
		// tâm của mặt đào ("di chuyển đúng tâm ô đó"), luật ngắm-ngang được miễn,
		// ăn 3 mặt liên tiếp từ chỗ đó rồi tự trả về lộ trình cũ ("đào 3 lần để
		// tới hướng tiếp tục vừa nãy") — cùng bộ máy với nhánh approach-bị-chặn,
		// nên không có chủ thứ hai nào đụng vào breaker. The 8s park above stays
		// the backstop for when even charging the column can't move.
		// The trigger only exists for wedges AT the face (RESCUE_NEAR_SQ): en
		// route, travelling belongs to APPROACH and its own boring resets the
		// idle clock anyway.
		ClientPlayerEntity self = client.player;
		boolean nearFace = self != null
				&& centre.getSquaredDistance(self.getBlockPos()) <= RESCUE_NEAR_SQ;
		if (nearFace && !straightRescueTried && faceTicks > FACE_RESCUE_TICKS
				&& straightQueue.isEmpty()) {
			straightRescueTried = true;
			// KẸT THÌ BỔ BA NHÁT THẲNG, ĐÚNG BỘ MÁY CỦA ĐẦU DÃY.
			//
			// User chốt: "bị kẹt vào ở giữa, 9 ô ở trong góc, nên khi thấy ko đào
			// thì cứ đào 3 nhát như cách kia rồi đào tiếp tục". "Cách kia" chính là
			// {@link #armStraightDigs} — thứ đang chạy ngon mỗi lần sang dãy mới:
			// nạp mấy tâm mặt kế tiếp rồi bổ cho đủ BA NHÁT THẬT, ô nào vốn trống
			// thì bỏ qua chứ không tính gian.
			//
			// Bản trước bật chế độ "giếng" ({@code useEntryStance}) ở đây: đích đổi
			// sang chính cột của mặt, tức là chui vào GIỮA khối 9 ô — đúng cái thế
			// kẹt mà user vừa tả, vì đứng trong đó thì luật đứng-lùi không bao giờ
			// đạt và mặt phẳng 3x3 cũng không trùng 9 ô cần đào. Ba nhát thẳng thì
			// bổ từ ngoài vào, mở đường rồi trả về lộ trình cũ.
			armStraightDigs(centre);
			// Nạp không ra ô nào (mặt nằm sát rìa vùng, hướng dãy hết đất) thì im
			// lặng nhường cho nấc 8 giây — KHÔNG reset faceTicks, không nhắn gì,
			// kẻo cứ 1.5 giây lại hô một câu mà chẳng bổ được nhát nào.
			if (!straightQueue.isEmpty()) {
				frontDigsLeft = 0;
				useEntryStance = false;
				mover.reset();
				faceTicks = 0; // cú xử lý này được trọn cửa sổ riêng trước nấc 8 giây
				enterPhase(Phase.APPROACH);
				message("§ekẹt 1.5s — bổ " + STRAIGHT_DIGS_PER_ROW + " nhát thẳng rồi đi tiếp");
			}
		}

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
		return aimLevel(player, centre, 30.0);
	}

	/**
	 * Thân có nằm đúng trên ĐƯỜNG TÂM của mặt đào không (lệch ngang ≤ nửa block)?
	 *
	 * <p>Cúp 3x3 ăn một mặt phẳng vuông góc với mặt block mà crosshair chạm vào.
	 * Đứng đúng hàng thì mặt phẳng đó trùng khít 9 ô của mặt đang làm; lệch sang
	 * bên là nó xẻ sang cột khác và chừa lại đúng mấy ô mình định đào — user gọi
	 * là "đang đi mà đào lệch". Chỉ xét trục NGANG (trục vuông góc hướng chạy),
	 * vì dọc theo hướng chạy thì đứng gần hay xa đều bổ vào cùng một mặt.
	 */
	private boolean faceAligned(ClientPlayerEntity player, BlockPos centre) {
		double cross = plan.travelAxis == Direction.Axis.X
				? player.getZ() - (centre.getZ() + 0.5)
				: player.getX() - (centre.getX() + 0.5);

		if (Math.abs(cross) > FACE_ALIGN_TOLERANCE) {
			return false;
		}


		// ... và phải đứng LÙI LẠI, ngoài mặt phẳng của khối 9 ô.
		//
		// Đứng ngay trong cột của mặt thì thân nằm giữa khối 9 ô: bổ từ đó là bổ
		// vào ô ngay dưới/ngay trên chân mình, mặt phẳng 3x3 xoay theo hướng
		// nhìn chứ không trùng mặt đào, và mover không bao giờ "tới nơi" được nên
		// nhảy loi choi tại chỗ. Lùi tối thiểu 0.7 block là đứng hẳn ngoài mặt.
		return Math.abs(alongOffset(player, centre)) >= FACE_STANDOFF;
	}

	/** Khoảng cách thân ↔ mặt phẳng khối 9 ô, đo dọc theo hướng chạy của dãy. */
	private double alongOffset(ClientPlayerEntity player, BlockPos centre) {
		return plan.travelAxis == Direction.Axis.X
				? player.getX() - (centre.getX() + 0.5)
				: player.getZ() - (centre.getZ() + 0.5);
	}

	// (sweepCentred đã gỡ 2026-08-20: luật căn-tâm-từng-ô của lượt vét bắt chân
	// lắt nhắt di chuyển cho từng ô — trái luật "đứng một chỗ vét hết rồi mới đi".)

	/** Góc nhìn từ mắt tới tâm {@code pos} có nằm trong {@code maxDegrees} so với phương ngang không? */
	private static boolean aimLevel(ClientPlayerEntity player, BlockPos pos, double maxDegrees) {
		Vec3d eye = player.getEyePos();
		double dy = pos.getY() + 0.5 - eye.y;
		double horizontal = Math.hypot(pos.getX() + 0.5 - eye.x, pos.getZ() + 0.5 - eye.z);
		return Math.abs(Math.toDegrees(Math.atan2(dy, horizontal))) <= maxDegrees;
	}

	/**
	 * Mở lượt tụt tầng: ĐI THẲNG TỚI đúng cột ô 9 của tầng mới, đứng lên đó, cúi
	 * bổ hai nhát xuống chân rồi đào tiếp theo hướng — lệnh chốt cuối của user
	 * ("đi tới thẳng chỗ đó và đào 2 nhát xuống rồi tiếp tục đào theo hướng";
	 * bản đứng-lùi-hai-block bổ từ xa đã gỡ theo cùng lệnh).
	 */
	private void armDescendDigs(ClientPlayerEntity player) {
		endDescendDigs();

		if (player == null) {
			return;
		}
		// ĐI THẲNG TỚI ĐÚNG CỘT Ô 9 rồi cúi bổ xuống ngay tại chỗ (lệnh chốt của
		// user: "đi tới thẳng chỗ đó và đào 2 nhát xuống rồi tiếp tục đào theo
		// hướng"). Bản đứng-lùi-2-block bổ từ xa đã gỡ theo cùng lệnh.
		BlockPos well = plan.faceCenter();
		BlockPos stand = new BlockPos(well.getX(), plan.layerTop() + 1, well.getZ());
		// Nóc tầng+1 là chỗ đứng khi tầng còn nguyên khối đá. Hộp đánh dấu từ mặt
		// sàn mine thì hàng trên của tầng là KHÍ và mặt đi lại thấp hơn một mức —
		// giữ Y cứng là bắt mover đi tới một ô lơ lửng, nó liền kê block dưới chân
		// để "tới" cho bằng được. Dò xuống theo chỗ đặt chân thật.
		World world = client.world;
		if (world != null && !BlockUtil.standable(world, stand)
				&& BlockUtil.standable(world, stand.down())) {
			stand = stand.down();
		}
		descendStand = stand.toImmutable();
		descendColumn = descendStand;
	}

	/**
	 * Bổ HAI NHÁT THẬT xuống chân tại cột ô 9: đào — rơi một mức — chờ đáp — căn
	 * lại — đào. Nhát chỉ được tính khi ô mình ĐANG bổ thật sự vỡ; ô dưới chân
	 * hoá khí mà thân chưa kịp rơi thì đứng chờ chứ không dẹp lượt (vết xe đổ
	 * "chỉ đào 1 nhát" cũ).
	 *
	 * @return true khi tick này thuộc về việc tụt tầng.
	 */
	private boolean tickDescendDigs(World world, ClientPlayerEntity player) {
		// Bước 1: đi thẳng tới Ô ĐỨNG = chính cột ô 9 của tầng mới, ở mặt trên.
		// descendTicks tăng ở tick(), không tăng ở đây — xem chú thích trên đó.
		if (descendStand != null) {
			if (descendTicks > STRAIGHT_DIG_LIMIT) {
				descendStand = null; // đi mãi không tới — để máy chính lo
				return false;
			}
			MoveController.Result result = mover.moveTo(descendStand);

			if (result == MoveController.Result.ARRIVED) {
				descendStand = null;
				descendDigging = true;
				descendSwings = 0;
				descendWorking = null;
				descendTicks = 0;
				return true;
			}
			if (result == MoveController.Result.BLOCKED) {

				// Chắn đường thì dọn chắn, không đứng nhìn.
				double reach = Math.max(config.reachDistance, player.getBlockInteractionRange());

				if (digObstacleToward(world, player, descendStand, reach)) {
					return true;
				}
				descendStand = null;
				return false;
			}
			note = "xuống tầng — tới trên đầu ô 9";
			return true;
		}

		// Bước 2: cúi bổ xuống chân cho đủ HAI NHÁT THẬT, ngay tại cột ô 9.
		if (!descendDigging) {
			return false;
		}
		// Ô đang bổ đã vỡ → tính một nhát; thân sẽ rơi theo, chờ đáp rồi bổ tiếp.
		if (descendWorking != null && !needsDigging(world, descendWorking)) {
			descendSwings++;
			descendWorking = null;
			descendTicks = 0;
		}
		if (descendSwings >= LAYER_DESCEND_DIGS) {
			// XÁC NHẬN XONG HẲN RỒI MỚI TRẢ MÁY CHÍNH (luật user: "đào xong 2
			// nhát, xác định đào xong rồi mới đào hướng 9 ô tiếp"). Bản trước trả
			// ngay cái tick nhát 2 vỡ — thân còn đang rơi xuống đáy giếng thì máy
			// mặt đã chộp tick, ngắm mặt kế, đầu ngóc dậy giữa chừng. Giờ: đợi
			// ĐÁP ĐÁY đã, nghỉ đúng một nhịp ngắn cho ra tấm ra món, rồi mới ngẩng
			// đầu vào việc — cả lượt cúi là một khối liền: cúi, bổ, bổ, đáp, dậy.
			if (!player.isOnGround()) {
				input.stop();
				note = "xuống tầng — chờ đáp đáy giếng";
				return true;
			}
			if (++descendSettleTicks <= DESCEND_SETTLE_TICKS) {
				input.stop();
				note = "xuống tầng — xong 2 nhát, vào việc";
				return true;
			}
			return endDescendDigs(); // đủ hai mức, đã đáp — máy chính đào theo hướng
		}
		if (descendTicks > STRAIGHT_DIG_LIMIT) {
			return endDescendDigs(); // lì quá thì thôi, để máy chính lo
		}
		// ĐANG RƠI THÌ ĐỨNG YÊN CHỜ ĐÁP — bổ trong lúc thân trôi là tia ngắm chạy
		// theo mắt, ô dưới chân đổi giữa chừng, nhát hai không bao giờ thành hình.
		if (!player.isOnGround()) {
			input.stop();
			note = "xuống tầng — chờ đáp rồi bổ nhát " + (descendSwings + 1)
					+ "/" + LAYER_DESCEND_DIGS;
			return true;
		}

		// "ĐI RA GIỮA, đi ngang đúng chỗ" (luật user): thân nằm gọn giữa Ô ĐỨNG
		// (thẳng hàng với cột giếng) rồi mới bổ. Nhích bằng phím, KHÔNG xoay đầu.
		//
		// CÓ CHỐT: căn xong MỘT LẦN là đứng chết tại chỗ. Bản trước tick nào cũng
		// đo lại với đúng một ngưỡng — nhích vào, trớn đẩy lố ra ngoài ngưỡng,
		// nhích ngược, lại lố… thân lắc qua lắc lại quanh tâm suốt lượt bổ, đúng
		// "không cố định 1 chỗ" user quát. Chốt chỉ nhả khi bị đẩy văng thật sự
		// (lệch quá 0.45 — có gì đó húc mình), còn rung động thường thì mặc kệ.
		// Quá 3 giây chưa khớp thì bổ luôn — vẫn đúng cột, chỉ kém đẹp.
		if (descendColumn != null) {
			double offX = player.getX() - (descendColumn.getX() + 0.5);
			double offZ = player.getZ() - (descendColumn.getZ() + 0.5);
			double off = Math.max(Math.abs(offX), Math.abs(offZ));
			if (descendCentred && off > 0.45) {
				descendCentred = false; // bị húc văng khỏi ô — căn lại
			}
			if (!descendCentred) {
				if (off <= DESCEND_CENTER_MARGIN) {
					descendCentred = true;
				} else if (++descendCentreTicks <= DESCEND_CENTER_LIMIT) {
					double yawRad = Math.toRadians(player.getYaw());
					double sin = Math.sin(yawRad);
					double cos = Math.cos(yawRad);
					input.set(nudgeAxis((-offZ) * cos - (-offX) * sin),
							nudgeAxis((-offZ) * sin + (-offX) * cos), false, false);
					note = "xuống tầng — căn thẳng hàng với giếng";
					return true;
				}
			}
		}

		// ĐỨNG ĐÚNG HƯỚNG ĐÀO RỒI MỚI CÚI (luật user kèm ảnh): quay mũi về hướng
		// chạy của dãy trước nhát đầu tiên. Cúi trong lúc mũi còn theo hướng vừa
		// đi tới thì xuống đáy ngẩng lên là đang nhìn sai đường — nhát tiếp theo
		// đào lệch hướng. Quay xong một lần là yaw đứng im suốt hai nhát (ngắm ô
		// dưới chân chỉ chỉnh pitch, không đụng yaw).
		if (descendSwings == 0 && descendWorking == null) {
			Direction dir = plan.travelAxis == Direction.Axis.X
					? (plan.rowDirection() > 0 ? Direction.EAST : Direction.WEST)
					: (plan.rowDirection() > 0 ? Direction.SOUTH : Direction.NORTH);
			if (Rotations.stepYawTo(player,
					Rotations.yawTo(dir.getOffsetX(), dir.getOffsetZ()), 20.0F)) {
				input.stop();
				note = "xuống tầng — quay về hướng đào";
				return true;
			}
		}

		BlockPos target = player.getBlockPos().down();
		double reach = Math.max(config.reachDistance, player.getBlockInteractionRange());

		if (!selection.contains(target)) {
			return endDescendDigs(); // chạm đáy vùng — trả máy chính
		}
		// Ô DƯỚI CHÂN VỪA VỠ MÀ THÂN CHƯA KỊP RƠI THÌ CHỜ, ĐỪNG DẸP CẢ LƯỢT —
		// thấy khí mà kết thúc ngay là tái phát "chỉ đào 1 nhát".
		if (world.getBlockState(target).isAir()) {
			if (++descendWaitTicks > DESCEND_WAIT_LIMIT) {
				return endDescendDigs(); // treo lâu (đứng chàng hảng) — máy chính lo
			}
			input.stop();
			note = "xuống tầng — chờ tụt xuống rồi bổ nhát " + (descendSwings + 1)
					+ "/" + LAYER_DESCEND_DIGS;
			return true;
		}
		descendWaitTicks = 0;
		// Ô bị khoá 60 giây tuyên "lì" (server bảo kê)? Trả máy chính ngay — arm
		// lại đúng nó là vòng vô hạn 60 giây/lượt.
		if (stubborn.containsKey(target)) {
			return endDescendDigs();
		}
		if (!needsDigging(world, target) || !breaker.canReach(target, reach)) {
			return endDescendDigs(); // nền không phá được / mất tầm — trả máy chính
		}
		descendWorking = target.toImmutable();
		input.stop();
		workTarget(target, reach); // cúi bổ thẳng xuống, một tư thế — yaw không đổi
		note = "xuống tầng — bổ xuống " + (descendSwings + 1) + "/" + LAYER_DESCEND_DIGS;
		return true;
	}

	/** Kết thúc lượt tụt tầng, trả tick lại cho máy chính. */
	private boolean endDescendDigs() {
		descendDigging = false;
		descendWorking = null;
		descendStand = null;
		descendColumn = null;
		descendTicks = 0;
		descendWaitTicks = 0;
		descendSettleTicks = 0;
		descendCentreTicks = 0;
		descendCentred = false;
		return false;
	}

	/**
	 * Bám nguyên một block cho tới khi nó vỡ — một tư thế đầu, một mục tiêu.
	 *
	 * <p>Chỉ nhận việc khi breaker ĐANG bổ dở ({@code aiming() != null}). Trong
	 * lúc đó không ai được đổi mục tiêu, nên mắt đứng yên và tiến độ đập của
	 * vanilla không bị reset. Có trần {@link #LOCK_DIG_LIMIT}: block không chịu
	 * vỡ (server bảo kê, sai công cụ) thì nhả ra cho máy chính xoay cách khác.
	 *
	 * @return true khi tick này thuộc về nhát bổ đang dở.
	 */
	private boolean tickLockedDig(World world, ClientPlayerEntity player) {
		BlockPos busy = breaker.aiming();

		// Breaker này DÙNG CHUNG với MoveController (nó nhận chính đối tượng đó
		// trong hàm dựng). Mover arm breaker khi khoan đường / phá trần, và nếu
		// khoá ở đây ôm luôn mấy nhát đó thì mover không được gọi nữa — cờ leo
		// trụ treo cứng. {@code activeTarget} chỉ được đặt trong workTarget của
		// engine, nên nó là dấu "nhát này là của engine".
		if (busy == null || activeTarget == null || !busy.equals(activeTarget)) {
			lockedTicks = 0;
			return false;
		}
		double reach = Math.max(config.reachDistance, player.getBlockInteractionRange());

		if (!needsDigging(world, busy) || !breaker.canReach(busy, reach)) {
			lockedTicks = 0;
			return false; // vỡ rồi hoặc mất tầm — trả về máy chính
		}
		if (++lockedTicks > LOCK_DIG_LIMIT) {
			// HẾT KIÊN NHẪN THẬT SỰ — phải cắt hẳn, không thì tick sau máy chính
			// arm lại đúng block đó và bộ đếm về 0: vòng lặp 60 giây/lần, bot đứng
			// đập mãi một viên (block bị server bảo kê, mine tự hồi sinh, desync).
			// Ghi vào sổ "lì" có hạn để mấy hàm CHỌN mục tiêu tạm né nó ra —
			// KHÔNG đụng vào needsDigging, vì hàm đó còn dùng để kết luận tầng đã
			// sạch; giấu block ở đó là âm thầm bỏ mặt đào (trái luật).
			lockedTicks = 0;
			stubborn.put(busy.toImmutable(), STUBBORN_COOLDOWN);
			breaker.cancel();
			activeTarget = null;
			stuckRounds++;
			message("§eô tại " + busy.toShortString() + " không vỡ sau "
					+ (LOCK_DIG_LIMIT / 20) + "s — tạm để đó, xoay cách khác");
			enterPhase(Phase.REPOSITION);
			return false;
		}
		// Ghì tiến nằm sẵn trong workTarget (một lối đào duy nhất cho cả mod).
		workTarget(busy, reach);
		return true;
	}

	/**
	 * Ghì phím tiến khi block đang bổ nằm ngay phía trước, ngang tầm mắt.
	 *
	 * <p>Đi trong lúc đào không làm mất tiến độ đập: thân ép vào block nên
	 * crosshair vẫn dính nguyên chỗ cũ, tới lúc vỡ thì bước vào luôn. Chỉ ghì
	 * khi thoả cả ba: (1) đang ở lượt đào MẶT (lượt vét thì đứng im một chỗ theo
	 * đúng luật "đứng im vét quanh chỗ đứng"), (2) mục tiêu nằm trong nón 40° phía
	 * trước và ngang tầm — không phải nhát bổ xuống chân, (3) còn cách hơn một
	 * block để không dẫm lên rìa hố vừa mở.
	 */
	private void pressIntoTarget(ClientPlayerEntity player, BlockPos target) {
		// NGUYÊN TRẠNG BẢN CŨ (lệnh user "quay về mấy bản cũ lấy cái di chuyển"):
		// lượt vét đứng im tuyệt đối — cú ghì chỉ dành cho lượt mặt. Thử nghiệm
		// "cụm to trong lượt vét cũng vừa đi vừa đào" đã gỡ: nó là một trong các
		// nguồn bước chân lạ ngoài dãy.
		if (plan == null || plan.areLayerFacesDone() || !player.isOnGround()) {
			return; // input đã stop mặc định đầu tick
		}

		Vec3d eye = player.getEyePos();
		double dx = target.getX() + 0.5 - player.getX();
		double dz = target.getZ() + 0.5 - player.getZ();
		double horizontal = Math.hypot(dx, dz);
		double dy = target.getY() + 0.5 - eye.y;

		if (Math.abs(dy) > WALK_DIG_MAX_RISE) {
			return; // nhát bổ xuống chân / lên trần: đứng yên mà bổ
		}
		// Ô NGANG TẦM là BỨC TƯỜNG, không phải cái hố — ép sát vào nó vô hại, mà
		// còn là cách duy nhất để bước ngay vào khoảng trống lúc nó vỡ. Chỉ ô
		// thấp hơn chân mới cần giữ khoảng cách (đứng sát miệng hố thì rơi).
		boolean wallAhead = target.getY() >= player.getBlockY();

		if (!wallAhead && horizontal < WALK_DIG_MIN_DIST) {
			return;
		}

		// Không bước vào chỗ hụt sàn: kiểm MỌI ô sàn mà thân có thể lấn sang.
		//
		// Cố ý không dùng standable(): hàm đó đòi ô phía trước phải trống, mà ở
		// đây ô phía trước chính là bức tường đang đục — đòi vậy thì không bao
		// giờ ghì tiến được. Cái duy nhất cần biết là có sàn để đặt chân hay
		// không.
		//
		// Kiểm THEO TỪNG THÀNH PHẦN chứ không chỉ ô tròn-hướng: cú ghì đi theo
		// yaw đang giữ nên thân hay trôi CHÉO — bản cũ chỉ soi đúng một ô theo
		// hướng chính, còn ô chéo (sàn vừa bị nhát vét trước khoét mất) thì bước
		// thẳng vào và rơi giữa lượt vét — soi vòng 3 bắt được.
		World world = client.world;

		if (world != null) {
			BlockPos feet = player.getBlockPos();
			int sx = dx > 0.2 ? 1 : dx < -0.2 ? -1 : 0;
			int sz = dz > 0.2 ? 1 : dz < -0.2 ? -1 : 0;
			if ((sx != 0 && !BlockUtil.walkableOn(world, feet.add(sx, -1, 0)))
					|| (sz != 0 && !BlockUtil.walkableOn(world, feet.add(0, -1, sz)))
					|| (sx != 0 && sz != 0 && !BlockUtil.walkableOn(world, feet.add(sx, -1, sz)))) {
				return;
			}
		}

		double yaw = Math.toRadians(player.getYaw());
		double sin = Math.sin(yaw);
		double cos = Math.cos(yaw);
		double forward = dz * cos - dx * sin;
		double strafe = dz * sin + dx * cos;

		// ĐI NGANG CHỈ DÀNH CHO ĐOẠN CUỐI, không dùng khi đang chạy dọc dãy.
		//
		// Cuối dãy hay gặp một BỜ TƯỜNG PHẲNG: mặt 9 ô kế tiếp nằm dịch sang bên
		// dọc theo vách chứ không nằm thẳng trước mũi — lúc đó phải trượt ngang
		// mới ăn hết được vách. Giữa dãy thì mục tiêu LUÔN là tâm nằm thẳng trước
		// mũi (faceWorkCell chỉ trả về tâm), thành phần ngang ~0 nên nhánh này
		// không bao giờ chạm tới. (Nguyên trạng bản cũ — chỉ an toàn khi giữ đúng
		// luật chỉ-nhắm-tâm ở faceWorkCell.)
		boolean alongWall = Math.abs(strafe) > Math.abs(forward);

		// Còn xa thì chạy cho kịp — bằng đúng tốc độ lúc đi lại bình thường.
		// Sát mặt đá thì thôi sprint: chạy vào tường chỉ tổ nảy ra và lệch hàng.
		boolean sprint = config.allowSprint && horizontal > WALK_DIG_SPRINT_DIST;

		input.set(pressAxis(forward), alongWall ? pressAxis(strafe) : 0.0F, false, sprint);
	}

	/**
	 * Trượt thân về ĐÚNG ĐƯỜNG TÂM của mặt 9 ô khi chỉ còn lệch chút xíu.
	 *
	 * <p>Chỉ bấm theo TRỤC NGANG (trục vuông góc hướng chạy), nên khoảng lùi
	 * {@link #FACE_STANDOFF} giữ nguyên — không có chuyện vừa canh tâm vừa trôi
	 * vào trong khối 9 ô rồi nhảy loi choi tại chỗ như bản cũ.
	 *
	 * @return true nếu tick này dùng để canh tâm, tức là CHƯA được phép bổ.
	 */
	private boolean centreOnFaceLine(ClientPlayerEntity player, BlockPos centre) {
		if (!player.isOnGround()) {
			return false; // đang rơi/nhảy: bấm phím lúc này chỉ trôi lung tung
		}
		double cross = plan.travelAxis == Direction.Axis.X
				? player.getZ() - (centre.getZ() + 0.5)
				: player.getX() - (centre.getX() + 0.5);
		double off = Math.abs(cross);

		if (off <= FACE_ALIGN_TOLERANCE || off > CENTRE_NUDGE_RANGE) {
			return false; // đã đúng tâm, hoặc lệch xa quá — để mover đi hẳn
		}
		// Hướng cần trượt, theo hệ toạ độ thế giới: ngược chiều với độ lệch.
		double wantX = plan.travelAxis == Direction.Axis.X ? 0.0 : -cross;
		double wantZ = plan.travelAxis == Direction.Axis.X ? -cross : 0.0;

		double yaw = Math.toRadians(player.getYaw());
		double sin = Math.sin(yaw);
		double cos = Math.cos(yaw);
		double forward = wantZ * cos - wantX * sin;
		double strafe = wantZ * sin + wantX * cos;

		input.set(nudgeAxis(forward), nudgeAxis(strafe), false, false);
		note = "canh đúng tâm 9 ô (lệch " + String.format("%.2f", off) + ")";
		return true;
	}

	// (centreOnCellLine đã gỡ cùng sweepCentred 2026-08-20 — lượt vét giờ đứng
	// nguyên một chỗ bổ mọi góc, không nhích canh tâm từng ô nữa.)

	/** Như {@link #pressAxis} nhưng vùng chết hẹp, dùng riêng cho lúc canh tâm. */
	private static float nudgeAxis(double offset) {
		if (Math.abs(offset) < CENTRE_NUDGE_DEADZONE) {
			return 0.0F;
		}
		return offset > 0 ? 1.0F : -1.0F;
	}

	/** Bấm phím theo một trục, có vùng chết để khỏi rung lắc quanh tâm. */
	private static float pressAxis(double offset) {
		if (Math.abs(offset) < WALK_DIG_DEADZONE) {
			return 0.0F;
		}
		return offset > 0 ? 1.0F : -1.0F;
	}

	/**
	 * Không với tới đích thì ĐÀO CÁI ĐANG CHẮN — kể cả phải cúi đầu.
	 *
	 * <p>Bắn một tia từ mắt tới tâm ô cần đào; block đầu tiên chặn tia chính là
	 * thứ phải dọn. Đây là câu trả lời cho "khi không thấy đào thì tự cúi đầu
	 * xuống đào hoặc di chuyển tới đó": luật nhìn-ngang chỉ áp cho việc CHỌN mặt
	 * đào, còn khi đã kẹt thì dọn chướng ngại là việc bắt buộc, góc nào cũng bổ.
	 *
	 * @return true nếu tick này đã dùng để bổ chướng ngại.
	 */
	private boolean digObstacleToward(World world, ClientPlayerEntity player, BlockPos target, double reach) {
		Vec3d eye = player.getEyePos();
		BlockHitResult hit = world.raycast(new RaycastContext(eye, Vec3d.ofCenter(target),
				RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, player));

		if (hit.getType() != HitResult.Type.BLOCK || hit.getBlockPos().equals(target)) {
			return false; // không có gì chắn — kẹt vì lý do khác (quá xa chẳng hạn)
		}

		BlockPos obstacle = hit.getBlockPos();

		if (!selection.contains(obstacle, OBSTACLE_DIG_MARGIN)
				|| !BlockUtil.isBreakable(world, obstacle)
				|| !breaker.canReach(obstacle, reach)
				|| loadBearing(obstacle)) {
			// loadBearing: tia hay chạm chính viên dưới chân khi đích nằm thấp
			// hơn — đào nó là tự thả mình rơi rồi lại phải kê lên.
			return false;
		}

		input.stop();
		workTarget(obstacle, reach); // cùng một lối đào với mặt 9 ô
		note = "dọn chướng ngại để tới ô đào";
		return true;
	}

	/**
	 * Nạp ba tâm mặt liên tiếp theo hướng chạy của dãy vào hàng đợi "bổ thẳng".
	 *
	 * <p>Đúng ba block của ba đường thẳng kế nhau — không phải ba lần "con trỏ
	 * đổi mặt". Nhờ vậy mặt nào vốn đã trống thì bỏ qua ngay mà không tính là
	 * một nhát, còn mặt nào còn đá thì phải bổ cho vỡ mới được đi tiếp.
	 */
	private void armStraightDigs(BlockPos firstCentre) {
		straightQueue.clear();
		straightTicks = 0;
		straightSwings = 0;
		straightWorking = null;
		straightStance = null;

		ClientPlayerEntity player = client.player;
		int midY = plan.faceCenter().getY();

		// SANG DÃY = CẮT NGANG. Đào hết một dãy thì thân đang ở tít đầu kia, lệch
		// sang bên đúng bằng bề rộng dãy (3 ô). Ba nhát bổ NGANG từ chỗ đứng sẽ
		// mở đúng đoạn hầm 3 ô dẫn vào đường tâm của dãy mới; xong ba nhát thì
		// thân đã ở đúng hàng, chỉ việc quay người theo hướng dãy mới và chạy
		// tiếp — đúng thứ user mô tả: "đào 3 lần rồi mới quay người trả về đúng
		// hướng và tiếp tục đào".
		if (player != null) {
			boolean axisX = plan.travelAxis == Direction.Axis.X;
			int travelNow = axisX ? player.getBlockX() : player.getBlockZ();
			int crossNow = axisX ? player.getBlockZ() : player.getBlockX();
			int crossStep = Integer.signum(plan.aimCross() - crossNow);

			if (crossStep != 0) {
				// Nạp RỘNG chứ không đúng ba ô: ô đầu tiên bên cạnh thường đã bị
				// mặt cuối của dãy CŨ ăn mất (mặt 3x3 phủ ±1 quanh tâm dãy cũ),
				// nên nạp đúng ba ô là mất ngay một nhát — "3 nhát 27 ô" ra thành
				// "2 nhát 18 ô". Bộ đếm {@link #straightSwings} mới là thứ chốt
				// đủ ba NHÁT THẬT; hàng đợi chỉ là danh sách ứng viên.
				for (int i = 1; i <= STRAIGHT_SCAN_DEPTH; i++) {
					BlockPos cell = plan.posAt(travelNow, crossNow + i * crossStep, midY);

					if (selection.contains(cell)) {
						straightQueue.add(cell.toImmutable());
					}
				}
				return;
			}
		}

		// Đã đứng sẵn trên đường tâm của dãy (dãy đầu tiên sau /start chẳng hạn):
		// nhát chạy THẲNG theo hướng dãy để mở miệng hầm.
		Direction dir = plan.travelAxis == Direction.Axis.X
				? (plan.rowDirection() > 0 ? Direction.EAST : Direction.WEST)
				: (plan.rowDirection() > 0 ? Direction.SOUTH : Direction.NORTH);

		for (int i = 0; i < STRAIGHT_SCAN_DEPTH; i++) {
			BlockPos cell = firstCentre.offset(dir, i);

			if (selection.contains(cell)) {
				straightQueue.add(cell.toImmutable());
			}
		}
	}

	/**
	 * Bổ cho hết ba nhát thẳng hàng của đầu dãy — đúng ba block của ba đường,
	 * không nhát nào bị bỏ dở.
	 *
	 * @return true khi tick này thuộc về việc đó (máy chính không được chạy).
	 */
	private boolean tickStraightDigs(World world, ClientPlayerEntity player) {
		// Ô nào đã trống thì bỏ khỏi hàng đợi — kể cả khi nhát trước ăn lan sang.
		// Chỉ ô mà TA ĐANG BỔ rồi mới vỡ mới được tính là một nhát; ô vốn đã
		// trống sẵn (mặt cuối dãy cũ ăn mất) chỉ bị loại, không tính.
		while (!straightQueue.isEmpty() && !needsDigging(world, straightQueue.peek())) {
			BlockPos finished = straightQueue.poll();

			if (finished.equals(straightWorking)) {
				straightSwings++;
				straightWorking = null;
			}
			straightTicks = 0;
		}
		if (straightSwings >= STRAIGHT_DIGS_PER_ROW) {
			straightQueue.clear(); // đủ ba nhát thật — trả quyền cho máy chính
			straightStance = null;
			straightWorking = null;
			return false;
		}
		if (straightQueue.isEmpty()) {
			return false;
		}
		// Lưới an toàn: ba nhát này chạy ngoài watchdog của máy chính nên tự đặt
		// giờ — quá lâu thì nhả ra cho máy chính xoay cách khác (nó không bỏ mặt
		// đào), khỏi đứng hình. straightTicks tăng ở tick() chứ không tăng ở đây:
		// tickLockedDig chiếm tick khi đang bổ dở, đếm trong này là bị bỏ đói.
		if (straightTicks > STRAIGHT_DIG_LIMIT) {
			// KHÔNG đụng trạng thái tụt tầng ở đây: đó là việc của máy kia. Một máy
			// con dọn trạng thái của máy con khác là mầm bug lặng lẽ về sau.
			straightQueue.clear();
			straightStance = null;
			message("§ebổ thẳng đầu dãy quá lâu — trả về lộ trình thường");
			return false;
		}

		BlockPos target = straightQueue.peek();

		// Khoá 60 giây vừa tuyên bố ô này "lì" (server bảo kê)? Nhả về máy chính
		// ngay thay vì arm lại đúng nó khi khoá vừa buông — cùng vòng vô hạn đã
		// bịt ở lượt tụt tầng.
		if (stubborn.containsKey(target)) {
			straightQueue.clear();
			straightStance = null;
			straightWorking = null;
			message("§eô bổ thẳng không vỡ — trả về lộ trình thường");
			return false;
		}


		double reach = Math.max(config.reachDistance, player.getBlockInteractionRange());
		int done = straightSwings + 1;

		if (breaker.canReach(target, reach)) {
			straightWorking = target; // ô này ta ĐANG bổ — vỡ thì tính một nhát
			workTarget(target, reach);
			note = "bổ thẳng " + done + "/" + STRAIGHT_DIGS_PER_ROW;
			return true;
		}

		// Chưa với tới: DỌN CHƯỚNG NGẠI trước (cúi đầu cũng được), rồi mới tính
		// tới chuyện đi — đứng im chờ là thứ tuyệt đối không được phép.
		if (digObstacleToward(world, player, target, reach)) {
			return true;
		}

		BlockPos stand = standNear(world, player, target);

		if (stand == null) {
			stand = new BlockPos(target.getX(), plan.layerBottom(), target.getZ());
		}
		note = "tới chỗ bổ nhát " + done + "/" + STRAIGHT_DIGS_PER_ROW;

		// Chỉ reset mover khi ĐỔI chỗ đứng. Reset mỗi tick là xoá lộ trình vừa
		// tính rồi tính lại từ đầu — thân đứng nguyên tại chỗ cho tới lúc hết
		// giờ 30 giây của nhát này.
		if (!stand.equals(straightStance)) {
			straightStance = stand.toImmutable();
			mover.reset();
		}

		if (mover.moveTo(stand) == MoveController.Result.BLOCKED) {

			// Chỗ đứng này không tới được: đánh dấu đã thử để tick sau standNear
			// chọn chỗ khác, thay vì xoá sổ rồi chọn lại đúng nó.
			triedStances.add(stand.toImmutable());
			straightStance = null;
			mover.reset();
		}
		return true;
	}

	/**
	 * Walk to the spot this face is dug from. Never gives up on the face.
	 *
	 * <p><b>Đào thẳng vào tâm ngay khi đã đứng đúng hàng</b> — không bắt đi cho
	 * bằng hết tới ô đứng chuẩn. Hai luật của user gộp lại thành đúng một điều
	 * kiện ba vế:
	 *
	 * <ul>
	 *   <li>{@code canReach} — với tới tâm;</li>
	 *   <li>{@link #faceAimLevel} — nhìn NGANG, không cúi (cấm "cúi đầu đào");</li>
	 *   <li>{@link #faceAligned} — thân nằm đúng trên đường tâm của mặt, nên mặt
	 *       phẳng 3x3 của cúp trùng với 9 ô cần đào chứ không lệch sang cột khác.</li>
	 * </ul>
	 *
	 * <p>Bản trước đó thiếu vế thứ ba nên đứng lệch vẫn bổ, ăn nhầm 9 ô của cột
	 * khác. Bản sau lại bỏ hẳn đường tắt, bắt phải ARRIVED mới được đào — và đó
	 * là cái làm hỏng: những mặt mà ô đứng chuẩn nằm sau tường/ngoài rìa thì đi
	 * mãi không tới, hết 8 giây bị bỏ qua ("kẹt 8s — bỏ qua"), còn trong lúc lết
	 * tới thì cứ đào-cúi-đào-cúi. Giữ cả ba vế là vừa thẳng tâm, vừa không cúi,
	 * vừa không phải đi vòng.
	 */
	private void approachFace(World world, BlockPos centre) {
		ClientPlayerEntity player = client.player;

		// ĐỨNG DƯỚI TẦNG MÀ CÒN VỚI TỚI THÌ ĐÀO TRƯỚC, LEO SAU (xem edgeDigging).
		//
		// Tầm dùng là tầm server (dài nhất có thể) — "với tới trước mặt đào hết
		// cỡ" đúng nghĩa đen. Điều kiện đứng-trên-đất + không-đang-leo giữ cho
		// nhánh này không cướp tick giữa cú nhảy của trụ: đỉnh cú nhảy tầm với
		// hay thoáng chạm tới tâm, cướp lúc đó là giết cú đặt block đang dở.
		// Mỗi mặt vỡ xong con trỏ tự sang mặt kế, vòng lại đây, lại với thử —
		// hết với nổi thì rơi xuống lối thường bên dưới (đi/leo), tức là chỉ
		// "bắc block lên" khi thật sự không còn gì đào được từ chỗ đứng.
		if (player != null && player.isOnGround() && !mover.isClimbing()
				&& player.getBlockY() < plan.layerBottom()
				&& breaker.canReach(centre,
						Math.max(config.reachDistance, player.getBlockInteractionRange()))) {
			input.stop();
			edgeDigging = true;
			enterPhase(Phase.AIM_LOCK);
			return;
		}

		if (player != null && !mover.isClimbing()
				&& breaker.canReach(centre, config.reachDistance)
				&& faceAligned(player, centre)
				&& (frontDigsLeft > 0 || faceAimLevel(player, centre))) {
			input.stop();
			enterPhase(Phase.AIM_LOCK);
			return;
		}

		// CÒN LỆCH MỘT CHÚT THÌ TRƯỢT VỀ TÂM, đừng đi lại từ đầu.
		//
		// Ngưỡng thẳng hàng đã siết còn 0.22 block cho đúng "100% đúng tâm giữa
		// 9 ô mới đào"; thiếu bước trượt này thì lệch 0.3 cũng phải chạy nguyên
		// lộ trình mới, vừa chậm vừa dễ đứng hình.
		if (player != null && !mover.isClimbing()
				&& breaker.canReach(centre, config.reachDistance)
				&& Math.abs(alongOffset(player, centre)) >= FACE_STANDOFF
				&& (frontDigsLeft > 0 || faceAimLevel(player, centre))
				&& centreOnFaceLine(player, centre)) {
			activeTarget = null;
			return;
		}


		BlockPos goal = useEntryStance ? plan.entryPos() : plan.standPos();
		MoveController.Result result = mover.moveTo(goal);
		if (result == MoveController.Result.MOVING) {
			note = mover.isClimbing() ? "đang leo lên"
					: useEntryStance ? "đào giếng vào mặt" : "tới chỗ đứng";
			activeTarget = null;
			// Do NOT touch the breaker here. The mover owns it while moving: its
			// walking branches silence it themselves, and its boring/ceiling
			// branches ARM it. The blanket cancel that used to sit here wiped the
			// bore's vanilla progress every single tick, so an approach could
			// never dig through a wall at all — the true root of every "9 ô ở xa
			// mà không tự đào tới" report ("phá trực tiếp và đi tới 9 ô luôn").
			return;
		}
		if (result == MoveController.Result.BLOCKED) {
			// Đi không được thì ĐÀO CÁI ĐANG CHẮN giữa mắt và tâm mặt — cúi đầu
			// cũng bổ. Đây là chỗ mà trước đây bot chỉ biết đứng nhìn khối 9 ô ở
			// xa rồi thôi ("nó không thèm đi tới, cứ đứng im").
			if (player != null && digObstacleToward(world, player, centre,
					Math.max(config.reachDistance, player.getBlockInteractionRange()))) {
				return;
			}
			if (frontDigsLeft == 0 && !useEntryStance) {
				// Wedged (typically a row start, standing at the old row's end):
				// switch the GOAL to the face's own column and charge it — the
				// bore cuts a chest-high line straight at the 3x3 and we dig the
				// face only after ARRIVING at that column, so the swing still
				// leaves from the middle of the cell. Arming the counter without
				// changing the goal did nothing: the next tick just replayed the
				// same blocked walk.
				frontDigsLeft = 3;
				useEntryStance = true;
				mover.reset();
				note = "đào 9 ô trước mặt";
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
		frontDigsLeft = 0; // properly back on the planned route
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
		// Tầm server, không phải 4.5 cứng: cùng con số mà nhánh chọn (edge-dig,
		// locked-dig, sweep) đã dùng — ngắm bằng tầm ngắn hơn tầm chọn là ô vừa
		// được chọn xong lại "không thấy", đúng họ bug lệch-tầm đã vá ở digStep.
		ClientPlayerEntity player = client.player;
		double reach = player != null
				? Math.max(config.reachDistance, player.getBlockInteractionRange())
				: config.reachDistance;

		if (!breaker.aimOnly(cell, reach)) {
			// No line to it from here. Walk to a spot that can see it — moving is the
			// natural answer to "can't see it", and it keeps the view level. Building was
			// tried here before and was wrong twice over: it aims at the floor, which
			// dipped the head after every swing, and when it ran out of cells the engine
			// simply stood still forever ("Kẹt: chưa ngắm được tâm").
			enterPhase(Phase.REPOSITION);
			return;
		}
		// CỬA CUỐI TRƯỚC KHI BỔ: 100% đúng tâm mới cho xuống DIG_LOCKED.
		//
		// Không thể tin mỗi bước "mover báo ARRIVED": mover chốt ARRIVED trong
		// 0.12 block nhưng GIỮ nguyên trạng thái đó tới 0.45, và hết giờ canh
		// giữa thì nó báo ARRIVED kể cả khi còn lệch. Lệch chừng ấy là mặt phẳng
		// 3x3 xẻ sang cột bên, chừa lại đúng mấy ô vừa định dọn — nên chỗ này tự
		// nhích lấy, và chỉ nhích (không bổ) cho tới khi thật sự thẳng tâm.
		//
		// TRỪ lúc đang với từ dưới mép ({@link #edgeDigging}): luật là "đứng im
		// mà đào", nhích ngang ở mép hố vừa trái luật vừa dễ trượt chân — với
		// qua hố thì không có tư thế thẳng tâm nào để mà canh.
		if (player != null && !edgeDigging
				&& !faceAligned(player, cell) && centreOnFaceLine(player, cell)) {
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
				// KHÔNG BỎ MẶT ĐÀO (luật 2). Không chỗ đứng nào thấy tâm nghĩa là
				// phải TỰ MỞ đường vào cột của mặt — cột đó luôn nằm trong vùng
				// nên luôn đào tới được. Đây là nấc 1 của watchdog, dùng lại y
				// nguyên thay vì đẩy con trỏ sang mặt khác (đường bỏ mặt cuối
				// cùng còn sót lại trong file).
				note = "không có chỗ đứng nào thấy tâm — tự khoan vào cột tâm";
				triedStances.clear();
				frontDigsLeft = 3;
				useEntryStance = true;
				mover.reset();
				enterPhase(Phase.APPROACH);
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
		if (!crosshairOn(cell)) {
			breaker.disarm();
			activeTarget = null;
			enterPhase(Phase.AIM_LOCK);
			return;
		}
		// Chính hàm dùng chung — mặt 9 ô và mọi kiểu đào khác đi qua đúng một
		// đường: khoá mục tiêu, bổ, và ghì tiến theo.
		workTarget(cell, Math.max(config.reachDistance,
				client.player != null ? client.player.getBlockInteractionRange() : config.reachDistance));

		if (activeTarget == null) {
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
	 * Is this face worth stopping at? Chỉ khi TÂM còn nguyên.
	 *
	 * <p>Tâm trống là mặt coi như xong, phần sót giao lượt vét (nó tự do tiếp
	 * cận mọi góc). Đã thử cho lượt mặt ăn cả mặt-thủng-tâm để "đào cho sát" —
	 * và phải gỡ ra theo lệnh user: nhắm ô ngoài tâm là gốc của mọi cú đảo
	 * đầu/đi xiên, đường đi thẳng tắp quan trọng hơn.
	 *
	 * <p>Vẫn KHÔNG kê block vá tâm như bản cổ đại: vá là phải cúi nhìn sàn, đúng
	 * cái gật đầu sau mỗi nhát mà user cấm.
	 */
	private boolean faceHasWork(World world) {
		if (plan.areLayerFacesDone()) {
			return false;
		}
		if (faceWorkCell(world) == null) {
			return false; // every cell of it is already gone (or not ours to dig)
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

		// Ô đang trong sổ "lì" thì tạm coi như chưa tới lượt — hết hạn né là
		// quay lại đào bình thường, mặt đào KHÔNG bị bỏ.
		// CỐ Ý không lọc qua diggableTarget ở đây: mặt đào là việc BẮT BUỘC phải
		// làm, giấu nó đi một tick là con trỏ kế hoạch chạy tiếp và mặt bị bỏ
		// hẳn ("kẹt thì phải đi tới đó hoặc đào tới đó"). Luật đứng lùi
		// FACE_STANDOFF vốn đã không cho phép đứng trên chính tâm mà bổ.
		// CHỈ TÂM, KHÔNG BAO GIỜ NHẮM Ô KHÁC — khôi phục nguyên trạng bản cũ
		// theo lệnh chốt của user ("quay về mấy bản cũ lấy cái di chuyển là
		// được"). Nhánh "mặt thủng tâm thì nhắm ô cạnh bên" từng đứng ở đây
		// chính là gốc của cả chuỗi lỗi di chuyển: nhắm ô lệch là đầu đảo, đầu
		// đảo là cú ghì tiến kéo thân đi xiên, và ba bản vá chồng lên nhau vẫn
		// không sạch. Tâm trống thì mặt coi như xong, phần sót giao lượt vét —
		// đổi lấy đường đi thẳng tắp một mạch, đúng ưu tiên user chọn.
		return needsDigging(world, centre) && !stubborn.containsKey(centre) ? centre : null;
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
	 * Đánh dấu cả khối 3x3x3 quanh tâm {@code centre} là ĐÃ XONG.
	 *
	 * <p>Lấy nguyên khối 3x3x3 chứ không chỉ 9 ô của mặt: một nhát cúp 3x3 ăn
	 * mặt phẳng vuông góc hướng nhìn, mà hướng nhìn có thể lệch một chút, nên ô
	 * bị ăn trải ra cả khối. Ghi rộng ra thì lượt vét không mò lại vùng vừa đào.
	 */
	private void markFaceDone(BlockPos centre) {
		World world = client.world;

		if (world == null) {
			return;
		}
		for (int dx = -1; dx <= 1; dx++) {
			for (int dy = -1; dy <= 1; dy++) {
				for (int dz = -1; dz <= 1; dz++) {
					BlockPos cell = centre.add(dx, dy, dz);

					// CHỈ ghi những ô đã THẬT SỰ trống. Ô nào nhát 3x3 chưa ăn
					// (server trả AoE trễ, hoặc block cứng khác loại) vẫn để
					// nguyên cho lượt vét — nếu ghi bừa cả khối thì đúng cái
					// user ghét: block còn đứng trơ mà không ai dọn.
					if (world.getBlockState(cell).isAir()) {
						doneCells.add(cell);
					}
				}
			}
		}
	}

	private boolean needsDigging(World world, BlockPos pos) {
		// The 3x3 slice around an aim point can reach past the rim, so cells are no
		// longer guaranteed to be inside the box — check before treating one as ours.
		if (!selection.contains(pos) || givenUp.contains(pos) || world.getBlockState(pos).isAir()) {
			return false;
		}
		// Ô nằm trong mặt 3x3 ĐÃ ĐÀO XONG thì thôi, không quay lại đào nữa —
		// trừ SỎI/CÁT rơi xuống lấp vào, cái đó thì phải dọn (luật user: "đào
		// chỗ đó 9 block xong thì không lặp lại, trừ khi sỏi rơi xuống").
		if (doneCells.contains(pos)
				&& !(world.getBlockState(pos).getBlock() instanceof FallingBlock)) {
			return false;
		}
		if (!BlockUtil.isBreakable(world, pos)) {
			return false; // bedrock and friends
		}
		return true;
	}

	/**
	 * Ô này đang GÁNH THÂN MÌNH — cấm chọn nó làm mục tiêu đào.
	 *
	 * <p>Hai trường hợp, đều là tự rút thảm dưới chân mình:
	 *
	 * <ul>
	 *   <li>ô ngay dưới chân: đào là rơi, rơi là "lọt dưới tầng", rồi kê block leo
	 *       lên, lên xong lại thấy nó "còn sót" nên đào tiếp — vòng lặp bất tận;</li>
	 *   <li>viên MÌNH VỪA KÊ ra để bước qua chỗ hụt sàn (hoặc để leo lên) mà còn
	 *       đang ở ngay sát dưới chân: đào nó ra là hụt sàn trở lại, kê tiếp, đào
	 *       tiếp — đúng cái user báo "tự đặt block xong lại đào block đó".</li>
	 * </ul>
	 *
	 * <p>Chỉ chừa lúc nó còn gánh mình: đi khỏi rồi thì lượt vét dọn nó bình
	 * thường, nên vùng vẫn được đào sạch. Cố ý KHÔNG nhét vào
	 * {@link #needsDigging}: hàm đó còn dùng để kết luận "tầng đã sạch", giấu
	 * block ở đó là âm thầm bỏ sót.
	 *
	 * <p>Nhát bổ xuống chân lúc TỤT TẦNG không đi qua đây (nó dùng
	 * {@link #tickDescendDigs} riêng), nên luật này không cản việc xuống tầng.
	 */
	private boolean loadBearing(BlockPos pos) {
		ClientPlayerEntity player = client.player;
		if (player == null) {
			return false;
		}
		BlockPos feet = player.getBlockPos();
		if (pos.equals(feet.down())) {
			return true;
		}
		return pos.getY() < feet.getY()
				&& pos.getSquaredDistance(feet) <= SCAFFOLD_KEEP_RADIUS_SQ
				&& mover.placedCells().contains(pos);
	}

	/** Ô đáng đào VÀ không phải cái đang đỡ chân mình. */
	private boolean diggableTarget(World world, BlockPos pos) {
		return needsDigging(world, pos) && !loadBearing(pos);
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
