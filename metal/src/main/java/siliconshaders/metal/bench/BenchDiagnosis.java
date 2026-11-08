package siliconshaders.metal.bench;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Evidence-based comparisons; a slower effect alone is not evidence of a bug. */
final class BenchDiagnosis {
    private BenchDiagnosis(){}
    static List<Map<String,Object>> analyze(List<Map<String,Object>> rows){
        List<Map<String,Object>> out=new ArrayList<>();
        for(var row:rows){
            String scene=String.valueOf(row.get("scene")),variant=String.valueOf(row.get("variant"));
            Map<String,Object> entry=new LinkedHashMap<>();entry.put("scene",scene);entry.put("variant",variant);
            List<String> notes=new ArrayList<>();
            if(Boolean.FALSE.equals(row.get("measurementValid")))notes.add("Messung ungültig: Auflösung geändert oder keine Frames.");
            if(Boolean.TRUE.equals(row.get("warmupTimedOut")))notes.add("Aufwärmen nach 20 Sekunden ohne ruhige Sekunde beendet; Shader-Aufbau oder langsame Frames können noch hineinwirken.");
            if(Boolean.TRUE.equals(row.get("lostFocus")))notes.add("Fenster während der Messung nicht durchgehend aktiv.");
            if(Boolean.TRUE.equals(row.get("vsync")))notes.add("VSync ist aktiv; Vergleich ungültig.");
            if(Boolean.TRUE.equals(row.get("lowPower")))notes.add("Stromsparmodus aktiv.");
            if(number(row,"thermalEnd")>=2)notes.add("Erhöhter Temperaturzustand; Leistungsbegrenzung möglich.");
            if(Boolean.FALSE.equals(row.get("allSectionsRendered")))notes.add("Chunk-Aufbau während der Messung noch nicht abgeschlossen.");
            if(number(row,"frames")<120)notes.add("Wenige Frames; Perzentile und kurze GPU-Stichproben sind unsicher.");
            if(number(row,"commandBuffers")==0)notes.add("Keine gültigen GPU-Command-Buffer-Zeiten verfügbar.");
            Object detail=row.get("gpuDetail");if(detail instanceof Map<?,?> m&&m.get("stages") instanceof Map<?,?> stages&&stages.isEmpty())notes.add("Keine GPU-Pass-Zeitstempel verfügbar; Gesamt-GPU-Zeit separat beachten.");
            if(row.get("tuningSettings") instanceof Map<?,?> tune&&num(tune.get("renderScale"))>0&&number(row,"upscaleFrames")==0)
                notes.add("MetalFX angefordert, aber kein Upscaling ausgeführt: Unterstützung oder Fallback prüfen.");
            if(row.get("renderPath") instanceof Map<?,?> path&&Boolean.TRUE.equals(path.get("failedUntilRestart")))notes.add("Renderziel/MetalFX nach Fehler bis zum Neustart deaktiviert.");
            if(row.get("cameraSettings") instanceof Map<?,?> cam&&num(cam.get("bloom"))>.5&&num(cam.get("bloomVariant"))>.5&&number(row,"hdrFrames")==0)
                notes.add("HDR-Bloom angefordert, aber kein HDR-Weltziel benutzt.");
            if(scene.equals("regen")&&number(row,"rainLevel")<.9)notes.add("Regen noch nicht vollständig eingeblendet.");
            String ineffective=BenchPathSummary.ineffective(row);if(ineffective!=null)notes.add("Render-Pfad nicht wirksam ("+ineffective+"): die Messung zeigt nicht den angeforderten Pfad.");
            var reference=reference(rows,scene.equals("land-ende")?"land":scene,String.valueOf(row.getOrDefault("referenceVariant","standard")));
            if(reference!=null&&reference!=row&&number(reference,"averageFrameMs")>0){
                double base=number(reference,"averageFrameMs"),ms=number(row,"averageFrameMs");
                entry.put("reference",reference.get("variant"));entry.put("deltaFrameMs",ms-base);entry.put("frameTimeRatio",ms/base);
                if(ms/base>2&&ms-base>2)notes.add("Mehr als doppelte Framezeit gegenüber Standard; Kandidat für gezieltes Profiling, kein Bugnachweis.");
                if((scene.equals("land-ende")||variant.endsWith("-ende"))&&Math.abs(ms/base-1)>.15)notes.add("Referenz driftet um mehr als 15 %; Reihenfolge, Wärme und Hintergrundlast prüfen.");
            }
            entry.put("notes",notes);out.add(entry);
        }
        return out;
    }
    private static Map<String,Object> reference(List<Map<String,Object>> rows,String scene,String variant){for(var r:rows)if(scene.equals(r.get("scene"))&&variant.equals(r.get("variant"))&&!Boolean.FALSE.equals(r.get("measurementValid")))return r;return null;}
    static double number(Map<String,Object> m,String key){return num(m.get(key));}
    private static double num(Object n){return n instanceof Number v?v.doubleValue():0;}
}
