package siliconshaders.metal.own;

import java.nio.file.*;
import siliconshaders.metal.sun.SunTuning;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.lwjgl.system.MemoryUtil;

/** Real registry/state classification, persistent controls and metadata association through sort/QREC/stubs. */
public final class SurfaceMaterialChecks {
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static net.minecraft.world.level.block.Block block(String name){return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getOptional(net.minecraft.resources.Identifier.parse("minecraft:"+name)).orElseThrow();}
    public static void main(String[] args)throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
        var c=SurfaceMaterials.catalog();require(c.profiles().size()==65&&c.rules().size()==1412,"Incomplete material catalog");
        var coverage=SurfaceMaterials.coverage();require(coverage.matchedBlocks()>900&&coverage.matchedStates()>10000,"Too little real registry coverage: "+coverage);
        require(SurfaceMaterials.id(Blocks.IRON_BLOCK.defaultBlockState())==SurfaceMaterials.profile("iron"),"Iron classification");
        require(SurfaceMaterials.id(Blocks.GOLD_BLOCK.defaultBlockState())==SurfaceMaterials.profile("gold"),"Gold classification");
        require(SurfaceMaterials.id(block("waxed_oxidized_copper").defaultBlockState())==SurfaceMaterials.profile("copper"),"Copper weather/wax variant lost");
        require(SurfaceMaterials.id(Blocks.OAK_PLANKS.defaultBlockState())==SurfaceMaterials.profile("wood"),"Wood classification");
        require(SurfaceMaterials.id(Blocks.OAK_LEAVES.defaultBlockState())==SurfaceMaterials.profile("leaf"),"Leaf classification");
        int lampOff=SurfaceMaterials.id(Blocks.REDSTONE_LAMP.defaultBlockState().setValue(BlockStateProperties.LIT,false)),lampOn=SurfaceMaterials.id(Blocks.REDSTONE_LAMP.defaultBlockState().setValue(BlockStateProperties.LIT,true));
        require(lampOff==SurfaceMaterials.profile("lamp_off")&&lampOn==SurfaceMaterials.profile("lamp_on"),"Lamp state ignored");
        require(SurfaceMaterials.id(block("copper_bulb").defaultBlockState().setValue(BlockStateProperties.LIT,false))==SurfaceMaterials.profile("copper_bulb_off"),"Copper bulb off");
        require(SurfaceMaterials.id(block("copper_bulb").defaultBlockState().setValue(BlockStateProperties.LIT,true))==SurfaceMaterials.profile("copper_bulb_on"),"Copper bulb on");
        require(SurfaceMaterials.id(Blocks.FURNACE.defaultBlockState().setValue(BlockStateProperties.LIT,true))==SurfaceMaterials.profile("furnace_lit"),"Furnace state ignored");
        require(SurfaceMaterials.id(Blocks.AIR.defaultBlockState())==0,"Air acquired material");
        require(SurfaceMaterials.translucentId(Blocks.GLASS.defaultBlockState())==1&&SurfaceMaterials.translucentId(Blocks.GLASS_PANE.defaultBlockState())==1,"Clear glass classification");
        require(SurfaceMaterials.translucentId(block("red_stained_glass").defaultBlockState())==2&&SurfaceMaterials.translucentId(block("blue_stained_glass_pane").defaultBlockState())==2,"Stained glass classification");
        require(SurfaceMaterials.translucentId(Blocks.TINTED_GLASS.defaultBlockState())==3&&SurfaceMaterials.translucentId(Blocks.ICE.defaultBlockState())==4&&SurfaceMaterials.translucentId(Blocks.FROSTED_ICE.defaultBlockState())==4,"Tinted glass / ice classification");
        require(SurfaceMaterials.translucentId(Blocks.SLIME_BLOCK.defaultBlockState())==5&&SurfaceMaterials.translucentId(Blocks.HONEY_BLOCK.defaultBlockState())==6,"Slime/honey classification");
        require(SurfaceMaterials.translucentId(Blocks.WATER.defaultBlockState())==0&&SurfaceMaterials.translucentId(Blocks.NETHER_PORTAL.defaultBlockState())==0,"Water/portal accidentally acquired glass behavior");
        for(int id=1;id<=6;id++)for(int block=0;block<16;block++)for(int sky=0;sky<16;sky++){
            int light=block*16|(sky*16<<16),tag=WaterVertices.encodeMaterial(light,id);
            require((tag&255)!=255&&WaterVertices.decodeMaterial(tag)==light,"Transparent tag changed lighting or collided with water");
            require(((tag&15)|((tag>>>16&15)<<4))==id,"Transparent tag lost material identity");
        }
        Path dir=Files.createTempDirectory("material-settings-");
        try {
            var tuning=new SunTuning(dir);int start=SunTuning.MATERIAL_START;
            require(SunTuning.LIGHTING_COUNT==76&&SunTuning.WATER_START==76&&SunTuning.MATERIAL_COUNT==25,"Existing packet ABI changed");
            tuning.set(start,0);tuning.set(start+1,2.25f);tuning.set(start+2,1.5f);tuning.set(start+5,0);tuning.set(start+12,0);tuning.set(start+13,.45f);tuning.set(start+14,1.75f);
            tuning.set(start+15,0);tuning.set(start+16,1.6f);tuning.set(start+17,0);tuning.set(start+18,1.7f);tuning.set(start+19,0);tuning.set(start+20,1.8f);tuning.set(start+21,2);tuning.set(start+22,96);tuning.set(start+23,39);tuning.set(start+24,2);
            var loaded=new SunTuning(dir);
            require(loaded.value(start+15)==0&&Math.abs(loaded.value(start+16)-1.6f)<.001&&loaded.value(start+17)==0&&Math.abs(loaded.value(start+18)-1.7f)<.001,"Surface detail settings did not persist");
            require(loaded.value(start+19)==0&&Math.abs(loaded.value(start+20)-1.8f)<.001&&loaded.value(start+21)==2&&loaded.value(start+22)==96,"World reflection settings did not persist");
            require(loaded.value(start+23)==39&&loaded.value(start+24)==2,"Reflection steps/resolution did not persist");
            for(int i=15;i<25;i++)require(SunTuning.PARAMS.get(start+i).group()==SunTuning.Group.MATERIAL,"New surface setting outside material reset");
            for(int i=36;i<40;i++)require(SunTuning.PARAMS.get(i).neutral()==0&&SunTuning.PARAMS.get(i).min()==0&&SunTuning.PARAMS.get(i).max()==0,"Native surface slot overlaps a user lighting setting");
            require(loaded.value(start)==0&&loaded.value(start+1)==2.25f&&loaded.value(start+2)==1.5f&&loaded.value(start+5)==0,"Material controls did not persist");
            require(loaded.value(start+12)==0&&Math.abs(loaded.value(start+13)-.45f)<.0001&&loaded.value(start+14)==1.75f,"Transparent controls did not persist");
            tuning.set(SunTuning.WATER_START,2);tuning.reset(SunTuning.Group.MATERIAL);
            require(tuning.value(start)==1&&tuning.value(start+1)==1&&tuning.value(start+5)==1&&tuning.value(SunTuning.WATER_START)==2,"Material reset altered other groups");
            require(tuning.value(start+15)==1&&tuning.value(start+16)==1&&tuning.value(start+17)==1&&tuning.value(start+18)==1,"Surface detail reset failed");
            require(tuning.value(start+19)==1&&tuning.value(start+20)==1&&tuning.value(start+21)==1&&tuning.value(start+22)==64,"World reflection reset failed");
            require(tuning.value(start+23)==32&&tuning.value(start+24)==1,"Reflection steps/resolution reset failed");
            for(int quality=-1;quality<=3;quality++) {
                Files.writeString(dir.resolve("config/siliconshaders-sun-tuning.json"),"{\"materialWorldReflectionQuality\":"+quality+"}");
                var legacy=new SunTuning(dir);
                require(legacy.value(start+23)==48&&legacy.value(start+24)==(quality<=0?0:1),"Legacy reflection budget/resolution changed");
            }
            Files.writeString(dir.resolve("config/siliconshaders-sun-tuning.json"),"{\"materialWorldReflectionQuality\":0,\"materialWorldReflectionSteps\":99,\"materialWorldReflectionResolution\":2}");
            var explicit=new SunTuning(dir);require(explicit.value(start+23)==48&&explicit.value(start+24)==2,"Explicit new controls lost/clamp failed");
            Files.writeString(dir.resolve("config/siliconshaders-sun-tuning.json"),"{\"materialGloss\":1.5}");
            var migrated=new SunTuning(dir);require(migrated.value(start+1)==1.5f&&migrated.value(start+15)==1&&migrated.value(start+17)==1&&migrated.value(start+19)==1&&migrated.value(start+21)==1,"Old material settings did not migrate by key");
            require(migrated.value(start+23)==32&&migrated.value(start+24)==1,"Fresh reflection defaults incorrect");
            // Changing live controls cannot change the immutable per-state classification.
            require(SurfaceMaterials.id(Blocks.REDSTONE_LAMP.defaultBlockState().setValue(BlockStateProperties.LIT,true))==lampOn,"Control changed cached material identity");
        }finally{try(var files=Files.walk(dir)){for(var p:files.sorted(java.util.Comparator.reverseOrder()).toList())Files.delete(p);}}
        OwnQuads.Layer layer=new OwnQuads.Layer();float[] uv={0,0,1,0,1,1,0,1};int[] colors={-1,-1,-1,-1},light={0x00f000f0,0x00f000f0,0x00f000f0,0x00f000f0};
        // Emit out of sorted order. One warped quad requires the QREC stub path.
        for(int i=0;i<3;i++){
            float y=2-i;float[] p={0,y,0,1,y,0,1,y,1,0,y,1};if(i==2)p[6]=.4f;
            layer.surfaceMaterial=7+i;layer.quad(p,colors,uv,light);
        }
        long metadata=MemoryUtil.nmemCalloc(1,3),records=MemoryUtil.nmemAlloc(3L*40),stubs=MemoryUtil.nmemAlloc(3L*64);
        try {
            layer.writeSurfaceMaterials(metadata);layer.copyRecsTo(records,-1,stubs,100,0);
            int pos=0;boolean[] seen=new boolean[3];for(int b=0;b<7;b++)for(int i=0;i<layer.count[b];i++){
                int id=MemoryUtil.memGetByte(metadata+pos)&255;float y=((MemoryUtil.memGetShort(layer.addr[b]+i*64L+2)&65535)/2048f-8);
                require(id==9-Math.round(y),"Sorting detached material from quad");seen[id-7]=true;pos++;
            }
            require(pos==3&&seen[0]&&seen[1]&&seen[2]&&layer.stubs()>0,"Metadata/stub coverage incomplete");
        }finally{MemoryUtil.nmemFree(metadata);MemoryUtil.nmemFree(records);MemoryUtil.nmemFree(stubs);}
        layer.reset();require(layer.surfaceMaterial==0,"Worker reset leaked previous block material");
        System.out.println("PASS: material registry coverage "+coverage+", state variants, live controls, sorting and QREC/stub association");
    }
}
