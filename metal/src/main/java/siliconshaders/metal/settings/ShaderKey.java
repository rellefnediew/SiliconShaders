package siliconshaders.metal.settings;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import siliconshaders.metal.sun.SunSettings;

/** The shader's on and off as a key in Minecraft's own controls; the same switch as the menu's, and kept the same way. */
public final class ShaderKey {
    public static final KeyMapping KEY=new KeyMapping("key.siliconshaders.toggle",InputConstants.KEY_K,KeyMapping.Category.register(Identifier.fromNamespaceAndPath("siliconshaders","shaders")));
    private ShaderKey(){}
    public static void handle(Minecraft minecraft){
        while(KEY.consumeClick()){
            if(siliconshaders.metal.bench.ShaderBench.active())continue; // the benchmark sets the shader itself
            var settings=SunSettings.get();
            settings.enabled(!settings.state().enabled());
            // What is true now: a settings file that cannot be written leaves the switch as it was.
            if(minecraft.player!=null)minecraft.player.sendOverlayMessage(Component.translatable(settings.state().enabled()?"siliconshaders.shaders.key.on":"siliconshaders.shaders.key.off"));
        }
    }
}
