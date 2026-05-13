package us.beiyue.beilinentryportability.platform.fabric1192.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import us.beiyue.beilinentryportability.common.ActorContext;

@Mixin(ServerPlayerGameMode.class)
abstract class ServerPlayerGameModeMixin {
	@Shadow
	protected ServerPlayer player;

	@Unique
	private ActorContext.Scope beilinEntryPortability$destroyScope;

	@Inject(method = "destroyBlock", at = @At("HEAD"))
	private void beilinEntryPortability$enterDestroyBlock(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
		beilinEntryPortability$destroyScope = ActorContext.pushNear(player.getGameProfile().getName(), "PLAYER_DESTROY_BLOCK", pos.getX(), pos.getY(), pos.getZ());
	}

	@Inject(method = "destroyBlock", at = @At("RETURN"))
	private void beilinEntryPortability$exitDestroyBlock(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
		if (beilinEntryPortability$destroyScope != null) {
			beilinEntryPortability$destroyScope.close();
			beilinEntryPortability$destroyScope = null;
		}
	}
}
