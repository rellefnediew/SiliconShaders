package siliconshaders.metal.mixin.own;

import java.util.List;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Our own near terrain's mixins apply only with -Dsiliconshaders.own=true and without Sodium (which replaces the same renderer): without
 * the switch the game is byte-for-byte what it is without them. Reads the property itself so nothing of siliconshaders.metal.own loads
 * before the game does.
 */
public final class OwnMixinPlugin implements IMixinConfigPlugin {
	static {
		siliconshaders.metal.Profile.apply(); // before any flag is read
	}

	private static final boolean ENABLED = Boolean.getBoolean("siliconshaders.own") && !FabricLoader.getInstance().isModLoaded("sodium");

	@Override
	public void onLoad(String mixinPackage) {
		if (Boolean.getBoolean("siliconshaders.own") && !ENABLED) siliconshaders.metal.Log.info("siliconshaders-own: Sodium is loaded, our near terrain stays off");
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		if (mixinClassName.endsWith(".OwnAnimFlushMixin") || mixinClassName.endsWith(".OwnAnimFlushFrameMixin")) return ENABLED && Boolean.getBoolean("siliconshaders.own.int.animOnePass");
		if (mixinClassName.endsWith(".OwnAtlasWriteMixin")) return ENABLED && Boolean.getBoolean("siliconshaders.own.int.atlasWrite");
		if (mixinClassName.endsWith(".OwnAnimCopyMixin")) return ENABLED && (Integer.getInteger("siliconshaders.own.int.animHash", 0) > 0 || Integer.getInteger("siliconshaders.own.int.animVerify", 0) > 0 || Integer.getInteger("siliconshaders.own.int.animVerifyCopy", 0) > 0 || Boolean.getBoolean("siliconshaders.own.int.animOnePass"));
		if (mixinClassName.endsWith(".FrameLogNoAnimMixin")) return false;  // (measurement only, not exact)
		if (mixinClassName.contains(".FrameLog")) return ENABLED && Boolean.getBoolean("siliconshaders.own.int.frameLog");  // (measurement only)
		return ENABLED;
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
}
