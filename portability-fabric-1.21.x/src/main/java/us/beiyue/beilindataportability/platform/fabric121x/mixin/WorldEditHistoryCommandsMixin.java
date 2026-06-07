package us.beiyue.beilindataportability.platform.fabric121x.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.extension.platform.Actor;
import org.spongepowered.asm.mixin.Mixin;
import us.beiyue.beilindataportability.common.ActorContext;
import us.beiyue.beilindataportability.platform.fabric121x.BlockChangeRecorder121x;

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
		ActorContext.Scope scope = BlockChangeRecorder121x.beginWorldEditHistory(actor, "WORLDEDIT_UNDO");
		boolean completed = false;
		try {
			original.call(actor, session, times, playerName);
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
		ActorContext.Scope scope = BlockChangeRecorder121x.beginWorldEditHistory(actor, "WORLDEDIT_REDO");
		boolean completed = false;
		try {
			original.call(actor, session, times, playerName);
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
