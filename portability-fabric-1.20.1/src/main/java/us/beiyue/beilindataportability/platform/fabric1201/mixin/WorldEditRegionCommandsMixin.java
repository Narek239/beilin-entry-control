package us.beiyue.beilindataportability.platform.fabric1201.mixin;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.function.mask.Mask;
import com.sk89q.worldedit.function.pattern.Pattern;
import com.sk89q.worldedit.regions.Region;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import us.beiyue.beilindataportability.platform.fabric1201.BlockChangeRecorder1201;

@Mixin(targets = "com.sk89q.worldedit.command.RegionCommands", remap = false)
abstract class WorldEditRegionCommandsMixin {
	@Inject(
		method = "set(Lcom/sk89q/worldedit/extension/platform/Actor;Lcom/sk89q/worldedit/EditSession;Lcom/sk89q/worldedit/regions/Region;Lcom/sk89q/worldedit/function/pattern/Pattern;)I",
		at = @At("HEAD"),
		remap = false
	)
	private void beilinEntryPortability$beginWorldEditSet(
		Actor actor,
		EditSession editSession,
		Region region,
		Pattern pattern,
		CallbackInfoReturnable<Integer> cir
	) {
		BlockChangeRecorder1201.pushBulkScope(BlockChangeRecorder1201.beginWorldEditSet(actor, editSession, region, pattern));
	}

	@Inject(
		method = "set(Lcom/sk89q/worldedit/extension/platform/Actor;Lcom/sk89q/worldedit/EditSession;Lcom/sk89q/worldedit/regions/Region;Lcom/sk89q/worldedit/function/pattern/Pattern;)I",
		at = @At("RETURN"),
		remap = false
	)
	private void beilinEntryPortability$endWorldEditSet(
		Actor actor,
		EditSession editSession,
		Region region,
		Pattern pattern,
		CallbackInfoReturnable<Integer> cir
	) {
		BlockChangeRecorder1201.closeBulkScope();
	}

	@Inject(
		method = "replace(Lcom/sk89q/worldedit/extension/platform/Actor;Lcom/sk89q/worldedit/EditSession;Lcom/sk89q/worldedit/regions/Region;Lcom/sk89q/worldedit/function/mask/Mask;Lcom/sk89q/worldedit/function/pattern/Pattern;)I",
		at = @At("HEAD"),
		remap = false
	)
	private void beilinEntryPortability$beginWorldEditReplace(
		Actor actor,
		EditSession editSession,
		Region region,
		Mask from,
		Pattern to,
		CallbackInfoReturnable<Integer> cir
	) {
		BlockChangeRecorder1201.pushBulkScope(BlockChangeRecorder1201.beginWorldEditReplace(actor, editSession, region, from, to));
	}

	@Inject(
		method = "replace(Lcom/sk89q/worldedit/extension/platform/Actor;Lcom/sk89q/worldedit/EditSession;Lcom/sk89q/worldedit/regions/Region;Lcom/sk89q/worldedit/function/mask/Mask;Lcom/sk89q/worldedit/function/pattern/Pattern;)I",
		at = @At("RETURN"),
		remap = false
	)
	private void beilinEntryPortability$endWorldEditReplace(
		Actor actor,
		EditSession editSession,
		Region region,
		Mask from,
		Pattern to,
		CallbackInfoReturnable<Integer> cir
	) {
		BlockChangeRecorder1201.closeBulkScope();
	}
}
