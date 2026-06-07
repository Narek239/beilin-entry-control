package us.beiyue.beilindataportability.platform.fabric1192.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.extension.platform.Actor;
import org.spongepowered.asm.mixin.Mixin;
import us.beiyue.beilindataportability.common.ActorContext;
import us.beiyue.beilindataportability.platform.fabric1192.BlockChangeRecorder1192;

@Mixin(targets = "com.sk89q.worldedit.command.HistoryCommands", remap = false)
abstract class WorldEditHistoryCommandsMixin {
	@WrapMethod(
		method = "undo(Lcom/sk89q/worldedit/extension/platform/Actor;Lcom/sk89q/worldedit/LocalSession;ILjava/lang/String;)V",
		remap = false
	)
	private void beilinEntryPortability$wrapWorldEditUndo(
		Actor actor,
		LocalSession session,
		int times,
		String playerName,
		Operation<Void> original
	) {
		ActorContext.Scope scope = BlockChangeRecorder1192.beginWorldEditHistory(actor, "WORLDEDIT_UNDO");
		boolean completed = false;
		try {
			original.call(actor, session, times, playerName);
			completed = true;
		} finally {
			if (completed) {
				BlockChangeRecorder1192.completeBulkScope(scope);
			} else {
				BlockChangeRecorder1192.abortBulkScope(scope);
			}
		}
	}

	@WrapMethod(
		method = "redo(Lcom/sk89q/worldedit/extension/platform/Actor;Lcom/sk89q/worldedit/LocalSession;ILjava/lang/String;)V",
		remap = false
	)
	private void beilinEntryPortability$wrapWorldEditRedo(
		Actor actor,
		LocalSession session,
		int times,
		String playerName,
		Operation<Void> original
	) {
		ActorContext.Scope scope = BlockChangeRecorder1192.beginWorldEditHistory(actor, "WORLDEDIT_REDO");
		boolean completed = false;
		try {
			original.call(actor, session, times, playerName);
			completed = true;
		} finally {
			if (completed) {
				BlockChangeRecorder1192.completeBulkScope(scope);
			} else {
				BlockChangeRecorder1192.abortBulkScope(scope);
			}
		}
	}
}
