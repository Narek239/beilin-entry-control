package us.beiyue.beilindataportability.platform.fabric1192.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.blocks.BlockInput;
import net.minecraft.server.commands.FillCommand;
import net.minecraft.world.level.block.state.pattern.BlockInWorld;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Coerce;
import us.beiyue.beilindataportability.common.ActorContext;
import us.beiyue.beilindataportability.platform.fabric1192.BlockChangeRecorder1192;

import java.util.function.Predicate;

@Mixin(FillCommand.class)
abstract class VanillaFillCommandMixin {
	@WrapMethod(method = "fillBlocks")
	private static int beilinEntryPortability$wrapFillBlocks(
		CommandSourceStack source,
		BoundingBox box,
		BlockInput block,
		@Coerce Object mode,
		Predicate<BlockInWorld> filter,
		Operation<Integer> original
	) {
		ActorContext.Scope scope = BlockChangeRecorder1192.beginVanillaFill(source, box);
		Integer result = null;
		try {
			result = original.call(source, box, block, mode, filter);
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
