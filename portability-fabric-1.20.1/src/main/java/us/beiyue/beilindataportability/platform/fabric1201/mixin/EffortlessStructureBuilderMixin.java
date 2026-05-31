package us.beiyue.beilindataportability.platform.fabric1201.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import us.beiyue.beilindataportability.platform.fabric1201.BlockChangeRecorder1201;

@Mixin(targets = "dev.huskuraft.effortless.EffortlessStructureBuilder", remap = false)
abstract class EffortlessStructureBuilderMixin {
	@Inject(
		method = "onContextReceived",
		at = @At("HEAD"),
		remap = false
	)
	private void beilinEntryPortability$beginEffortlessBuild(
		@Coerce Object player,
		@Coerce Object context,
		CallbackInfo ci
	) {
		BlockChangeRecorder1201.pushBulkScope(BlockChangeRecorder1201.beginEffortlessBuild(player, context));
	}

	@Inject(
		method = "onContextReceived",
		at = @At("RETURN"),
		remap = false
	)
	private void beilinEntryPortability$endEffortlessBuild(
		@Coerce Object player,
		@Coerce Object context,
		CallbackInfo ci
	) {
		BlockChangeRecorder1201.closeBulkScope();
	}

	@Inject(method = "undo", at = @At("HEAD"), remap = false)
	private void beilinEntryPortability$beginEffortlessUndo(@Coerce Object player, CallbackInfo ci) {
		BlockChangeRecorder1201.pushBulkScope(BlockChangeRecorder1201.beginEffortlessHistory(player, "EFFORTLESS_UNDO"));
	}

	@Inject(method = "undo", at = @At("RETURN"), remap = false)
	private void beilinEntryPortability$endEffortlessUndo(@Coerce Object player, CallbackInfo ci) {
		BlockChangeRecorder1201.closeBulkScope();
	}

	@Inject(method = "redo", at = @At("HEAD"), remap = false)
	private void beilinEntryPortability$beginEffortlessRedo(@Coerce Object player, CallbackInfo ci) {
		BlockChangeRecorder1201.pushBulkScope(BlockChangeRecorder1201.beginEffortlessHistory(player, "EFFORTLESS_REDO"));
	}

	@Inject(method = "redo", at = @At("RETURN"), remap = false)
	private void beilinEntryPortability$endEffortlessRedo(@Coerce Object player, CallbackInfo ci) {
		BlockChangeRecorder1201.closeBulkScope();
	}
}
