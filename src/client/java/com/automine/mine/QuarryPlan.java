package com.automine.mine;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * Works out the order the box gets dug in, <b>one layer at a time</b>.
 *
 * <p>The box is sliced into layers {@code layerHeight} tall, taken <b>top
 * down</b>. Within a layer the player walks the box's <b>longer horizontal
 * axis</b> (chosen automatically, fewest turns) clearing a face
 * {@code passWidth} wide by {@code layerHeight} tall at each step — 3x3 = 9
 * cells by default. Rows run in alternating directions so the player never
 * walks back down a row it has already cleared.
 *
 * <p>A layer is covered in two passes: full faces first, then the clipped
 * slivers along the box edges (which are awkward to stand in front of until the
 * rock around them is gone). The cursor <b>never moves to the next layer by
 * itself</b> — the engine calls {@link #nextLayer()} only after it has verified
 * the current layer is completely empty.
 */
public final class QuarryPlan {

	private final Selection sel;
	private final int layerHeight;
	private final int passWidth;

	/**
	 * The box corners as they were when this plan was built. Needed because
	 * {@link #sel} is the mod's single live Selection object: comparing against it
	 * always "matches", even after the user marks a completely different box —
	 * which made /start resume a stale plan over the new marks.
	 */
	private final int selMinX, selMinY, selMinZ, selMaxX, selMaxY, selMaxZ;

	/** The horizontal axis the player travels along. */
	public final Direction.Axis travelAxis;

	/**
	 * Dig the box from the opposite corner: rows start at the far side of the
	 * cross axis and travel runs the other way. Chosen when another account is
	 * already working the default end, so the two meet in the middle instead of
	 * fighting over the same faces.
	 */
	public final boolean mirrored;

	private final int minT, maxT, minC, maxC, minY, maxY;
	private final int layerCount, rowCount, stepCount;

	private int layer, row, step;
	/** 0 = full faces only, 1 = the leftover slivers at the box edges. */
	private int pass;
	private boolean layerFacesDone;
	private boolean done;

	public QuarryPlan(Selection sel, int layerHeight, int passWidth) {
		this(sel, layerHeight, passWidth, false);
	}

	public QuarryPlan(Selection sel, int layerHeight, int passWidth, boolean mirrored) {
		this.sel = sel;
		this.layerHeight = Math.max(1, layerHeight);
		this.passWidth = Math.max(1, passWidth);
		this.mirrored = mirrored;
		this.selMinX = sel.minX();
		this.selMinY = sel.minY();
		this.selMinZ = sel.minZ();
		this.selMaxX = sel.maxX();
		this.selMaxY = sel.maxY();
		this.selMaxZ = sel.maxZ();

		this.travelAxis = sel.sizeX() >= sel.sizeZ() ? Direction.Axis.X : Direction.Axis.Z;
		if (travelAxis == Direction.Axis.X) {
			minT = sel.minX(); maxT = sel.maxX();
			minC = sel.minZ(); maxC = sel.maxZ();
		} else {
			minT = sel.minZ(); maxT = sel.maxZ();
			minC = sel.minX(); maxC = sel.maxX();
		}
		minY = sel.minY();
		maxY = sel.maxY();

		this.layerCount = ceilDiv(maxY - minY + 1, this.layerHeight);
		this.rowCount = ceilDiv(maxC - minC + 1, this.passWidth);
		this.stepCount = maxT - minT + 1;

		if (!isFullFace()) {
			advance(); // the very first face may already be a clipped edge
		}
	}

	private static int ceilDiv(int a, int b) {
		return (a + b - 1) / b;
	}

	// ---- geometry ----

	/** Top Y of the current layer (dug first). */
	public int layerTop() {
		return maxY - layer * layerHeight;
	}

	/** Floor of the current layer — the player stands here. */
	public int layerBottom() {
		return Math.max(minY, layerTop() - (layerHeight - 1));
	}

	/** Odd layers walk their rows in reverse, so a new layer starts under where the last ended. */
	private int rowSlot() {
		boolean forward = (layer % 2 == 0) != mirrored;
		return forward ? row : (rowCount - 1 - row);
	}

	public int rowCenter() {
		return Math.min(maxC, minC + passWidth / 2 + rowSlot() * passWidth);
	}

	private int rowLow() {
		return Math.max(minC, rowCenter() - passWidth / 2);
	}

	private int rowHigh() {
		return Math.min(maxC, rowLow() + passWidth - 1);
	}

	/** +1 or -1, flipping on every row and across layer boundaries too. */
	public int rowDirection() {
		int forward = ((layer * rowCount + row) % 2 == 0) ? 1 : -1;
		return mirrored ? -forward : forward;
	}

	public int currentTravel() {
		return rowDirection() > 0 ? minT + step : maxT - step;
	}

	public int faceWidth() {
		return rowHigh() - rowLow() + 1;
	}

	public int faceHeight() {
		return layerTop() - layerBottom() + 1;
	}

	public int cellsInFace() {
		return faceWidth() * faceHeight();
	}

	public boolean isFullFace() {
		return faceWidth() == passWidth && faceHeight() == layerHeight;
	}

	// ---- the face being worked on ----

	/**
	 * The nine cells one swing will take: the 3x3 slice centred on
	 * {@link #faceCenter()}, in the plane of the face (so it spans the cross axis
	 * and Y, never along the direction of travel). This is what the renderer draws
	 * in red, and it is the truth about what is about to break — not a wish list.
	 *
	 * <p>Ordered by <b>ring distance from the middle</b>: the centre, then the four
	 * cells sharing an edge with it, then the four diagonals. Callers walk this list
	 * when the centre is missing, and the old corner-first order sent the view up to
	 * a diagonal before it came back to the middle — the head-swing at {@code /start}.
	 */
	public List<BlockPos> faceCells() {
		BlockPos center = faceCenter();
		int t = currentTravel();
		int c = travelAxis == Direction.Axis.X ? center.getZ() : center.getX();
		int y = center.getY();

		List<BlockPos> cells = new ArrayList<>(9);
		cells.add(posAt(t, c, y));
		// Edge-adjacent before diagonals: nearest the middle wins.
		cells.add(posAt(t, c, y + 1));
		cells.add(posAt(t, c, y - 1));
		cells.add(posAt(t, c - 1, y));
		cells.add(posAt(t, c + 1, y));
		cells.add(posAt(t, c - 1, y + 1));
		cells.add(posAt(t, c + 1, y + 1));
		cells.add(posAt(t, c - 1, y - 1));
		cells.add(posAt(t, c + 1, y - 1));
		return cells;
	}

	/**
	 * The cell to swing at: the middle of the face, pulled one block inside the box
	 * on the two axes the slice spans.
	 *
	 * <p>The pickaxe breaks a 3x3 slice centred on whatever it hits, so an aim
	 * point on the rim would take a row of blocks <b>outside</b> the marked box with
	 * it — that is the old "đào lố xuống 1 block". Keeping the aim one in on the
	 * cross axis and on Y means the slice lands inside. The travel axis is
	 * deliberately <b>not</b> clamped: the slice doesn't span it, and moving the aim
	 * along it would point at a different face than the one being worked, which
	 * would silently skip faces at either end of a row. On a box thinner than the
	 * tool no safe cell exists, so we aim at the middle and accept the spill.
	 */
	public BlockPos faceCenter() {
		return posAt(
				currentTravel(),
				clampInside(rowCenter(), minC, maxC),
				clampInside(centerY(), minY, maxY));
	}

	/** Pull {@code v} one block inside [min, max], unless that range is too thin to allow it. */
	private static int clampInside(int v, int min, int max) {
		if (max - min < 2) {
			return v; // thinner than the 3x3 slice — no safe cell exists
		}
		return Math.max(min + 1, Math.min(max - 1, v));
	}

	/**
	 * Middle Y of the current layer. Written as an offset from the floor because
	 * {@code (top + bottom) / 2} truncates toward zero, which lands a row too high
	 * below y=0.
	 */
	private int centerY() {
		return layerBottom() + (layerTop() - layerBottom()) / 2;
	}

	/**
	 * Where the player should stand to reach the current face: <b>always one block back
	 * along the travel axis</b>, facing the 3x3 head-on.
	 *
	 * <p>The step-0 case used to return the face's own travel coordinate, which put the
	 * player <em>inside</em> the very 3x3 being worked. From in there the middle cell is
	 * the block at your own feet: it cannot be aimed at, and Minecraft refuses to place
	 * anything into the cell a player occupies — so a missing middle could never be
	 * patched and the face stalled. That is the "lao vào tâm giữa sao đặt được" the user
	 * described. Standing one back leaves the whole face in front of the eyes, close
	 * enough to reach the middle and with room to place into it.
	 *
	 * <p>Note this can sit one block <em>outside</em> the selection at the start of a
	 * row. That is intentional and harmless: standing outside the box is how you dig
	 * into it, and breaking is still restricted to the box by {@code digStep} and
	 * {@code needsDigging}.
	 */
	public BlockPos standPos() {
		return posAt(currentTravel() - rowDirection(), rowCenter(), layerBottom());
	}

	/**
	 * The face's own centre column at floor level — the fallback stance.
	 *
	 * <p>At a row start {@link #standPos} sits <b>outside</b> the box, and while
	 * the row is still buried (digging in from the surface, or a fresh layer at
	 * depth) that cell is under rock the mover is forbidden to break. This was the
	 * failure that made a surface-marked box skip <em>every</em> face of the layer
	 * and fall back to sweeping it from above, one block at a time, looking down —
	 * the "vừa đào vừa cúi". The face's own column is always inside the box, so it
	 * may always be dug: enter it from above like a well, let the descent consume
	 * the middle cell, and the cursor then moves on to the next face, which gets
	 * worked normally from inside the opened row.
	 */
	public BlockPos entryPos() {
		return posAt(currentTravel(), rowCenter(), layerBottom());
	}

	public BlockPos posAt(int t, int c, int y) {
		return travelAxis == Direction.Axis.X ? new BlockPos(t, y, c) : new BlockPos(c, y, t);
	}

	// ---- cursor ----

	public boolean isDone() {
		return done;
	}

	/** True once every face of this layer has been visited; the engine then verifies it. */
	public boolean areLayerFacesDone() {
		return layerFacesDone;
	}

	/** Move on to the next face of this layer. */
	public void advance() {
		if (layerFacesDone) {
			return;
		}
		int guard = 0;
		int limit = rowCount * stepCount + 2;
		do {
			step++;
			if (step >= stepCount) {
				step = 0;
				row++;
			}
			if (row >= rowCount) {
				row = 0;
				step = 0;
				if (pass == 0) {
					pass = 1; // now mop up the clipped edges
				} else {
					layerFacesDone = true;
					return;
				}
			}
		} while (pass == 0 && !isFullFace() && ++guard < limit);
	}

	/**
	 * Drop to the next layer. Only call once the current one is verified empty.
	 *
	 * @return false when the whole box is finished.
	 */
	public boolean nextLayer() {
		layer++;
		if (layer >= layerCount) {
			done = true;
			return false;
		}
		row = 0;
		step = 0;
		pass = 0;
		layerFacesDone = false;
		if (!isFullFace()) {
			advance();
		}
		return true;
	}

	/** Re-open the current layer's faces, e.g. after gravel poured in. */
	public void restartLayer() {
		row = 0;
		step = 0;
		pass = 0;
		layerFacesDone = false;
	}

	/** Go back to the top layer and run the whole box again. */
	public void restartFromTop() {
		layer = 0;
		done = false;
		restartLayer();
		if (!isFullFace()) {
			advance();
		}
	}

	// ---- whole-layer bounds, for the verification sweep ----

	public int minTravel() { return minT; }
	public int maxTravel() { return maxT; }
	public int minCross() { return minC; }
	public int maxCross() { return maxC; }

	// ---- reporting ----

	public int layerIndex() { return layer; }
	public int layerCount() { return layerCount; }

	public float progress() {
		if (done) {
			return 1.0F;
		}
		double inLayer = layerFacesDone ? 1.0
				: (row + (double) step / Math.max(1, stepCount)) / Math.max(1, rowCount);
		return (float) Math.min(1.0, (layer + inLayer) / layerCount);
	}

	public String describe() {
		if (layerFacesDone) {
			return "tầng " + (layer + 1) + "/" + layerCount + " · đang kiểm tra sót";
		}
		return "tầng " + (layer + 1) + "/" + layerCount
				+ " · hàng " + (rowSlot() + 1) + "/" + rowCount
				+ " · " + (step + 1) + "/" + stepCount
				+ " · mặt " + faceWidth() + "x" + faceHeight() + "=" + cellsInFace() + " ô"
				+ " · trục " + (travelAxis == Direction.Axis.X ? "X" : "Z")
				+ (mirrored ? " · chiều ngược" : "")
				+ (pass == 1 ? " · vét rìa" : "");
	}

	public Selection selection() {
		return sel;
	}

	/**
	 * Whether this plan was built for exactly the box {@code other} spans now.
	 * Compared against the corners captured at construction — comparing against
	 * the live {@link #sel} always matched, because it IS the object being asked
	 * about, so a plan built for an old box would resume over new marks.
	 */
	public boolean matchesSelection(Selection other) {
		if (other == null || !other.isComplete()) {
			return false;
		}
		return selMinX == other.minX()
				&& selMinY == other.minY()
				&& selMinZ == other.minZ()
				&& selMaxX == other.maxX()
				&& selMaxY == other.maxY()
				&& selMaxZ == other.maxZ();
	}
}
