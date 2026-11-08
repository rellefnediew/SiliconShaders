package siliconshaders.metal.bench;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The .txt next to a result's .json: what ran where, then one line per scene and variant. */
final class BenchReport {
    private BenchReport() {}
    @SuppressWarnings("unchecked")
    static String text(Map<String,Object> out) {
        StringBuilder b=new StringBuilder();
        Map<String,Object> sys=(Map<String,Object>)out.get("system");
        b.append("siliconshaders Shader-Benchmark  ").append(out.get("started")).append("  (").append(out.get("minutes")).append(" min, ").append(out.get("status")).append(")\n");
        b.append("Mac: ").append(sys.get("chip")).append(" (").append(sys.get("model")).append("), ").append(sys.get("gpuCores")).append(" GPU-Kerne, ")
            .append(sys.get("memoryGB")).append(" GB, macOS ").append(sys.get("macos")).append('\n');
        if(sys.containsKey("gpuPolicy"))b.append("GPU-Pfad: ").append(sys.get("gpuPolicy")).append(", Empfänger-Culling ").append(onOff(sys.get("receiverCulling"))).append('\n');
        b.append("Strom: ").append(blank(sys.get("power"),"unbekannt")).append(", Stromsparmodus ").append(onOff(sys.get("lowPower")))
            .append(", Wärme am Anfang ").append(thermal(sys.get("thermal"))).append(", am Ende ").append(thermal(sys.get("thermalEnd"))).append('\n');
        Map<String,Object> options=(Map<String,Object>)out.get("options");
        b.append("siliconshaders ").append(out.get("siliconshaders")).append(", Profil ").append(out.get("profile")).append(", ").append(out.get("secondsPerMeasurement"))
            .append(" s pro Messung, Fenster ").append(options.get("window")).append(", Sichtweite ").append(options.get("renderDistance")).append(", VSync aus, FPS unbegrenzt\n\n");
        b.append(String.format(Locale.ROOT,"%-20s %-32s %8s %8s %8s %8s %8s %9s  %s%n","Szene","Einstellung","FPS","1%-Low","p95 ms","p99 ms","GPU ms","GPU-Last","Wärme"));
        for(Map<String,Object> r:(List<Map<String,Object>>)out.get("results"))
            b.append(String.format(Locale.ROOT,"%-20s %-32s %8.1f %8.1f %8.2f %8.2f %8.2f %8.0f %%  %s%n",r.get("sceneLabel"),r.get("variantLabel"),
                num(r.get("fps")),num(r.get("low1")),num(r.get("p95Ms")),num(r.get("p99Ms")),num(r.get("gpuMs")),num(r.get("gpuBusy"))*100,
                thermal(r.get("thermalStart"))+(r.get("thermalStart").equals(r.get("thermalEnd"))?"":" → "+thermal(r.get("thermalEnd")))));
        b.append("\nGPU-Last ist eine Schätzung aus beobachteten Command-Buffer-Zeiten. Niedrige Werte beweisen keinen CPU-Engpass.\n");
        b.append("Landschaft (Ende) prüft zeitliche Drift; Wärme ist eine mögliche Ursache.\n");
        if(out.get("pathSummary") instanceof List<?> paths&&!paths.isEmpty()) {
            b.append("\nRender-Pfade auf diesem Mac (gleiches Bild, Unterschied gegen das Rauschen der Szene):\n");
            for(Object value:paths)if(value instanceof Map<?,?> p)
                b.append(String.format(Locale.ROOT,"%-20s Shader %-3s %-7s %s (Rauschen %.1f %%)%n",p.get("sceneLabel"),Boolean.TRUE.equals(p.get("shader"))?"an":"aus",p.get("path"),p.get("verdict"),num(p.get("noisePercent"))));
        }
        Object diagnostics=out.get("diagnostics");
        if(diagnostics instanceof List<?> entries)for(Object value:entries)if(value instanceof Map<?,?> entry){
            b.append("\n").append(entry.get("scene")).append(" / ").append(entry.get("variant"));
            if(entry.get("deltaFrameMs") instanceof Number delta)b.append(String.format(Locale.ROOT,"  Δ Standard: %+.3f ms",delta.doubleValue()));
            if(entry.get("notes") instanceof List<?> notes)for(Object note:notes)b.append("\n  ").append(note);b.append('\n');
        }
        if(out.get("measurementNotes") instanceof List<?> notes)for(Object note:notes)b.append("\nMesshinweis: ").append(note).append('\n');
        for(Object w:(List<Object>)out.get("warnings"))b.append("Hinweis: ").append(w).append('\n');
        return b.toString();
    }
    static double num(Object o) { return o instanceof Number n?n.doubleValue():0; }
    static String thermal(Object o) { int i=(int)num(o);return i>=0&&i<BenchSystem.THERMAL.length&&o!=null?BenchSystem.THERMAL[i]:"?"; }
    private static String onOff(Object o) { return o==null?"?":Boolean.TRUE.equals(o)?"an":"aus"; }
    private static String blank(Object o,String fallback) { return o==null||o.toString().isEmpty()?fallback:o.toString(); }
}
