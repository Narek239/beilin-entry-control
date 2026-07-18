package us.beiyue.beilindataportability.platform.fabric121x.mixin;

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
import us.beiyue.beilindataportability.platform.fabric121x.BlockChangeRecorder121x;

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
		ActorContext.Scope scope = BlockChangeRecorder121x.beginVanillaFill(source, box);
		Integer result = null;
		try {
			result = original.call(source, box, block, mode, filter);
			return result;
		} finally {
			if (result != null) {
				BlockChangeRecorder121x.completeBulkScope(scope, result);
			} else {
				BlockChangeRecorder121x.abortBulkScope(scope);
			}
		}
	}
}
