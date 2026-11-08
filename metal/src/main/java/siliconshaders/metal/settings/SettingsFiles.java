package siliconshaders.metal.settings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Prepare all files before publication, and roll back completed replacements if any later file fails. */
public final class SettingsFiles {
    private SettingsFiles(){}
    public static void write(Map<Path,String> documents) throws IOException {write(documents,(from,to)->Files.move(from,to,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING));}
    @FunctionalInterface interface Move {void apply(Path from,Path to) throws IOException;}
    static void write(Map<Path,String> documents,Move move) throws IOException {
        Map<Path,Path> staged=new LinkedHashMap<>();Map<Path,byte[]> before=new LinkedHashMap<>();List<Path> published=new ArrayList<>();
        try{
            for(var entry:documents.entrySet()){
                Path path=entry.getKey();Files.createDirectories(path.getParent());
                before.put(path,Files.exists(path)?Files.readAllBytes(path):null);
                Path temporary=Files.createTempFile(path.getParent(),"siliconshaders-menu-",".tmp");staged.put(path,temporary);Files.writeString(temporary,entry.getValue(),StandardCharsets.UTF_8);
            }
            for(var entry:staged.entrySet()){move.apply(entry.getValue(),entry.getKey());published.add(entry.getKey());}
        }catch(IOException failure){
            for(Path path:published)try{
                byte[] bytes=before.get(path);if(bytes==null)Files.deleteIfExists(path);else{
                    Path temporary=Files.createTempFile(path.getParent(),"siliconshaders-restore-",".tmp");
                    try{Files.write(temporary,bytes);Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temporary);}
                }
            }catch(IOException rollback){failure.addSuppressed(rollback);}
            throw failure;
        }finally{for(Path temporary:staged.values())Files.deleteIfExists(temporary);}
    }
}
