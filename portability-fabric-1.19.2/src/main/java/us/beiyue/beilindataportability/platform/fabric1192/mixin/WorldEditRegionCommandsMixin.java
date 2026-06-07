package us.beiyue.beilindataportability.platform.fabric1192.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.function.mask.Mask;
import com.sk89q.worldedit.function.pattern.Pattern;
import com.sk89q.worldedit.regions.Region;
import org.spongepowered.asm.mixin.Mixin;
import us.beiyue.beilindataportability.common.ActorContext;
import us.beiyue.beilindataportability.platform.fabric1192.BlockChangeRecorder1192;

@Mixin(targets = "com.sk89q.worldedit.command.RegionCommands", remap = false)
abstract class WorldEditRegionCommandsMixin {
	@WrapMethod(
		method = "set(Lcom/sk89q/worldedit/extension/platform/Actor;Lcom/sk89q/worldedit/EditSession;Lcom/sk89q/worldedit/regions/Region;Lcom/sk89q/worldedit/function/pattern/Pattern;)I",
		remap = false
	)
	private int beilinEntryPortability$wrapWorldEditSet(
		Actor actor,
		EditSession editSession,
		Region region,
		Pattern pattern,
		Operation<Integer> original
	) {
		ActorContext.Scope scope = BlockChangeRecorder1192.beginWorldEditSet(actor, editSession, region, pattern);
		Integer result = null;
		try {
			result = original.call(actor, editSession, region, pattern);
			return result;
		} finally {
			if (result != null) {
				BlockChangeRecorder1192.completeBulkScope(scope, result);
			} else {
				BlockChangeRecorder1192.abortBulkScope(scope);
			}
		}
	}

	@WrapMethod(
		method = "replace(Lcom/sk89q/worldedit/extension/platform/Actor;Lcom/sk89q/worldedit/EditSession;Lcom/sk89q/worldedit/regions/Region;Lcom/sk89q/worldedit/function/mask/Mask;Lcom/sk89q/worldedit/function/pattern/Pattern;)I",
		remap = false
	)
	private int beilinEntryPortability$wrapWorldEditReplace(
		Actor actor,
		EditSession editSession,
		Region region,
		Mask from,
		Pattern to,
		Operation<Integer> original
	) {
		ActorContext.Scope scope = BlockChangeRecorder1192.beginWorldEditReplace(actor, editSession, region, from, to);
		Integer result = null;
		try {
			result = original.call(actor, editSession, region, from, to);
			return result;
		} finally {
			if (result != null) {
				BlockChangeRecorder1192.completeBulkScope(scope, result);
			} else {
				BlockChangeRecorder1192.abortBulkScope(scope);
			}
		}
	}
}
