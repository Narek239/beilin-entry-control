package us.beiyue.beilindataportability.platform.fabric1201.mixin;

import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import us.beiyue.beilindataportability.platform.fabric1201.BlockChangeRecorder1201;

@Mixin(targets = "com.sk89q.worldedit.extent.world.SideEffectExtent", remap = false)
abstract class WorldEditSideEffectExtentMixin {
	@Shadow
	@Final
	private World world;

	@Inject(
		method = "setBlock(Lcom/sk89q/worldedit/math/BlockVector3;Lcom/sk89q/worldedit/world/block/BlockStateHolder;)Z",
		at = @At("RETURN"),
		remap = false
	)
	private void beilinEntryPortability$recordWorldEditSideEffectBlock(
		BlockVector3 location,
		BlockStateHolder<?> block,
		CallbackInfoReturnable<Boolean> cir
	) {
		if (Boolean.TRUE.equals(cir.getReturnValue())) {
			BlockChangeRecorder1201.recordWorldEditExtentBlock(world, location, block);
		}
	}
}
