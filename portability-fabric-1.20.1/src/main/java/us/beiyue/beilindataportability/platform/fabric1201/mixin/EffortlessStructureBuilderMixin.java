package us.beiyue.beilindataportability.platform.fabric1201.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Coerce;
import us.beiyue.beilindataportability.common.ActorContext;
import us.beiyue.beilindataportability.platform.fabric1201.BlockChangeRecorder1201;

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
		ActorContext.Scope scope = BlockChangeRecorder1201.beginEffortlessBuild(player, context);
		boolean completed = false;
		try {
			original.call(player, context);
			completed = true;
		} finally {
			if (completed) {
				BlockChangeRecorder1201.completeBulkScope(scope);
			} else {
				BlockChangeRecorder1201.abortBulkScope(scope);
			}
		}
	}

	@WrapMethod(method = "undo", remap = false)
	private void beilinEntryPortability$wrapEffortlessUndo(@Coerce Object player, Operation<Void> original) {
		ActorContext.Scope scope = BlockChangeRecorder1201.beginEffortlessHistory(player, "EFFORTLESS_UNDO");
		boolean completed = false;
		try {
			original.call(player);
			completed = true;
		} finally {
			if (completed) {
				BlockChangeRecorder1201.completeBulkScope(scope);
			} else {
				BlockChangeRecorder1201.abortBulkScope(scope);
			}
		}
	}

	@WrapMethod(method = "redo", remap = false)
	private void beilinEntryPortability$wrapEffortlessRedo(@Coerce Object player, Operation<Void> original) {
		ActorContext.Scope scope = BlockChangeRecorder1201.beginEffortlessHistory(player, "EFFORTLESS_REDO");
		boolean completed = false;
		try {
			original.call(player);
			completed = true;
		} finally {
			if (completed) {
				BlockChangeRecorder1201.completeBulkScope(scope);
			} else {
				BlockChangeRecorder1201.abortBulkScope(scope);
			}
		}
	}
}
