package us.beiyue.beilindataportability.platform.fabric1192.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import us.beiyue.beilindataportability.platform.fabric1192.BlockChangeRecorder1192;

import java.util.ArrayDeque;

@Mixin(Level.class)
abstract class LevelSetBlockMixin {
	@Unique
	private final ThreadLocal<ArrayDeque<BlockState>> beilinEntryPortability$oldStates = ThreadLocal.withInitial(ArrayDeque::new);

	@Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("HEAD"))
	private void beilinEntryPortability$captureOldState(
		BlockPos pos,
		BlockState state,
		int flags,
		int recursionLeft,
		CallbackInfoReturnable<Boolean> cir
	) {
		Level level = (Level) (Object) this;
		beilinEntryPortability$oldStates.get().push(pos != null ? level.getBlockState(pos) : state);
	}

	@Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("RETURN"))
	private void beilinEntryPortability$recordSetBlock(
		BlockPos pos,
		BlockState state,
		int flags,
		int recursionLeft,
		CallbackInfoReturnable<Boolean> cir
	) {
		ArrayDeque<BlockState> oldStates = beilinEntryPortability$oldStates.get();
		BlockState oldState = oldStates.isEmpty() ? null : oldStates.pop();
		if (Boolean.TRUE.equals(cir.getReturnValue())) {
			BlockChangeRecorder1192.recordSetBlock((Level) (Object) this, pos, oldState, state);
		}
	}
}
