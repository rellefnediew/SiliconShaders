package siliconshaders.metal.bench;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Offline report and multi-Mac comparison. Gson's HTML escaping keeps embedded JSON inert. */
final class BenchHtml {
    static String render(Map<String,Object> data) throws IOException {
        try(var input=BenchHtml.class.getResourceAsStream("/siliconshaders/bench/report.html")){
            if(input==null)throw new IOException("Benchmark report template missing");
            return new String(input.readAllBytes(),StandardCharsets.UTF_8).replace("__SILICONSHADERS_DATA__",new Gson().toJson(data));
        }
    }
}
