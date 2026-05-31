package us.beiyue.beilindataportability.platform.fabric1201.mixin;

import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.extension.platform.Actor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import us.beiyue.beilindataportability.platform.fabric1201.BlockChangeRecorder1201;

@Mixin(targets = "com.sk89q.worldedit.command.HistoryCommands", remap = false)
abstract class WorldEditHistoryCommandsMixin {
	@Inject(
		method = "undo(Lcom/sk89q/worldedit/extension/platform/Actor;Lcom/sk89q/worldedit/LocalSession;ILjava/lang/String;)V",
		at = @At("HEAD"),
		remap = false
	)
	private void beilinEntryPortability$beginWorldEditUndo(
		Actor actor,
		LocalSession session,
		int times,
		String playerName,
		CallbackInfo ci
	) {
		BlockChangeRecorder1201.pushBulkScope(BlockChangeRecorder1201.beginWorldEditHistory(actor, "WORLDEDIT_UNDO"));
	}

	@Inject(
		method = "undo(Lcom/sk89q/worldedit/extension/platform/Actor;Lcom/sk89q/worldedit/LocalSession;ILjava/lang/String;)V",
		at = @At("RETURN"),
		remap = false
	)
	private void beilinEntryPortability$endWorldEditUndo(
		Actor actor,
		LocalSession session,
		int times,
		String playerName,
		CallbackInfo ci
	) {
		BlockChangeRecorder1201.closeBulkScope();
	}

	@Inject(
		method = "redo(Lcom/sk89q/worldedit/extension/platform/Actor;Lcom/sk89q/worldedit/LocalSession;ILjava/lang/String;)V",
		at = @At("HEAD"),
		remap = false
	)
	private void beilinEntryPortability$beginWorldEditRedo(
		Actor actor,
		LocalSession session,
		int times,
		String playerName,
		CallbackInfo ci
	) {
		BlockChangeRecorder1201.pushBulkScope(BlockChangeRecorder1201.beginWorldEditHistory(actor, "WORLDEDIT_REDO"));
	}

	@Inject(
		method = "redo(Lcom/sk89q/worldedit/extension/platform/Actor;Lcom/sk89q/worldedit/LocalSession;ILjava/lang/String;)V",
		at = @At("RETURN"),
		remap = false
	)
	private void beilinEntryPortability$endWorldEditRedo(
		Actor actor,
		LocalSession session,
		int times,
		String playerName,
		CallbackInfo ci
	) {
		BlockChangeRecorder1201.closeBulkScope();
	}
}
