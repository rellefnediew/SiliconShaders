package siliconshaders.metal.settings;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Shipped, read-only profiles. The first-start default and Standard quality remain DefaultProfile's responsibility. */
public final class BundledProfiles {
    public static final String NOIR_ID="builtin.noir";
    private static volatile GraphicsProfile noir;
    private BundledProfiles(){}

    public static List<GraphicsProfile> all(String standardName){
        return List.of(DefaultProfile.get(standardName),find(NOIR_ID));
    }

    public static GraphicsProfile find(String id){
        if(DefaultProfile.ID.equals(id))return DefaultProfile.get("Standard");
        if(!NOIR_ID.equals(id))return null;
        GraphicsProfile profile=noir;
        if(profile==null){
            try(InputStream in=BundledProfiles.class.getResourceAsStream("/siliconshaders/noir-profile.txt")){
                if(in==null)throw new IllegalStateException("noir-profile.txt missing");
                profile=ProfileCodec.decode(new String(in.readAllBytes(),StandardCharsets.UTF_8));
            }catch(java.io.IOException failure){throw new IllegalStateException(failure);}
            noir=profile;
        }
        return new GraphicsProfile(NOIR_ID,"Noir",profile.scope(),GraphicsCatalog.portable(profile.values(),profile.scope()));
    }
}
