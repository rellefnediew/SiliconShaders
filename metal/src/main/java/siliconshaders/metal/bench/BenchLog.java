package siliconshaders.metal.bench;

import com.google.gson.GsonBuilder;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A single shareable diagnostic log, with bounded game-log context and no raw frame-array duplication. */
final class BenchLog {
    @SuppressWarnings("unchecked")
    static String text(Map<String,Object> report,String progress,Path gameLog){
        StringBuilder out=new StringBuilder("SiliconShaders Diagnose – Ablauf\n").append(progress).append("\n").append(BenchReport.text(report));
        var json=new GsonBuilder().setPrettyPrinting().serializeNulls().create();
        out.append("\nMessdetails (Roh-Framezeiten stehen in der JSON-Datei)\n");
        for(var row:(List<Map<String,Object>>)report.get("results")){
            var details=new LinkedHashMap<>(row);details.remove("frameTimesMs");out.append(json.toJson(details)).append('\n');
        }
        out.append("\nMinecraft-Kontext: logs/latest.log (maximal letzte 2 MiB)\n");
        try(var channel=FileChannel.open(gameLog,StandardOpenOption.READ)){
            long start=Math.max(0,channel.size()-2*1024*1024);channel.position(start);
            ByteBuffer buffer=ByteBuffer.allocate((int)Math.min(2*1024*1024,channel.size()-start));while(buffer.hasRemaining()&&channel.read(buffer)>0){}
            buffer.flip();out.append(StandardCharsets.UTF_8.decode(buffer));
        }catch(Exception failure){out.append("Minecraft-Log nicht verfügbar: ").append(failure.getMessage());}
        return out.toString();
    }
}
