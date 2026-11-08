package siliconshaders.metal.settings;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** One atomic library file; profile names never become filesystem paths. Failed writes retain the old library. */
public final class ProfileStore {
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
    private final Path file;private List<GraphicsProfile> profiles=List.of();private String active="",issue="";
    public ProfileStore(Path directory){
        siliconshaders.metal.BrandMigration.settings(directory);
        file=directory.resolve("config/siliconshaders-graphics-profiles.json");
        try{
            if(!Files.isRegularFile(file))return;if(Files.size(file)>1024*1024)throw new IllegalArgumentException();
            String raw=Files.readString(file);ProfileCodec.checkNesting(raw);JsonObject data=JsonParser.parseString(raw).getAsJsonObject();if(data.get("version").getAsInt()!=1)throw new IllegalArgumentException();
            List<GraphicsProfile> loaded=new ArrayList<>();Set<String> ids=new HashSet<>();
            for(JsonElement row:data.getAsJsonArray("profiles")){
                if(loaded.size()>=64)throw new IllegalArgumentException();JsonObject object=row.getAsJsonObject();
                String id=object.get("id").getAsString();UUID.fromString(id);if(!ids.add(id))throw new IllegalArgumentException();
                Map<String,Float> values=new TreeMap<>();for(var e:object.getAsJsonObject("values").entrySet())values.put(e.getKey(),e.getValue().getAsFloat());
                loaded.add(new GraphicsProfile(id,object.get("name").getAsString(),GraphicsProfile.Scope.valueOf(object.get("scope").getAsString()),values));
            }
            profiles=List.copyOf(loaded);active=data.has("active")?data.get("active").getAsString():"";
        }catch(Exception bad){issue="profiles.read";siliconshaders.metal.Log.warn("[SiliconShaders menu] Profile library not read: "+bad);}
    }
    public List<GraphicsProfile> profiles(){return profiles;}
    public String active(){return active;}
    public String issue(){return issue;}
    public GraphicsProfile find(String id){return profiles.stream().filter(p->p.id().equals(id)).findFirst().orElse(null);}
    public void put(GraphicsProfile profile) throws IOException {
        List<GraphicsProfile> next=new ArrayList<>(profiles);int at=-1;for(int i=0;i<next.size();i++)if(next.get(i).id().equals(profile.id()))at=i;
        if(at<0){if(next.size()>=64)throw new IllegalArgumentException("profiles.full");next.add(profile);}else next.set(at,profile);
        persist(next,active);
    }
    public void delete(String id) throws IOException {List<GraphicsProfile> next=new ArrayList<>(profiles);next.removeIf(p->p.id().equals(id));persist(next,active.equals(id)?"":active);}
    public Map<Path,String> activationDocument(String selected){return issue.isEmpty()?Map.of(file,document(profiles,selected==null?"":selected)):Map.of();}
    public void activationPublished(String selected){if(issue.isEmpty())active=selected==null?"":selected;}
    private static String document(List<GraphicsProfile> next,String selected){JsonObject data=new JsonObject();data.addProperty("version",1);data.addProperty("active",selected);data.add("profiles",JSON.toJsonTree(next));return JSON.toJson(data)+"\n";}
    private void persist(List<GraphicsProfile> next,String selected) throws IOException {
        if(!issue.isEmpty())throw new IOException("Existing profile library could not be read; preserve it");
        SettingsFiles.write(Map.of(file,document(next,selected)));profiles=List.copyOf(next);active=selected;
    }
}
