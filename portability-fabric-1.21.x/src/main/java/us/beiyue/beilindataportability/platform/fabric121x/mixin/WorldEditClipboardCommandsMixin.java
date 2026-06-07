package us.beiyue.beilindataportability.platform.fabric121x.mixin;

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
import us.beiyue.beilindataportability.platform.fabric121x.BlockChangeRecorder121x;

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
		ActorContext.Scope scope = BlockChangeRecorder121x.beginWorldEditCut(actor, editSession, region, leavePattern, mask);
		boolean completed = false;
		try {
			original.call(actor, session, editSession, region, leavePattern, copyEntities, copyBiomes, mask);
			completed = true;
		} finally {
			if (completed) {
				BlockChangeRecorder121x.completeBulkScope(scope);
			} else {
				BlockChangeRecorder121x.abortBulkScope(scope);
			}
		}
	}

	@WrapMethod(
		method = "paste(Lcom/sk89q/worldedit/extension/platform/Actor;Lcom/sk89q/worldedit/world/World;Lcom/sk89q/worldedit/LocalSession;Lcom/sk89q/worldedit/EditSession;ZZZZZZZLcom/sk89q/worldedit/function/mask/Mask;)V",
		remap = false
	)
	private void beilinEntryPortability$wrapWorldEditPaste(
		Actor actor,
		World world,
		LocalSession session,
		EditSession editSession,
		boolean ignoreAirBlocks,
		boolean pasteStructureVoid,
		boolean atOrigin,
		boolean selectPasted,
		boolean onlySelect,
		boolean pasteEntities,
		boolean pasteBiomes,
		Mask sourceMask,
		Operation<Void> original
	) {
		ActorContext.Scope scope = BlockChangeRecorder121x.beginWorldEditPaste(actor, editSession, session, atOrigin, onlySelect);
		boolean completed = false;
		try {
			original.call(
				actor,
				world,
				session,
				editSession,
				ignoreAirBlocks,
				pasteStructureVoid,
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
				BlockChangeRecorder121x.completeBulkScope(scope);
			} else {
				BlockChangeRecorder121x.abortBulkScope(scope);
			}
		}
	}
}
