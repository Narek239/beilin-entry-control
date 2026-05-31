package us.beiyue.beilindataportability.platform.fabric1192;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public final class PortabilityOptionalMixinPlugin1192 implements IMixinConfigPlugin {
	@Override
	public void onLoad(String mixinPackage) {
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		if (mixinClassName.endsWith(".WorldEditRegionCommandsMixin")
			|| mixinClassName.endsWith(".WorldEditEditSessionMixin")
			|| mixinClassName.endsWith(".WorldEditClipboardCommandsMixin")
			|| mixinClassName.endsWith(".WorldEditHistoryCommandsMixin")) {
			return FabricLoader.getInstance().isModLoaded("worldedit");
		}
		if (mixinClassName.endsWith(".EffortlessStructureBuilderMixin")
			|| mixinClassName.endsWith(".EffortlessBlockStateUpdateOperationMixin")) {
			return isEffortlessLoaded();
		}
		return true;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
	}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}

	private static boolean isEffortlessLoaded() {
		FabricLoader loader = FabricLoader.getInstance();
		return loader.isModLoaded("effortless")
			|| loader.isModLoaded("effortlessbuilding")
			|| loader.isModLoaded("effortless_building")
			|| loader.isModLoaded("effortless_structure");
	}
}
