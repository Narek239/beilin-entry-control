package us.beiyue.beilindataportability.platform.fabric1192.mixin;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import us.beiyue.beilindataportability.platform.fabric1192.BlockChangeRecorder1192;

@Mixin(value = EditSession.class, remap = false)
abstract class WorldEditEditSessionMixin {
	@Inject(
		method = "setBlock(Lcom/sk89q/worldedit/math/BlockVector3;Lcom/sk89q/worldedit/world/block/BlockStateHolder;Lcom/sk89q/worldedit/EditSession$Stage;)Z",
		at = @At("RETURN"),
		remap = false
	)
	private void beilinEntryPortability$recordWorldEditSetBlock(
		BlockVector3 position,
		BlockStateHolder<?> block,
		EditSession.Stage stage,
		CallbackInfoReturnable<Boolean> cir
	) {
		if (Boolean.TRUE.equals(cir.getReturnValue())) {
			BlockChangeRecorder1192.recordWorldEditSetBlock((EditSession) (Object) this, position, block);
		}
	}
}
