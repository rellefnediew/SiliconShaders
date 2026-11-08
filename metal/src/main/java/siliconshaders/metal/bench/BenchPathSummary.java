package siliconshaders.metal.bench;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The path profile's answer per scene: which side of each render path pair was faster here, and whether the difference is more than the
 * scene's own noise (its reference measured at the start and again at the end). A pair whose override didn't take effect says so instead
 * of naming a winner: a measurement of the wrong path must not read as a result.
 */
final class BenchPathSummary {
    private BenchPathSummary() {}
    /** Below this a difference is never called a win, however quiet the repeat was. */
    static final double MIN_DIFFERENCE=.02;
    private record Pair(String path,String a,String aLabel,String b,String bLabel) {}
    private static final List<Pair> PAIRS=List.of(new Pair("uocc","uocc-aus","aus","uocc-an","an"),new Pair("vgroup","vgroup-aus","aus","vgroup-2","2"),
        new Pair("a1","a1-aus","aus","a1-30","30"),new Pair("grid","grid-aus","aus","grid-an","an"));

    static List<Map<String,Object>> analyze(List<Map<String,Object>> rows) {
        List<Map<String,Object>> out=new ArrayList<>();
        List<String> scenes=new ArrayList<>();
        for(var row:rows){String scene=String.valueOf(row.get("scene"));if(!scenes.contains(scene))scenes.add(scene);}
        for(String scene:scenes)for(boolean shader:new boolean[]{true,false}) {
            String prefix=shader?"pfad-":"aus-",reference=shader?"standard":"vanilla";
            Map<String,Object> first=find(rows,scene,reference),repeat=find(rows,scene,reference+"-ende");
            double noise=first!=null&&repeat!=null&&ms(first)>0?Math.abs(ms(repeat)-ms(first))/ms(first):0;
            for(Pair pair:PAIRS) {
                Map<String,Object> a=find(rows,scene,prefix+pair.a()),b=find(rows,scene,prefix+pair.b());
                if(a==null||b==null)continue;
                Map<String,Object> entry=new LinkedHashMap<>();
                entry.put("scene",scene);entry.put("sceneLabel",a.get("sceneLabel"));entry.put("shader",shader);entry.put("path",pair.path());
                entry.put(pair.aLabel()+"Ms",ms(a));entry.put(pair.bLabel()+"Ms",ms(b));entry.put("noisePercent",noise*100);entry.put("framebuffer",a.get("framebuffer"));
                String ineffective=ineffective(a);if(ineffective==null)ineffective=ineffective(b);
                double slow=Math.max(ms(a),ms(b)),fast=Math.min(ms(a),ms(b)),gain=slow>0?(slow-fast)/slow:0;
                if(ineffective!=null){entry.put("winner",null);entry.put("verdict","nicht wirksam: "+ineffective);}
                else if(Boolean.FALSE.equals(a.get("measurementValid"))||Boolean.FALSE.equals(b.get("measurementValid"))||slow<=0){entry.put("winner",null);entry.put("verdict","Messung ungültig");}
                else if(gain<Math.max(MIN_DIFFERENCE,noise)){entry.put("winner",null);entry.put("gainPercent",gain*100);entry.put("verdict","kein klarer Unterschied");}
                else{String winner=ms(a)<ms(b)?pair.aLabel():pair.bLabel();entry.put("winner",winner);entry.put("gainPercent",gain*100);
                    entry.put("verdict",String.format(java.util.Locale.ROOT,"%s schneller, %.1f %%",winner,gain*100));}
                out.add(entry);
            }
        }
        return out;
    }

    /** Why a row's override didn't show in the frames it drew, or null when it did or the row has none. */
    static String ineffective(Map<String,Object> row) {
        if(!(row.get("pathOverrides") instanceof Map<?,?> path)||path.isEmpty())return null;
        if(!(row.get("pathEffective") instanceof Map<?,?> seen))return "keine Zähler";
        for(var e:path.entrySet()) {
            String value=String.valueOf(e.getValue());
            String problem=switch(String.valueOf(e.getKey())) {
                case "frag.uocc" -> Boolean.parseBoolean(value)==(num(seen.get("uoccShare"))>.5)?null:"Occlusion";
                case "frag.uoccSizeSwitch" -> Boolean.parseBoolean(value)||num(seen.get("uoccShare"))>.5?null:"Occlusion";
                case "tl.a1Split" -> Integer.parseInt(value)>0==(num(seen.get("a1SplitShare"))>.5)?null:"A1-Split";
                case "frag.vGroupSh" -> Integer.parseInt(value)>0==(num(seen.get("groupedDraws"))>0)?null:"Gruppierung";
                case "sun.receiverGrid" -> Boolean.parseBoolean(value)==(num(seen.get("receiverGridShare"))>.5)?null:"Empfänger-Grid";
                default -> null;
            };
            if(problem!=null)return problem;
        }
        return null;
    }
    private static Map<String,Object> find(List<Map<String,Object>> rows,String scene,String variant) {
        for(var r:rows)if(scene.equals(r.get("scene"))&&variant.equals(r.get("variant")))return r;
        return null;
    }
    private static double ms(Map<String,Object> row) { return num(row.get("averageFrameMs")); }
    private static double num(Object n) { return n instanceof Number v?v.doubleValue():0; }
}
