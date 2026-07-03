package com.autobuilder.build;

import com.autobuilder.config.AutoBuilderConfig;
import com.autobuilder.schematic.SchematicData;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.Input;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Drives the whole auto-build process, one client tick at a time:
 * walk to a spot near the next block, aim at the right face, make sure the
 * right item is in hand (refilling from the inventory if not), place it,
 * verify it landed, then move to the next one.
 */
public final class AutoBuildEngine {

	public enum State {IDLE, NAVIGATING, FACING, INVENTORY, PLACING, VERIFYING, PAUSED, DONE}

	private static final float TURN_DEGREES_PER_TICK = 12.0F;
	private static final int VERIFY_WAIT_TICKS = 2;

	private final MinecraftClient client;
	private final AutoBuilderConfig config;

	private State state = State.IDLE;
	private Deque<PlacementTask> queue = new ArrayDeque<>();
	private PlacementTask current;
	private Vec3d standTarget;
	private double standDistance;
	private int standRetries;
	private int placeRetries;
	private int waitTicks;
	private int cooldownTicks;
	private String pauseReason;

	private MovementController movement;
	private AutoBuildInput autoInput;
	private InventoryManager inventoryManager;
	private Input savedInput;

	public AutoBuildEngine(MinecraftClient client, AutoBuilderConfig config) {
		this.client = client;
		this.config = config;
	}

	public State state() {
		return state;
	}

	public int remainingTasks() {
		return queue.size() + (current != null ? 1 : 0);
	}

	public boolean isActive() {
		return state != State.IDLE && state != State.DONE;
	}

	public void start(SchematicData schematic, BlockPos origin) {
		ClientPlayerEntity player = client.player;
		if (player == null || client.world == null) {
			return;
		}

		stop();

		queue = com.autobuilder.build.BuildPlanner.plan(schematic, origin, client.world);
		current = null;
		placeRetries = 0;
		standRetries = 0;
		cooldownTicks = 0;
		pauseReason = null;

		this.autoInput = new AutoBuildInput();
		this.savedInput = player.input;
		player.input = autoInput;
		this.movement = new MovementController(player, autoInput);
		this.inventoryManager = new InventoryManager(client);

		if (queue.isEmpty()) {
			state = State.DONE;
			player.sendMessage(Text.translatable("autobuilder.msg.done"), false);
		} else {
			state = State.NAVIGATING;
			player.sendMessage(Text.translatable("autobuilder.msg.started"), false);
		}
	}

	public void pause() {
		if (isActive() && state != State.PAUSED) {
			state = State.PAUSED;
			if (movement != null) movement.stop();
			if (client.player != null) client.player.sendMessage(Text.translatable("autobuilder.msg.paused"), false);
		}
	}

	public void resume() {
		if (state == State.PAUSED) {
			state = State.NAVIGATING;
			if (client.player != null) client.player.sendMessage(Text.translatable("autobuilder.msg.resumed"), false);
		}
	}

	public void stop() {
		if (client.player != null && savedInput != null) {
			client.player.input = savedInput;
		}
		if (inventoryManager != null) {
			inventoryManager.cancel();
		}
		savedInput = null;
		autoInput = null;
		movement = null;
		inventoryManager = null;
		queue = new ArrayDeque<>();
		current = null;
		state = State.IDLE;
	}

	public void tick() {
		if (state == State.IDLE || state == State.PAUSED || state == State.DONE) {
			return;
		}

		ClientPlayerEntity player = client.player;
		if (player == null || client.world == null) {
			stop();
			return;
		}

		if (cooldownTicks > 0) {
			cooldownTicks--;
			return;
		}

		if (current == null) {
			if (!nextTask()) {
				finish();
				return;
			}
		}

		switch (state) {
			case NAVIGATING -> tickNavigating(player);
			case FACING -> tickFacing(player);
			case INVENTORY -> tickInventory();
			case PLACING -> tickPlacing(player);
			case VERIFYING -> tickVerifying(player);
			default -> {
			}
		}
	}

	private boolean nextTask() {
		current = queue.poll();
		if (current == null) {
			return false;
		}
		standDistance = 2.2;
		standRetries = 0;
		placeRetries = 0;
		standTarget = computeStandTarget(current, standDistance);
		state = State.NAVIGATING;
		return true;
	}

	private void finish() {
		if (client.player != null) {
			client.player.sendMessage(Text.translatable("autobuilder.msg.done"), false);
		}
		stop();
		state = State.DONE;
	}

