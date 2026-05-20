package us.beiyue.beilindataportability.platform.fabric121x.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import us.beiyue.beilindataportability.common.ActorContext;

@Mixin(ServerGamePacketListenerImpl.class)
abstract class ServerGamePacketListenerImplMixin {
	@Shadow
	public ServerPlayer player;

	@Unique
	private ActorContext.Scope beilinEntryPortability$useItemOnScope;
	@Unique
	private ActorContext.Scope beilinEntryPortability$useItemScope;

	@Inject(method = "handleUseItemOn", at = @At("HEAD"))
	private void beilinEntryPortability$enterUseItemOn(ServerboundUseItemOnPacket packet, CallbackInfo ci) {
		BlockPos pos = packet.getHitResult().getBlockPos();
		beilinEntryPortability$useItemOnScope = ActorContext.pushNear(player.getGameProfile().getName(), "PLAYER_USE_ITEM_ON", pos.getX(), pos.getY(), pos.getZ());
	}

	@Inject(method = "handleUseItemOn", at = @At("RETURN"))
	private void beilinEntryPortability$exitUseItemOn(ServerboundUseItemOnPacket packet, CallbackInfo ci) {
		if (beilinEntryPortability$useItemOnScope != null) {
			beilinEntryPortability$useItemOnScope.close();
			beilinEntryPortability$useItemOnScope = null;
		}
	}

	@Inject(method = "handleUseItem", at = @At("HEAD"))
	private void beilinEntryPortability$enterUseItem(ServerboundUseItemPacket packet, CallbackInfo ci) {
		beilinEntryPortability$useItemScope = ActorContext.push(player.getGameProfile().getName(), "PLAYER_USE_ITEM");
	}

	@Inject(method = "handleUseItem", at = @At("RETURN"))
	private void beilinEntryPortability$exitUseItem(ServerboundUseItemPacket packet, CallbackInfo ci) {
		if (beilinEntryPortability$useItemScope != null) {
			beilinEntryPortability$useItemScope.close();
			beilinEntryPortability$useItemScope = null;
		}
	}
}
