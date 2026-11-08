package siliconshaders.metal.pack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class ShaderChecks {
    public static void main(String[] args) throws Exception {
        siliconshaders.metal.sun.SunChecks.run();
        Path temp=Files.createTempDirectory("siliconshaders-shader-check-");
        try {
            require(ShaderTranslations.withFallback(java.util.Map.of(),java.util.List.of("en_us")).get("siliconshaders.shaders.title").equals("Metal Shaders"),"Standalone English translations missing");
            require(ShaderTranslations.withFallback(java.util.Map.of(),java.util.List.of("en_us","de_de")).get("siliconshaders.shaders.title").equals("Metal-Shader"),"German translations missing");
            require(ShaderTranslations.withFallback(java.util.Map.of("siliconshaders.shaders.title","Custom"),java.util.List.of("de_de")).get("siliconshaders.shaders.title").equals("Custom"),"Resource-pack translation did not take precedence");
            require(!ShaderSettings.load(temp).enabled(),"Shaders enabled by default");
            for(String name:new String[] {"../outside.zip","/absolute.zip","..\\outside.zip","pack.jar"}) {
                try { ShaderSettings.packPath(temp,name); throw new AssertionError("Invalid pack path accepted"); }
                catch(IOException expected) { }
            }
            new ShaderSettings(true,PackProgram.DIAGNOSTIC).save(temp);
            require(ShaderSettings.load(temp).equals(new ShaderSettings(true,PackProgram.DIAGNOSTIC)),"Settings did not persist");
            Files.writeString(temp.resolve("config/siliconshaders-shaders.json"),"{invalid");
            try { ShaderSettings.load(temp); throw new AssertionError("Corrupt settings accepted"); } catch(IOException expected) { }
            Files.delete(temp.resolve("config/siliconshaders-shaders.json"));

            CountDownLatch started=new CountDownLatch(1),finish=new CountDownLatch(1);
            ShaderManager manager=new ShaderManager(temp,(dir,name,reload) -> {
                if(name.equals("first.zip")) { started.countDown(); if(!finish.await(5,TimeUnit.SECONDS)) throw new AssertionError("Test compiler timeout"); throw new IOException("stale error"); }
                return new PackProgram(name,"test-only");
            });
            manager.select("first.zip"); manager.enabled(true);
            require(started.await(5,TimeUnit.SECONDS),"Compiler did not start");
            manager.select("second.zip"); finish.countDown();
            await(() -> manager.snapshot().phase()==ShaderManager.Phase.READY);
            require(manager.snapshot().pack().equals("second.zip") && manager.snapshot().program().key().equals("second.zip"),"Stale result replaced selected pack");
            manager.enabled(false);
            require(!manager.snapshot().enabled() && manager.snapshot().program()==null,"Disable kept an active program");
            require(!ShaderSettings.load(temp).enabled(),"Disable did not persist");

            PackProgram diagnostic=PackProgram.compile(temp,PackProgram.DIAGNOSTIC,false);
            PackProgram hit=PackProgram.compile(temp,PackProgram.DIAGNOSTIC,false);
            require(hit.equals(diagnostic),"Cache did not round-trip");
            Files.writeString(temp.resolve("siliconshaders-pack-cache/final/"+diagnostic.key()+".json"),"invalid cache");
            require(PackProgram.compile(temp,PackProgram.DIAGNOSTIC,false).equals(diagnostic),"Corrupt cache was not rebuilt");
            // An imported Metal program must load without GLSL sources and retain its original resource slots.
            var imported = new com.google.gson.Gson().fromJson(diagnostic.json(),com.google.gson.JsonObject.class);
            imported.addProperty("siliconshadersMetalFormat",1);
            imported.addProperty("scope","final-image-only");
            Files.createDirectories(temp.resolve("shaderpacks"));
            Path metalZip=temp.resolve("shaderpacks/imported-metal.zip");
            try(var zip=new java.util.zip.ZipOutputStream(Files.newOutputStream(metalZip))) {
                zip.putNextEntry(new java.util.zip.ZipEntry("shaders/siliconshaders.json"));
                zip.write(imported.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)); zip.closeEntry();
            }
            require(com.google.gson.JsonParser.parseString(PackProgram.compile(temp,"imported-metal.zip",false).json()).equals(imported),"Pretranslated shader did not preserve its source and bindings");
            if(args.length==2) {
                Path source=Path.of(args[0]),output=Path.of(args[1]); Files.createDirectories(output); Files.createDirectories(temp.resolve("shaderpacks"));
                Files.copy(source,temp.resolve("shaderpacks").resolve(source.getFileName()));
                Path saved=source.resolveSibling(source.getFileName()+".txt");
                if(Files.exists(saved)) Files.copy(saved,temp.resolve("shaderpacks").resolve(saved.getFileName()));
                PackProgram real=PackProgram.compile(temp,source.getFileName().toString(),false);
                Files.writeString(output.resolve("complementary-runtime.json"),real.json());
                Files.writeString(output.resolve("diagnostic-runtime.json"),diagnostic.json());
                System.out.println("PASS: real pack compiled with its noise image for the in-game runtime");
            }
            System.out.println("PASS: translations, settings persistence, invalid paths, corrupt settings, stale compile error, pack switching, disable, cache round-trip, corrupt cache rebuild");
        } finally {
            try(var files=Files.walk(temp)) { for(Path file:files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(file); }
        }
    }
    private static void require(boolean yes,String text) { if(!yes) throw new AssertionError(text); }
    private static void await(java.util.function.BooleanSupplier done) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!done.getAsBoolean()) { if(System.nanoTime()>until) throw new AssertionError("State transition timed out"); Thread.sleep(10); }
    }
}
