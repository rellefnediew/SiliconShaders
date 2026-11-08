package siliconshaders.metal.mixin.lod;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Far terrain's mixins apply only with -Dsiliconshaders.lod=true (or -Dsiliconshaders.lod.radius), the ground-truth ones only with
 * -Dsiliconshaders.lod.truthRd: without them the game is byte-for-byte what it is without them. Reads the properties itself so nothing of siliconshaders.metal.lod loads before the game does.
 */
public final class LodMixinPlugin implements IMixinConfigPlugin {
	static {
		siliconshaders.metal.Profile.apply(); // before any flag is read
	}

	private static final boolean ENABLED = Boolean.getBoolean("siliconshaders.lod") || System.getProperty("siliconshaders.lod.radius") != null;
	/** -Dsiliconshaders.lod.truthRd=N: ground-truth captures (real chunks past 32): only the LodTruth mixins, with or without far terrain. */
	private static final boolean TRUTH = System.getProperty("siliconshaders.lod.truthRd") != null;
	/** -Dsiliconshaders.lod.fade=instant: Sodium's section fade-in off while far terrain draws (LodSodiumFadeMixin). */
	private static final boolean FADE_INSTANT = "instant".equals(System.getProperty("siliconshaders.lod.fade", ""));

	@Override
	public void onLoad(String mixinPackage) {
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		if (mixinClassName.endsWith(".LodSodiumFadeMixin")) return ENABLED && FADE_INSTANT && net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("sodium");
		return mixinClassName.contains(".LodTruth") ? TRUTH : ENABLED;
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
