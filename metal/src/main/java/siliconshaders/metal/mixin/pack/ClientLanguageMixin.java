package siliconshaders.metal.mixin.pack;

import java.util.List;
import java.util.Map;
import siliconshaders.metal.pack.ShaderTranslations;
import net.minecraft.client.resources.language.ClientLanguage;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ClientLanguage.class)
abstract class ClientLanguageMixin {
    @Redirect(method="loadFrom",at=@At(value="INVOKE",target="Ljava/util/Map;copyOf(Ljava/util/Map;)Ljava/util/Map;"))
    private static Map<String,String> siliconshaders$shaderTranslations(Map<String,String> existing,ResourceManager resources,List<String> languages,boolean rightToLeft) {
        return ShaderTranslations.withFallback(existing,languages);
    }
}
