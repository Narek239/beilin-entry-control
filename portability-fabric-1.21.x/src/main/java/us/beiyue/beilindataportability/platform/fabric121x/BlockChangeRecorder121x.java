package us.beiyue.beilindataportability.platform.fabric121x;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import us.beiyue.beilindataportability.common.ActorContext;
import us.beiyue.beilindataportability.common.BulkBlockChange;
import us.beiyue.beilindataportability.common.BulkPlacementBounds;
import us.beiyue.beilindataportability.common.BulkPlacementIntrospection;
import us.beiyue.beilindataportability.common.BuildingIndexStore;

import java.util.ArrayDeque;
import java.util.function.Supplier;

public final class BlockChangeRecorder121x {
	private static Supplier<BuildingIndexStore> storeSupplier = () -> null;
	private static volatile boolean recordWorldEditBulkPlacements = true;
	private static volatile boolean recordEffortlessBulkPlacements = true;
	private static volatile boolean discardLinearBulkPlacements = true;
	private static final ThreadLocal<ArrayDeque<BlockState>> OLD_STATES = ThreadLocal.withInitial(ArrayDeque::new);
	private static final ThreadLocal<ArrayDeque<Boolean>> OLD_STATE_CAPTURED = ThreadLocal.withInitial(ArrayDeque::new);
	private static final ThreadLocal<ArrayDeque<ActorContext.Scope>> BULK_SCOPES = ThreadLocal.withInitial(ArrayDeque::new);
	private static final ThreadLocal<ArrayDeque<Boolean>> BULK_SCOPE_PRESENT = ThreadLocal.withInitial(ArrayDeque::new);

	private BlockChangeRecorder121x() {
	}

	public static void register(
		Supplier<BuildingIndexStore> storeSupplier,
		Logger log
	) {
		BlockChangeRecorder121x.storeSupplier = storeSupplier != null ? storeSupplier : () -> null;
		log.info("Beilin Data Portability block recorder configured for Mixin capture");
	}

	public static void configureBulkOptions(
		boolean recordWorldEditBulkPlacements,
		boolean recordEffortlessBulkPlacements,
		boolean discardLinearBulkPlacements
	) {
		BlockChangeRecorder121x.recordWorldEditBulkPlacements = recordWorldEditBulkPlacements;
		BlockChangeRecorder121x.recordEffortlessBulkPlacements = recordEffortlessBulkPlacements;
		BlockChangeRecorder121x.discardLinearBulkPlacements = discardLinearBulkPlacements;
	}

	public static boolean shouldCaptureOldState() {
		ActorContext.Actor actor = ActorContext.current();
		return actor == null || (!actor.shouldIgnoreBlockRecords() && !actor.shouldForcePlacementRecords());
	}

	public static void captureOldState(Level level, BlockPos pos) {
		boolean captureOldState = shouldCaptureOldState() && level != null && pos != null;
		OLD_STATE_CAPTURED.get().push(captureOldState);
		if (captureOldState) {
			OLD_STATES.get().push(level.getBlockState(pos));
		}
	}

	public static BlockState popCapturedOldState() {
		ArrayDeque<Boolean> captured = OLD_STATE_CAPTURED.get();
		boolean capturedOldState = !captured.isEmpty() && captured.pop();
		ArrayDeque<BlockState> oldStates = OLD_STATES.get();
		return capturedOldState && !oldStates.isEmpty() ? oldStates.pop() : null;
	}

	public static void pushBulkScope(ActorContext.Scope scope) {
		BULK_SCOPE_PRESENT.get().push(scope != null);
		if (scope != null) {
			BULK_SCOPES.get().push(scope);
		}
	}

	public static void closeBulkScope() {
		ArrayDeque<Boolean> present = BULK_SCOPE_PRESENT.get();
		boolean hasScope = !present.isEmpty() && present.pop();
		ArrayDeque<ActorContext.Scope> scopes = BULK_SCOPES.get();
		if (hasScope && !scopes.isEmpty()) scopes.pop().close();
	}

	public static ActorContext.Scope beginWorldEditSet(Object actor, Object editSession, Object region, Object pattern) {
		if (!recordWorldEditBulkPlacements) return null;
		String actorName = BulkPlacementIntrospection.actorName(actor);
		BulkPlacementBounds bounds = BulkPlacementIntrospection.worldEditRegionBounds(editSession, region);
		if (BulkPlacementIntrospection.isWorldEditAirPattern(pattern)) {
			return beginBulkDeleteBounds(actorName, "WORLDEDIT_SET", bounds);
		}
		return beginBulkRecordOrDiscardLinear(actorName, "WORLDEDIT_SET", bounds);
	}

	public static ActorContext.Scope beginWorldEditReplace(Object actor, Object editSession, Object region, Object from, Object to) {
		if (!recordWorldEditBulkPlacements) return null;
		String actorName = BulkPlacementIntrospection.actorName(actor);
		BulkPlacementBounds bounds = BulkPlacementIntrospection.worldEditRegionBounds(editSession, region);
		if (from == null && BulkPlacementIntrospection.isWorldEditAirPattern(to)) {
			return beginBulkDeleteBounds(actorName, "WORLDEDIT_REPLACE", bounds);
		}
		return beginBulkRecordOrDiscardLinear(actorName, "WORLDEDIT_REPLACE", bounds);
	}

