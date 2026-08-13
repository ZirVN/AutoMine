package com.automine.render;

import com.automine.AutoMineClient;
import com.automine.mine.QuarryEngine;
import com.automine.mine.QuarryPlan;
import com.automine.mine.Selection;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderSetup;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;

/**
 * Draws the marked box in the world: the whole selection in cyan, the layer
 * currently being dug in yellow, the nine cells of the face in hand in red with
 * the middle one picked out in white, and the block being chewed on in orange.
 * All coordinates are emitted relative to the camera, as the world renderer
 * expects.
 *
 * <p>Everything is drawn <b>through</b> terrain. Vanilla's {@code lines} layer
 * depth-tests, so from inside a quarry the box was hidden by the very rock being
 * dug — useless for watching what the bot is up to.
 */
public final class SelectionRenderer {

	private static final int BOX_COLOR = 0xFF3FD8CC;     // cyan — whole selection
	private static final int LAYER_COLOR = 0xFFFFD24A;   // yellow — current layer
	private static final int FACE_COLOR = 0xFFFF3B30;    // red — the 9 cells in hand
	private static final int CENTER_COLOR = 0xFFFFFFFF;  // white — the middle cell
	private static final int TARGET_COLOR = 0xFFFF9500;  // orange — block being mined
	private static final int FLUID_COLOR = 0xFF38BDF8;   // blue — fluid cell being sealed

	/**
	 * Lines that ignore the depth buffer, so the markers stay visible through walls.
	 * Built from vanilla's line pipeline with the depth test switched off — the same
	 * shape as the stock {@code lines_translucent}, which only disables depth
	 * <em>write</em> and is therefore still occluded.
	 */
	private static final RenderLayer SEE_THROUGH_LINES = RenderLayer.of(
			"automine_see_through_lines",
			RenderSetup.builder(RenderPipeline.builder(RenderPipelines.RENDERTYPE_LINES_SNIPPET)
							.withLocation("pipeline/automine_see_through_lines")
							.withBlend(BlendFunction.TRANSLUCENT)
							.withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
							.withDepthWrite(false)
							.build())
					.translucent()
					.build());

	private SelectionRenderer() {
	}

	public static void register() {
		WorldRenderEvents.AFTER_ENTITIES.register(SelectionRenderer::render);
	}

	private static void render(net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext context) {
		Selection sel = AutoMineClient.SELECTION;
		QuarryEngine engine = AutoMineClient.ENGINE;
		if (sel == null || !sel.isComplete() || !AutoMineClient.CONFIG.renderSelection) {
			return;
		}

		VertexConsumerProvider consumers = context.consumers();
		if (consumers == null) {
			return;
		}
		MatrixStack matrices = context.matrices();
		Vec3d cam = context.gameRenderer().getCamera().getCameraPos();
		VertexConsumer lines = consumers.getBuffer(SEE_THROUGH_LINES);

		// Whole selection.
		drawBox(matrices, lines, cam,
				sel.minX(), sel.minY(), sel.minZ(),
				sel.sizeX(), sel.sizeY(), sel.sizeZ(),
				BOX_COLOR, 2.0F);

		if (engine == null || engine.state() != QuarryEngine.State.RUNNING) {
			return;
		}

		// The slab being worked on right now — during faces AND the layer sweep,
		// since the sweep works the same layer until it is provably clean.
		QuarryPlan plan = engine.plan();
		if (plan != null && !plan.isDone()) {
			int bottom = plan.layerBottom();
			int height = plan.layerTop() - bottom + 1;
			drawBox(matrices, lines, cam,
					sel.minX(), bottom, sel.minZ(),
					sel.sizeX(), height, sel.sizeZ(),
					LAYER_COLOR, 2.5F);
		}

		// The slice the next swing takes, and the cell it's aimed at.
		BlockPos center = engine.faceCenter();
		for (BlockPos cell : engine.faceCells()) {
			if (!sel.contains(cell)) {
				continue; // at the rim the slice can hang outside the box
			}
			boolean isCenter = cell.equals(center);
			drawBlock(matrices, lines, cam, cell,
					isCenter ? CENTER_COLOR : FACE_COLOR,
					isCenter ? 4.0F : 2.0F);
		}

		// The block currently being broken.
		BlockPos target = engine.activeTarget();
		if (target != null) {
			drawBlock(matrices, lines, cam, target, TARGET_COLOR, 3.0F);
		}

		// The fluid cell being sealed.
		BlockPos fluid = engine.fluidTarget();
		if (fluid != null) {
			drawBlock(matrices, lines, cam, fluid, FLUID_COLOR, 3.0F);
		}
	}

	private static void drawBlock(MatrixStack matrices, VertexConsumer lines, Vec3d cam,
			BlockPos pos, int color, float lineWidth) {
		drawBox(matrices, lines, cam, pos.getX(), pos.getY(), pos.getZ(), 1, 1, 1, color, lineWidth);
	}

	private static void drawBox(MatrixStack matrices, VertexConsumer lines, Vec3d cam,
			int x, int y, int z, int sizeX, int sizeY, int sizeZ, int color, float lineWidth) {
		VoxelShape shape = VoxelShapes.cuboidUnchecked(0, 0, 0, sizeX, sizeY, sizeZ);
		VertexRendering.drawOutline(matrices, lines, shape,
				x - cam.x, y - cam.y, z - cam.z, color, lineWidth);
	}
}
