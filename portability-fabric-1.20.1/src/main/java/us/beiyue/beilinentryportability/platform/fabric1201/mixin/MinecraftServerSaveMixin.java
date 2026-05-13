package us.beiyue.beilinentryportability.platform.fabric1201.mixin;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import us.beiyue.beilinentryportability.platform.fabric1201.BeilinEntryPortability1201;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerSaveMixin {
	@Inject(method = "saveEverything", at = @At("HEAD"))
	private void beilinEntryPortability$flushBeforeSave(boolean suppressLogs, boolean flush, boolean force, CallbackInfoReturnable<Boolean> cir) {
		BeilinEntryPortability1201.flushIndexForWorldSave();
	}

	@Inject(method = "saveEverything", at = @At("RETURN"))
	private void beilinEntryPortability$checkpointAfterSave(boolean suppressLogs, boolean flush, boolean force, CallbackInfoReturnable<Boolean> cir) {
		BeilinEntryPortability1201.checkpointIndexAfterWorldSave();
	}
}