	public static ActorContext.Scope beginWorldEditCut(Object actor, Object editSession, Object region, Object leavePattern, Object mask) {
		if (!recordWorldEditBulkPlacements) return null;
		String actorName = BulkPlacementIntrospection.actorName(actor);
		BulkPlacementBounds bounds = BulkPlacementIntrospection.worldEditRegionBounds(editSession, region);
		if (mask == null && (leavePattern == null || BulkPlacementIntrospection.isWorldEditAirPattern(leavePattern))) {
			return beginBulkDeleteBounds(actorName, "WORLDEDIT_CUT", bounds);
		}
		return beginBulkRecordOrDiscardLinear(actorName, "WORLDEDIT_CUT", bounds);
	}

	public static ActorContext.Scope beginWorldEditPaste(Object actor, Object editSession, Object session, boolean atOrigin, boolean onlySelect) {
		if (!recordWorldEditBulkPlacements || onlySelect) return null;
		return beginBulkRecordOrDiscardLinear(
			BulkPlacementIntrospection.actorName(actor),
			"WORLDEDIT_PASTE",
			BulkPlacementIntrospection.worldEditPasteBounds(editSession, session, actor, atOrigin)
		);
	}

	public static ActorContext.Scope beginWorldEditHistory(Object actor, String source) {
		if (!recordWorldEditBulkPlacements) return null;
		return ActorContext.pushBulkRecord(BulkPlacementIntrospection.actorName(actor), source, BlockChangeRecorder121x::flushBulkChanges);
	}

	public static ActorContext.Scope beginEffortlessHistory(Object player, String source) {
		if (!recordEffortlessBulkPlacements) return null;
		return ActorContext.pushBulkRecord(BulkPlacementIntrospection.actorName(player), source, BlockChangeRecorder121x::flushBulkChanges);
	}

	public static ActorContext.Scope beginEffortlessBuild(Object player, Object context) {
		if (!recordEffortlessBulkPlacements || !BulkPlacementIntrospection.isEffortlessBulkContext(context)) return null;
		String actorName = BulkPlacementIntrospection.actorName(player);
		BulkPlacementBounds bounds = BulkPlacementIntrospection.effortlessBounds(player, context);
		if ("BREAK_BLOCK".equals(BulkPlacementIntrospection.effortlessBuildState(context))) {
			return beginBulkDeleteBounds(actorName, "EFFORTLESS_BUILD", bounds);
		}
		return beginBulkRecordOrDiscardLinear(actorName, "EFFORTLESS_BUILD", bounds);
	}

	public static void recordWorldEditSetBlock(Object editSession, Object position, Object newState) {
		recordWorldEditBulkBlock(worldEditLevel(editSession), position, newState, true);
	}

	public static void recordWorldEditExtentBlock(Object worldEditWorld, Object position, Object newState) {
		Level level = worldEditWorldLevel(worldEditWorld);
		String dimension = level != null
			? level.dimension().location().toString()
			: BulkPlacementIntrospection.worldEditWorldDimension(worldEditWorld);
		recordWorldEditBulkBlock(dimension, position, newState, false);
	}

	private static void recordWorldEditBulkBlock(Level level, Object position, Object newState, boolean strict) {
		String dimension = level != null ? level.dimension().location().toString() : null;
		recordWorldEditBulkBlock(dimension, position, newState, strict);
	}

	private static void recordWorldEditBulkBlock(String dimension, Object position, Object newState, boolean strict) {
		ActorContext.Actor actor = ActorContext.current();
		if (actor == null || actor.shouldIgnoreBlockRecords()) return;
		if (!actor.shouldForcePlacementRecords()) return;
		Integer x = BulkPlacementIntrospection.blockCoordinate(position, "x");
		Integer y = BulkPlacementIntrospection.blockCoordinate(position, "y");
		Integer z = BulkPlacementIntrospection.blockCoordinate(position, "z");
		if (dimension == null || x == null || y == null || z == null || newState == null) {
			if (strict) {
				throw new IllegalStateException("Unable to record WorldEdit set block: missing level, position, or block state");
			}
			return;
		}
		actor.addBulkChange(new BulkBlockChange(
			dimension,
			x,
			y,
			z,
			null,
			newState.toString(),
			false,
			true
		));
	}

