package us.beiyue.beilinentrycontrol.mixin;

import org.spongepowered.asm.mixin.Mixin;

/**
 * Placeholder mixin targeting class_3248 to keep config stable.
 * All login gating is done via reflection in LoginHandler; this mixin intentionally does nothing.
 */
@Mixin(targets = "net.minecraft.class_3248")
public class ServerLoginNetworkHandlerMixin {
}
