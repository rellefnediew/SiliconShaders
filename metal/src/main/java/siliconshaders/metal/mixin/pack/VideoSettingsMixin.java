package siliconshaders.metal.mixin.pack;

import siliconshaders.metal.fast.SmartLeaves;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VideoSettingsScreen.class)
abstract class VideoSettingsMixin extends OptionsSubScreen {
    VideoSettingsMixin(Screen parent,Options options,Component title) { super(parent,options,title); }
    @Inject(method="addOptions",at=@At("HEAD"))
    private void siliconshaders$shaderMenu(CallbackInfo ci) {
        list.addBig(Button.builder(Component.translatable("siliconshaders.shaders.button"),b -> minecraft.gui.setScreen(new siliconshaders.metal.settings.GraphicsScreen(this))).width(310).build());
        if(SmartLeaves.ON) {
            Button leaves=Button.builder(smartLeaves(),b -> { SmartLeaves.get().next();b.setMessage(smartLeaves()); }).width(310)
                .tooltip(Tooltip.create(Component.translatable("siliconshaders.smartLeaves.help"))).build();
            list.addBig(leaves);
        }
    }
    private static Component smartLeaves() {
        return Component.translatable("siliconshaders.smartLeaves",Component.translatable("siliconshaders.smartLeaves."+SmartLeaves.get().mode().name().toLowerCase(java.util.Locale.ROOT)));
    }
}
