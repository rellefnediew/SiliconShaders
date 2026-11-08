package siliconshaders.metal.bench;

import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.levelgen.Heightmap;

/** A repeatable material/light/entity fixture in the dedicated benchmark world. Server thread only. */
final class BenchFixtures {
    static void prepare(MinecraftServer server,Map<String,double[]> spots){
        var level=server.overworld();double[] land=spots.get("land");
        int x=(int)land[0]+80,z=(int)land[2]+80,y=80;
        for(int dx=-20;dx<=20;dx+=8)for(int dz=-20;dz<=20;dz+=8){
            level.getChunk((x+dx)>>4,(z+dz)>>4);
            y=Math.max(y,level.getHeight(Heightmap.Types.WORLD_SURFACE,x+dx,z+dz)+8);
        }
        y=Math.min(level.getMaxY()-16,y);
        cmd(server,"kill @e[tag=siliconshaders_bench_fixture]");
        cmd(server,"fill "+(x-18)+" "+y+" "+(z-18)+" "+(x+18)+" "+(y+10)+" "+(z+18)+" air");
        cmd(server,"fill "+(x-18)+" "+y+" "+(z-18)+" "+(x+18)+" "+y+" "+(z+18)+" smooth_stone");
        String[] materials={"iron_block","gold_block","diamond_block","oak_planks","stone","glass","oak_leaves[persistent=true]","glowstone"};
        String[] lights={"sea_lantern","glowstone","shroomlight","ochre_froglight"};
        for(int i=0;i<64;i++){
            int px=x-14+(i%8)*4,pz=z-14+(i/8)*4;
            cmd(server,"setblock "+px+" "+(y+1)+" "+pz+" "+materials[i%8]);
            if(i%4==0)cmd(server,"setblock "+px+" "+(y+2)+" "+pz+" "+lights[(i/4)%4]);
            cmd(server,"summon armor_stand "+(px+1.5)+" "+(y+1)+" "+(pz+.5)+" {Tags:[\"siliconshaders_bench_fixture\"],NoGravity:1b,ShowArms:1b}");
        }
        spots.put("objekte",new double[]{x+.5,y+4,z+.5});
    }
    private static void cmd(MinecraftServer server,String value){server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),value);}
}
