package us.beiyue.beilindataportability.platform.fabric1192;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import us.beiyue.beilindataportability.common.ActorContext;
import us.beiyue.beilindataportability.common.BuildingIndexStore;

import java.util.function.Supplier;

public final class BlockChangeRecorder1192 {
	private static Supplier<BuildingIndexStore> storeSupplier = () -> null;

	private BlockChangeRecorder1192() {
	}

	public static void register(
		Supplier<BuildingIndexStore> storeSupplier,
		Logger log
	) {
		BlockChangeRecorder1192.storeSupplier = storeSupplier != null ? storeSupplier : () -> null;
		log.info("Beilin Data Portability block recorder configured for Mixin capture");
	}

	public static void recordSetBlock(Level level, BlockPos pos, BlockState oldState, BlockState newState) {
		BuildingIndexStore store = storeSupplier.get();
		if (store == null || level == null || level.isClientSide || pos == null || newState == null) return;
		ActorContext.Actor actor = ActorContext.current();
		if (actor == null) {
			actor = ActorContext.system("SYSTEM_SET_BLOCK");
		} else if (!actor.canAffectBlock(pos.getX(), pos.getY(), pos.getZ())) {
			return;
		}
		String dimension = level.dimension().location().toString();
		store.recordStateChangeWithSource(
			dimension,
			pos.getX(),
			pos.getY(),
			pos.getZ(),
			oldState != null ? oldState.toString() : null,
			newState.toString(),
			actor.name,
			actor.source
		);
	}
}