	public static void recordEffortlessBlockResult(Object result) {
		ActorContext.Actor actor = ActorContext.current();
		if (actor == null || actor.shouldIgnoreBlockRecords()) return;
		if (!actor.shouldForcePlacementRecords()) return;
		if (!effortlessResultSuccess(result)) return;
		Object operation = BulkPlacementIntrospection.invokeAny(result, "getOperation");
		Object position = BulkPlacementIntrospection.invokeAny(operation, "getBlockPosition");
		Object world = BulkPlacementIntrospection.invokeAny(operation, "getWorld");
		Object oldState = BulkPlacementIntrospection.invokeAny(result, "getBlockStateToBreak");
		Object newState = BulkPlacementIntrospection.invokeAny(result, "getBlockStatePlaced");
		Integer x = BulkPlacementIntrospection.blockCoordinate(position, "x");
		Integer y = BulkPlacementIntrospection.blockCoordinate(position, "y");
		Integer z = BulkPlacementIntrospection.blockCoordinate(position, "z");
		String dimension = effortlessWorldDimension(world);
		if (dimension == null || x == null || y == null || z == null || newState == null) {
			throw new IllegalStateException("Unable to record Effortless block result: missing dimension, position, or block state");
		}
		actor.addBulkChange(new BulkBlockChange(
			dimension,
			x,
			y,
			z,
			blockStateString(oldState),
			blockStateString(newState),
			Boolean.TRUE.equals(BulkPlacementIntrospection.invokeAny(oldState, "isReplaceable")),
			true
		));
	}

	public static void recordSetBlock(Level level, BlockPos pos, BlockState oldState, BlockState newState) {
		ActorContext.Actor actor = ActorContext.current();
		if (actor != null && actor.shouldIgnoreBlockRecords()) return;
		BuildingIndexStore store = storeSupplier.get();
		if (store == null || level == null || level.isClientSide || pos == null || newState == null) return;
		if (actor == null) {
			actor = ActorContext.system("SYSTEM_SET_BLOCK");
		} else if (!actor.canAffectBlock(pos.getX(), pos.getY(), pos.getZ())) {
			return;
		}
		String dimension = level.dimension().location().toString();
		if (actor.shouldForcePlacementRecords()) {
			actor.addBulkChange(new BulkBlockChange(
				dimension,
				pos.getX(),
				pos.getY(),
				pos.getZ(),
				null,
				newState.toString(),
				false,
				true
			));
			return;
		}
		boolean oldBlockReplaceable = oldState != null && oldState.canBeReplaced();
		store.recordStateChangeWithSource(
			dimension,
			pos.getX(),
			pos.getY(),
			pos.getZ(),
			oldState != null ? oldState.toString() : null,
			oldBlockReplaceable,
			newState.toString(),
			actor.name,
			actor.source
		);
	}

	private static ActorContext.Scope beginBulkRecordOrDiscardLinear(String actorName, String source, BulkPlacementBounds bounds) {
		if (bounds != null && discardLinearBulkPlacements && bounds.isLinearInfrastructure()) {
			return beginBulkDeleteBounds(actorName, source, bounds, "mixed");
		}
		return ActorContext.pushBulkRecord(actorName, source, BlockChangeRecorder121x::flushBulkChanges);
	}

	private static ActorContext.Scope beginBulkDeleteBounds(String actorName, String source, BulkPlacementBounds bounds) {
		return beginBulkDeleteBounds(actorName, source, bounds, "delete");
	}

	private static ActorContext.Scope beginBulkDeleteBounds(String actorName, String source, BulkPlacementBounds bounds, String changeType) {
		if (bounds == null) return ActorContext.pushBulkRecord(actorName, source, BlockChangeRecorder121x::flushBulkChanges);
		return ActorContext.pushBulkDeleteBounds(actorName, source, bounds, changeType, actor -> {
			BuildingIndexStore store = storeSupplier.get();
			if (store != null) {
				store.deleteIndexedBlocksInBounds(actor.bulkBounds(), actor.name, actor.source, actor.bulkChangeType());
			}
		});
	}

	private static void flushBulkChanges(ActorContext.Actor actor) {
		BuildingIndexStore store = storeSupplier.get();
		if (store != null) {
			store.recordBulkStateChanges(actor.bulkChanges(), actor.name, actor.source);
		}
	}

	private static Level worldEditLevel(Object editSession) {
		Object worldEditWorld = BulkPlacementIntrospection.invokeNoArgs(editSession, "getWorld");
		return worldEditWorldLevel(worldEditWorld);
	}

	private static Level worldEditWorldLevel(Object worldEditWorld) {
		Object minecraftWorld = BulkPlacementIntrospection.invokeNoArgs(worldEditWorld, "getWorld");
		return minecraftWorld instanceof Level level ? level : null;
	}

	private static boolean effortlessResultSuccess(Object result) {
		Object resultType = BulkPlacementIntrospection.invokeAny(result, "result");
		return Boolean.TRUE.equals(BulkPlacementIntrospection.invokeAny(resultType, "success"));
	}

	private static String effortlessWorldDimension(Object world) {
		Object dimensionId = BulkPlacementIntrospection.invokeAny(world, "getDimensionId");
		Object location = BulkPlacementIntrospection.invokeAny(dimensionId, "location");
		String dimension = location != null ? location.toString() : null;
		return dimension != null && !dimension.isBlank() ? dimension : null;
	}

	private static String blockStateString(Object state) {
		if (state == null) return null;
		if (Boolean.TRUE.equals(BulkPlacementIntrospection.invokeAny(state, "isAir"))) return "minecraft:air";
		return state.toString();
	}
}
