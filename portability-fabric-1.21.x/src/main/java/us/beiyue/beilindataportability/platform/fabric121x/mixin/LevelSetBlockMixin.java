package us.beiyue.beilindataportability.platform.fabric121x.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import us.beiyue.beilindataportability.platform.fabric121x.BlockChangeRecorder121x;

@Mixin(Level.class)
abstract class LevelSetBlockMixin {
	@Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("HEAD"))
	private void beilinEntryPortability$captureOldState(
		BlockPos pos,
		BlockState state,
		int flags,
		int recursionLeft,
		CallbackInfoReturnable<Boolean> cir
	) {
		BlockChangeRecorder121x.captureOldState((Level) (Object) this, pos);
	}

	@Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("RETURN"))
	private void beilinEntryPortability$recordSetBlock(
		BlockPos pos,
		BlockState state,
		int flags,
		int recursionLeft,
		CallbackInfoReturnable<Boolean> cir
	) {
		BlockState oldState = BlockChangeRecorder121x.popCapturedOldState();
		if (Boolean.TRUE.equals(cir.getReturnValue())) {
			BlockChangeRecorder121x.recordSetBlock((Level) (Object) this, pos, oldState, state);
		}
	}
}
