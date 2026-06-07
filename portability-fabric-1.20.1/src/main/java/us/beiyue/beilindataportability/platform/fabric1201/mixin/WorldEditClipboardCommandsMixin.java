package us.beiyue.beilindataportability.platform.fabric1201.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.function.mask.Mask;
import com.sk89q.worldedit.function.pattern.Pattern;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.world.World;
import org.spongepowered.asm.mixin.Mixin;
import us.beiyue.beilindataportability.common.ActorContext;
import us.beiyue.beilindataportability.platform.fabric1201.BlockChangeRecorder1201;

@Mixin(targets = "com.sk89q.worldedit.command.ClipboardCommands", remap = false)
abstract class WorldEditClipboardCommandsMixin {
	@WrapMethod(
		method = "cut(Lcom/sk89q/worldedit/extension/platform/Actor;Lcom/sk89q/worldedit/LocalSession;Lcom/sk89q/worldedit/EditSession;Lcom/sk89q/worldedit/regions/Region;Lcom/sk89q/worldedit/function/pattern/Pattern;ZZLcom/sk89q/worldedit/function/mask/Mask;)V",
		remap = false
	)
	private void beilinEntryPortability$wrapWorldEditCut(
		Actor actor,
		LocalSession session,
		EditSession editSession,
		Region region,
		Pattern leavePattern,
		boolean copyEntities,
		boolean copyBiomes,
		Mask mask,
		Operation<Void> original
	) {
		ActorContext.Scope scope = BlockChangeRecorder1201.beginWorldEditCut(actor, editSession, region, leavePattern, mask);
		boolean completed = false;
		try {
			original.call(actor, session, editSession, region, leavePattern, copyEntities, copyBiomes, mask);
			completed = true;
		} finally {
			if (completed) {
				BlockChangeRecorder1201.completeBulkScope(scope);
			} else {
				BlockChangeRecorder1201.abortBulkScope(scope);
			}
		}
	}

	@WrapMethod(
		method = "paste(Lcom/sk89q/worldedit/extension/platform/Actor;Lcom/sk89q/worldedit/world/World;Lcom/sk89q/worldedit/LocalSession;Lcom/sk89q/worldedit/EditSession;ZZZZZZLcom/sk89q/worldedit/function/mask/Mask;)V",
		remap = false
	)
	private void beilinEntryPortability$wrapWorldEditPaste(
		Actor actor,
		World world,
		LocalSession session,
		EditSession editSession,
		boolean ignoreAirBlocks,
		boolean atOrigin,
		boolean selectPasted,
		boolean onlySelect,
		boolean pasteEntities,
		boolean pasteBiomes,
		Mask sourceMask,
		Operation<Void> original
	) {
		ActorContext.Scope scope = BlockChangeRecorder1201.beginWorldEditPaste(actor, editSession, session, atOrigin, onlySelect);
		boolean completed = false;
		try {
			original.call(
				actor,
				world,
				session,
				editSession,
				ignoreAirBlocks,
				atOrigin,
				selectPasted,
				onlySelect,
				pasteEntities,
				pasteBiomes,
				sourceMask
			);
			completed = true;
		} finally {
			if (completed) {
				BlockChangeRecorder1201.completeBulkScope(scope);
			} else {
				BlockChangeRecorder1201.abortBulkScope(scope);
			}
		}
	}
}
