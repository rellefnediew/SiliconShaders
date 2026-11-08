package siliconshaders.metal.bench;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

/** After a benchmark started from the menu: one line per measurement, and where the files are. */
final class BenchResultScreen extends OptionsSubScreen {
    private final List<Map<String,Object>> results;private final Path file;private final String problem;
    BenchResultScreen(Screen parent,List<Map<String,Object>> results,Path file,String problem) {
        super(parent,Minecraft.getInstance().options,Component.translatable("siliconshaders.bench.result.title"));
        this.results=List.copyOf(results);this.file=file;this.problem=problem;
    }
    @Override protected void addOptions() {
        if(problem!=null)list.addHeader(Component.literal(problem));
        String scene=null;
        for(Map<String,Object> r:results) {
            if(!r.get("scene").equals(scene)) { scene=(String)r.get("scene");list.addHeader(Component.translatableWithFallback("siliconshaders.bench.scene."+scene,(String)r.get("sceneLabel"))); }
            Component line=Component.translatable("siliconshaders.bench.result.line",Component.translatableWithFallback("siliconshaders.bench.variant."+r.get("variant"),(String)r.get("variantLabel")),
                String.format(Locale.ROOT,"%.0f",BenchReport.num(r.get("fps"))),String.format(Locale.ROOT,"%.0f",BenchReport.num(r.get("low1"))),
                String.format(Locale.ROOT,"%.2f",BenchReport.num(r.get("gpuMs"))),String.format(Locale.ROOT,"%.0f",BenchReport.num(r.get("gpuBusy"))*100));
            list.addBig(new StringWidget(310,20,line,font));
        }
        if(file!=null) {
            list.addBig(new StringWidget(310,20,Component.translatable("siliconshaders.bench.result.saved",file.getFileName().toString()),font));
            list.addBig(Button.builder(Component.translatable("siliconshaders.bench.result.html"),b->{try{new ProcessBuilder("open",file.resolveSibling(file.getFileName().toString().replace(".json",".html")).toString()).start();}catch(java.io.IOException ignored){}}).width(310).build());
            list.addBig(Button.builder(Component.translatable("siliconshaders.bench.result.open"),b->{try{new ProcessBuilder("open",file.getParent().toString()).start();}catch(java.io.IOException ignored){}}).width(310).build());
        }
    }
}
