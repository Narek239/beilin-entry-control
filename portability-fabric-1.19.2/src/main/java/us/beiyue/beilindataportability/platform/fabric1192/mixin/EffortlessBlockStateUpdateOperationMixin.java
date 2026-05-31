package us.beiyue.beilindataportability.platform.fabric1192.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import us.beiyue.beilindataportability.platform.fabric1192.BlockChangeRecorder1192;

@Mixin(targets = "dev.huskuraft.effortless.building.operation.block.BlockStateUpdateOperation", remap = false)
abstract class EffortlessBlockStateUpdateOperationMixin {
	@Inject(method = "commit()Ldev/huskuraft/effortless/building/operation/block/BlockStateUpdateOperationResult;", at = @At("RETURN"), remap = false)
	private void beilinEntryPortability$recordEffortlessBlockResult(CallbackInfoReturnable<Object> cir) {
		BlockChangeRecorder1192.recordEffortlessBlockResult(cir.getReturnValue());
	}
}
