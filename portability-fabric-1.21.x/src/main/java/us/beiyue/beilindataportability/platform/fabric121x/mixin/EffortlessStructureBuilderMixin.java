package us.beiyue.beilindataportability.platform.fabric121x.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Coerce;
import us.beiyue.beilindataportability.common.ActorContext;
import us.beiyue.beilindataportability.platform.fabric121x.BlockChangeRecorder121x;

@Mixin(targets = "dev.huskuraft.effortless.EffortlessStructureBuilder", remap = false)
abstract class EffortlessStructureBuilderMixin {
	@WrapMethod(
		method = "onContextReceived",
		remap = false
	)
	private void beilinEntryPortability$wrapEffortlessBuild(
		@Coerce Object player,
		@Coerce Object context,
		Operation<Void> original
	) {
		ActorContext.Scope scope = BlockChangeRecorder121x.beginEffortlessBuild(player, context);
		boolean completed = false;
		try {
			original.call(player, context);
			completed = true;
		} finally {
			if (completed) {
				BlockChangeRecorder121x.completeBulkScope(scope);
			} else {
				BlockChangeRecorder121x.abortBulkScope(scope);
			}
		}
	}

	@WrapMethod(method = "undo", remap = false)
	private void beilinEntryPortability$wrapEffortlessUndo(@Coerce Object player, Operation<Void> original) {
		ActorContext.Scope scope = BlockChangeRecorder121x.beginEffortlessHistory(player, "EFFORTLESS_UNDO");
		boolean completed = false;
		try {
			original.call(player);
			completed = true;
		} finally {
			if (completed) {
				BlockChangeRecorder121x.completeBulkScope(scope);
			} else {
				BlockChangeRecorder121x.abortBulkScope(scope);
			}
		}
	}

	@WrapMethod(method = "redo", remap = false)
	private void beilinEntryPortability$wrapEffortlessRedo(@Coerce Object player, Operation<Void> original) {
		ActorContext.Scope scope = BlockChangeRecorder121x.beginEffortlessHistory(player, "EFFORTLESS_REDO");
		boolean completed = false;
		try {
			original.call(player);
			completed = true;
		} finally {
			if (completed) {
				BlockChangeRecorder121x.completeBulkScope(scope);
			} else {
				BlockChangeRecorder121x.abortBulkScope(scope);
			}
		}
	}
}
