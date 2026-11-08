package siliconshaders.metal.mixin.pack;

import com.mojang.blaze3d.systems.ScissorState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * RenderType.prepare() copies the scissor state into every PreparedRenderType, and this class compares by identity, so no two
 * prepared types are ever equal. RenderTypeFeatureRenderer's Group then never merges a draw: each call adds one, with its own
 * BufferBuilder, and searches the whole list first. Thousands of draws a frame for a few dozen items, quadratic in the search.
 * Compared by value, equal types merge as the search intends. Prepared types hold private copies, which never change afterwards.
 */
@Mixin(ScissorState.class)
abstract class ScissorStateValueMixin {
    @Shadow private boolean enabled;
    @Shadow private int x;
    @Shadow private int y;
    @Shadow private int width;
    @Shadow private int height;

    @Override public boolean equals(Object other) {
        if(this==other)return true;
        if(!(other instanceof ScissorState state)||state.enabled()!=enabled)return false;
        return !enabled||state.x()==x&&state.y()==y&&state.width()==width&&state.height()==height;
    }
    @Override public int hashCode() {
        return enabled?31*(31*(31*x+y)+width)+height:0;
    }
}
