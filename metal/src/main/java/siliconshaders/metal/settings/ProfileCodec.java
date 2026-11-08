package siliconshaders.metal.settings;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/**
 * Self-contained clipboard codes. Bounded decoding, explicit version and checksum; no network service. Codes already handed
 * out stay readable for good: a new format adds a prefix and keeps this one. GraphicsMenuChecks holds a frozen code.
 */
public final class ProfileCodec {
    public static final int MAX_CODE=32768,MAX_JSON=65536;
    private static final Gson JSON=new Gson();
    private ProfileCodec(){}
    public static String encode(GraphicsProfile profile){
        JsonObject data=new JsonObject();data.addProperty("version",1);data.addProperty("name",profile.name());data.addProperty("scope",profile.scope().name());
        data.add("values",JSON.toJsonTree(profile.values()));
        byte[] plain=JSON.toJson(data).getBytes(StandardCharsets.UTF_8);
        if(plain.length>MAX_JSON)throw new IllegalArgumentException("code.large");
        Deflater deflater=new Deflater(Deflater.BEST_COMPRESSION);ByteArrayOutputStream output=new ByteArrayOutputStream();
        try{deflater.setInput(plain);deflater.finish();byte[] block=new byte[1024];while(!deflater.finished()){int count=deflater.deflate(block);output.write(block,0,count);}}finally{deflater.end();}
        byte[] compressed=output.toByteArray();CRC32 checksum=new CRC32();checksum.update(compressed);
        String code="SILICONSHADERS1:"+Base64.getUrlEncoder().withoutPadding().encodeToString(compressed)+"."+String.format(Locale.ROOT,"%08x",checksum.getValue());
        if(code.length()>MAX_CODE)throw new IllegalArgumentException("code.large");return code;
    }
    public static GraphicsProfile decode(String input){
        if(input==null||input.isBlank())throw new IllegalArgumentException("code.empty");
        if(input.length()>MAX_CODE)throw new IllegalArgumentException("code.large");
        String code=input.replaceAll("\\s","");
        String prefix=code.startsWith("SILICONSHADERS1:")?"SILICONSHADERS1:":code.startsWith("MCOPT1:")?"MCOPT1:":null;
        if(prefix==null)throw new IllegalArgumentException(code.startsWith("SILICONSHADERS")||code.startsWith("MCOPT")?"code.version":"code.invalid");
        int split=code.lastIndexOf('.');if(split<=prefix.length()||!code.substring(split+1).matches("[0-9a-fA-F]{8}"))throw new IllegalArgumentException("code.invalid");
        byte[] compressed;try{compressed=Base64.getUrlDecoder().decode(code.substring(prefix.length(),split));}catch(IllegalArgumentException bad){throw new IllegalArgumentException("code.invalid");}
        CRC32 checksum=new CRC32();checksum.update(compressed);
        if(checksum.getValue()!=Long.parseUnsignedLong(code.substring(split+1),16))throw new IllegalArgumentException("code.checksum");
        Inflater inflater=new Inflater();ByteArrayOutputStream output=new ByteArrayOutputStream();
        try{
            inflater.setInput(compressed);byte[] block=new byte[1024];
            while(!inflater.finished()){
                int count=inflater.inflate(block);
                if(count==0)throw new IllegalArgumentException("code.invalid");
                if(output.size()+count>MAX_JSON)throw new IllegalArgumentException("code.large");output.write(block,0,count);
            }
            if(inflater.getRemaining()!=0)throw new IllegalArgumentException("code.invalid");
        }catch(DataFormatException bad){throw new IllegalArgumentException("code.invalid");}finally{inflater.end();}
        try{
            String raw=output.toString(StandardCharsets.UTF_8);checkNesting(raw);JsonElement parsed=JsonParser.parseString(raw);if(!parsed.isJsonObject())throw new IllegalArgumentException();
            JsonObject data=parsed.getAsJsonObject();
            if(!data.has("version")||!data.get("version").isJsonPrimitive()||!data.get("version").getAsJsonPrimitive().isNumber()||data.get("version").getAsBigDecimal().compareTo(java.math.BigDecimal.ONE)!=0)throw new IllegalArgumentException("code.version");
            String name=string(data,"name"),scope=string(data,"scope");JsonObject entries=data.getAsJsonObject("values");
            if(entries==null||entries.size()>2048)throw new IllegalArgumentException();Map<String,Float> values=new TreeMap<>();
            for(var entry:entries.entrySet()){
                if(!entry.getValue().isJsonPrimitive()||!entry.getValue().getAsJsonPrimitive().isNumber())throw new IllegalArgumentException();
                values.put(entry.getKey(),entry.getValue().getAsFloat());
            }
            return new GraphicsProfile(UUID.randomUUID().toString(),name,GraphicsProfile.Scope.valueOf(scope),values);
        }catch(RuntimeException bad){if("code.version".equals(bad.getMessage()))throw bad;throw new IllegalArgumentException("code.invalid");}
    }
    static void checkNesting(String raw){
        boolean quoted=false,escaped=false;int depth=0;
        for(int i=0;i<raw.length();i++){char c=raw.charAt(i);if(quoted){if(escaped)escaped=false;else if(c=='\\')escaped=true;else if(c=='"')quoted=false;}else if(c=='"')quoted=true;else if(c=='{'||c=='['){if(++depth>4)throw new IllegalArgumentException("code.invalid");}else if(c=='}'||c==']')depth--;}
    }
    private static String string(JsonObject object,String key){JsonElement value=object.get(key);if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString())throw new IllegalArgumentException();return value.getAsString();}
}
