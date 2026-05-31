package us.beiyue.beilindataportability.platform.fabric1201.mixin;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.function.mask.Mask;
import com.sk89q.worldedit.function.pattern.Pattern;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import us.beiyue.beilindataportability.platform.fabric1201.BlockChangeRecorder1201;

@Mixin(targets = "com.sk89q.worldedit.command.ClipboardCommands", remap = false)
abstract class WorldEditClipboardCommandsMixin {
	@Inject(
		method = "cut(Lcom/sk89q/worldedit/extension/platform/Actor;Lcom/sk89q/worldedit/LocalSession;Lcom/sk89q/worldedit/EditSession;Lcom/sk89q/worldedit/regions/Region;Lcom/sk89q/worldedit/function/pattern/Pattern;ZZLcom/sk89q/worldedit/function/mask/Mask;)V",
		at = @At("HEAD"),
		remap = false
	)
	private void beilinEntryPortability$beginWorldEditCut(
		Actor actor,
		LocalSession session,
		EditSession editSession,
		Region region,
		Pattern leavePattern,
		boolean copyEntities,
		boolean copyBiomes,
		Mask mask,
		CallbackInfo ci
	) {
		BlockChangeRecorder1201.pushBulkScope(BlockChangeRecorder1201.beginWorldEditCut(actor, editSession, region, leavePattern, mask));
	}

	@Inject(
		method = "cut(Lcom/sk89q/worldedit/extension/platform/Actor;Lcom/sk89q/worldedit/LocalSession;Lcom/sk89q/worldedit/EditSession;Lcom/sk89q/worldedit/regions/Region;Lcom/sk89q/worldedit/function/pattern/Pattern;ZZLcom/sk89q/worldedit/function/mask/Mask;)V",
		at = @At("RETURN"),
		remap = false
	)
	private void beilinEntryPortability$endWorldEditCut(
		Actor actor,
		LocalSession session,
		EditSession editSession,
		Region region,
		Pattern leavePattern,
		boolean copyEntities,
		boolean copyBiomes,
		Mask mask,
		CallbackInfo ci
	) {
		BlockChangeRecorder1201.closeBulkScope();
	}

	@Inject(
		method = "paste(Lcom/sk89q/worldedit/extension/platform/Actor;Lcom/sk89q/worldedit/world/World;Lcom/sk89q/worldedit/LocalSession;Lcom/sk89q/worldedit/EditSession;ZZZZZZLcom/sk89q/worldedit/function/mask/Mask;)V",
		at = @At("HEAD"),
		remap = false
	)
	private void beilinEntryPortability$beginWorldEditPaste(
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
		CallbackInfo ci
	) {
		BlockChangeRecorder1201.pushBulkScope(BlockChangeRecorder1201.beginWorldEditPaste(actor, editSession, session, atOrigin, onlySelect));
	}

	@Inject(
		method = "paste(Lcom/sk89q/worldedit/extension/platform/Actor;Lcom/sk89q/worldedit/world/World;Lcom/sk89q/worldedit/LocalSession;Lcom/sk89q/worldedit/EditSession;ZZZZZZLcom/sk89q/worldedit/function/mask/Mask;)V",
		at = @At("RETURN"),
		remap = false
	)
	private void beilinEntryPortability$endWorldEditPaste(
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
		CallbackInfo ci
	) {
		BlockChangeRecorder1201.closeBulkScope();
	}
}
