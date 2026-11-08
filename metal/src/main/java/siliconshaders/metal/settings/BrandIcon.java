package siliconshaders.metal.settings;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import java.io.InputStream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * The menu's icon, read from the mod's own jar. The mod runs without Fabric API, so its assets folder is no resource pack
 * and the texture manager could never find this file by itself: it would show the missing-texture checkerboard.
 */
final class BrandIcon {
    static final String RESOURCE="/assets/siliconshaders/textures/gui/icon.png";
    static final Identifier ID=Identifier.fromNamespaceAndPath("siliconshaders","textures/gui/brand_icon");
    /** Twice the size it is drawn at on a common GUI scale: sharp there, and the linear sampler handles the other scales. */
    static final int SIZE=112;
    private static boolean tried,ready;
    private BrandIcon(){}
    /** Render thread only. Loads once; after a failure the menu simply draws no icon. */
    static boolean ready() {
        if(tried)return ready;
        tried=true;
        try(InputStream in=BrandIcon.class.getResourceAsStream(RESOURCE)) {
            if(in==null)throw new IllegalStateException("not in the jar");
            NativeImage scaled=new NativeImage(SIZE,SIZE,false);
            try(NativeImage source=NativeImage.read(in)) {
                source.resizeSubRectTo(0,0,source.getWidth(),source.getHeight(),scaled);
            } catch(Exception failure) {
                scaled.close();
                throw failure;
            }
            Minecraft.getInstance().getTextureManager().register(ID,new DynamicTexture(()->"SiliconShaders icon",scaled) {{
                this.sampler=RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
            }});
            ready=true;
        } catch(Exception failure) {
            siliconshaders.metal.Log.warn("[SiliconShaders menu] Icon not loaded: "+failure.getMessage());
        }
        return ready;
    }
}