	private void tickNavigating(ClientPlayerEntity player) {
		MovementController.Result result = movement.moveToward(standTarget, 0.35);
		switch (result) {
			case ARRIVED -> state = State.FACING;
			case STUCK -> skipCurrentTask(player, false);
			case MOVING -> {
			}
		}
	}

	private void tickFacing(ClientPlayerEntity player) {
		movement.stop();
		Vec3d faceCenter = faceCenter(current);

		if (player.getEyePos().squaredDistanceTo(faceCenter) > config.reachDistance * config.reachDistance) {
			standRetries++;
			if (standRetries > 6) {
				skipCurrentTask(player, false);
				return;
			}
			standDistance = Math.max(1.0, standDistance - 0.4);
			standTarget = computeStandTarget(current, standDistance);
			state = State.NAVIGATING;
			return;
		}

		boolean aimed = FacingUtil.turnToward(player, faceCenter, TURN_DEGREES_PER_TICK);
		if (aimed && FacingUtil.isLookingAt(client.crosshairTarget, current.neighborPos(), current.clickFace())) {
			Item desiredItem = current.desiredState().getBlock().asItem();
			if (inventoryManager.isHolding(desiredItem)) {
				state = State.PLACING;
			} else {
				InventoryManager.Result invResult = inventoryManager.begin(desiredItem);
				handleInventoryResult(player, invResult);
			}
		}
	}

	private void tickInventory() {
		InventoryManager.Result result = inventoryManager.tick();
		handleInventoryResult(client.player, result);
	}

	private void handleInventoryResult(ClientPlayerEntity player, InventoryManager.Result result) {
		switch (result) {
			case DONE -> state = State.PLACING;
			case IN_PROGRESS -> state = State.INVENTORY;
			case ITEM_NOT_FOUND -> {
				Item item = current.desiredState().getBlock().asItem();
				player.sendMessage(Text.translatable("autobuilder.msg.need_item", item.getName()), false);
				pause();
			}
		}
	}

	private void tickPlacing(ClientPlayerEntity player) {
		BlockHitResult hit = client.crosshairTarget instanceof BlockHitResult bhr ? bhr : null;
		if (hit == null || !FacingUtil.isLookingAt(hit, current.neighborPos(), current.clickFace())) {
			state = State.FACING;
			return;
		}

		ActionResult result = client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
		if (result instanceof ActionResult.Success success) {
			if (success.swingSource() == ActionResult.SwingSource.CLIENT) {
				player.swingHand(Hand.MAIN_HAND);
			}
			waitTicks = 0;
			state = State.VERIFYING;
		} else {
			placeRetries++;
			if (placeRetries > config.maxRetriesPerBlock) {
				skipCurrentTask(player, true);
			}
		}
	}

	private void tickVerifying(ClientPlayerEntity player) {
		waitTicks++;
		if (waitTicks < VERIFY_WAIT_TICKS) {
			return;
		}

		BlockState actual = client.world.getBlockState(current.target());
		if (actual.equals(current.desiredState())) {
			current = null;
			cooldownTicks = config.ticksBetweenPlacements;
			state = State.NAVIGATING;
		} else {
			placeRetries++;
			if (placeRetries > config.maxRetriesPerBlock) {
				skipCurrentTask(player, true);
			} else {
				state = State.FACING;
			}
		}
	}

	private void skipCurrentTask(ClientPlayerEntity player, boolean announce) {
		if (announce) {
			player.sendMessage(Text.translatable("autobuilder.msg.stuck", current.target().toShortString()), false);
		}
		current = null;
		state = State.NAVIGATING;
	}

	private static Vec3d faceCenter(PlacementTask task) {
		Direction face = task.clickFace();
		return Vec3d.ofCenter(task.neighborPos()).add(
				face.getOffsetX() * 0.5, face.getOffsetY() * 0.5, face.getOffsetZ() * 0.5);
	}

	private static Vec3d computeStandTarget(PlacementTask task, double distance) {
		Direction face = task.clickFace();
		BlockPos neighbor = task.neighborPos();

		if (face.getAxis() == Direction.Axis.Y) {
			return new Vec3d(neighbor.getX() + 0.5 + distance, task.target().getY(), neighbor.getZ() + 0.5);
		}

		Vec3d faceCenter = Vec3d.ofCenter(neighbor).add(
				face.getOffsetX() * 0.5, face.getOffsetY() * 0.5, face.getOffsetZ() * 0.5);
		return new Vec3d(
				faceCenter.x + face.getOffsetX() * distance,
				task.target().getY(),
				faceCenter.z + face.getOffsetZ() * distance);
	}
}
