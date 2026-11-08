package siliconshaders.metal.fast;

import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Smart Leaves' face rule against the real registry (no mixins in this process: Block.shouldRenderFace is vanilla's, and a face
 * the mixin keeps is one vanilla keeps and SmartLeaves.skip doesn't drop), the hook's target, the modes and their persistence.
 */
public final class SmartLeavesChecks {
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}

    /** The faces a canopy (a ball of oak leaves, birch on one side) gets with cutout leaves: vanilla's rule plus the mode's. */
    private static int faces(BlockState[][][] grid) {
        int n=grid.length,faces=0;
        for(int x=0;x<n;x++)for(int y=0;y<n;y++)for(int z=0;z<n;z++) {
            BlockState state=grid[x][y][z];
            if(state.isAir())continue;
            for(Direction d:Direction.values()) {
                int ax=x+d.getStepX(),ay=y+d.getStepY(),az=z+d.getStepZ();
                BlockState neighbor=ax<0||ay<0||az<0||ax>=n||ay>=n||az>=n?Blocks.AIR.defaultBlockState():grid[ax][ay][az];
                if(Block.shouldRenderFace(state,neighbor,d)&&!SmartLeaves.skip(state,neighbor))faces++;
            }
        }
        return faces;
    }

    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
        LeavesBlock.setCutoutLeaves(true);
        // The hook's target: LeavesBlock declares skipRendering and no leaf block overrides it below LeavesBlock.
        var target=LeavesBlock.class.getDeclaredMethod("skipRendering",BlockState.class,BlockState.class,Direction.class);
        require(target.getReturnType()==boolean.class,"skipRendering changed");
        int leafBlocks=0;
        for(Block block:BuiltInRegistries.BLOCK) {
            if(!(block instanceof LeavesBlock))continue;
            leafBlocks++;
            for(Class<?> c=block.getClass();c!=LeavesBlock.class;c=c.getSuperclass()) {
                for(var m:c.getDeclaredMethods())require(!m.getName().equals("skipRendering"),c.getName()+" overrides skipRendering: the hook misses it");
            }
        }
        require(leafBlocks>=10,"Too few leaf blocks: "+leafBlocks);

        BlockState oak=Blocks.OAK_LEAVES.defaultBlockState(),birch=Blocks.BIRCH_LEAVES.defaultBlockState();
        BlockState oakFar=oak.setValue(LeavesBlock.DISTANCE,6).setValue(LeavesBlock.PERSISTENT,true).setValue(BlockStateProperties.WATERLOGGED,true);
        BlockState[] others={Blocks.CHERRY_LEAVES.defaultBlockState(),Blocks.PALE_OAK_LEAVES.defaultBlockState(),Blocks.MANGROVE_LEAVES.defaultBlockState(),
            Blocks.AZALEA_LEAVES.defaultBlockState(),Blocks.FLOWERING_AZALEA_LEAVES.defaultBlockState()};
        BlockState[] notLeaves={Blocks.AIR.defaultBlockState(),Blocks.GLASS.defaultBlockState(),Blocks.OAK_LOG.defaultBlockState(),Blocks.WATER.defaultBlockState(),
            Blocks.SHORT_GRASS.defaultBlockState(),Blocks.VINE.defaultBlockState(),Blocks.MANGROVE_ROOTS.defaultBlockState()};
        // Vanilla with cutout leaves keeps every face toward another leaf block: that's what Smart Leaves drops.
        require(Block.shouldRenderFace(oak,oak,Direction.UP)&&Block.shouldRenderFace(oak,birch,Direction.NORTH),"Vanilla already culls cutout leaves");

        Path dir=Files.createTempDirectory("smart-leaves-");
        try {
            SmartLeaves settings=new SmartLeaves(dir);
            require(settings.mode()==SmartLeaves.Mode.ALL,"Default is not all leaves");
            BlockState[][][] canopy=new BlockState[9][9][9];
            for(int x=0;x<9;x++)for(int y=0;y<9;y++)for(int z=0;z<9;z++) {
                int dx=x-4,dy=y-4,dz=z-4;
                canopy[x][y][z]=dx*dx+dy*dy+dz*dz<=16?(x<3?birch:oak):Blocks.AIR.defaultBlockState();
            }
            int[] faces=new int[3];
            for(SmartLeaves.Mode mode:SmartLeaves.Mode.values()) {
                settings.choose(mode);
                for(BlockState other:notLeaves)require(!SmartLeaves.skip(oak,other),mode+" drops a leaf face toward "+other);
                boolean all=mode==SmartLeaves.Mode.ALL,any=mode!=SmartLeaves.Mode.OFF;
                require(SmartLeaves.skip(oak,oak)==any&&SmartLeaves.skip(oak,oakFar)==any&&SmartLeaves.skip(oakFar,oak)==any,mode+": same leaves (any decay/water state)");
                require(SmartLeaves.skip(oak,birch)==all&&SmartLeaves.skip(birch,oak)==all,mode+": oak against birch");
                for(BlockState other:others)require(SmartLeaves.skip(oak,other)==all&&SmartLeaves.skip(other,other)==any,mode+": "+other);
                faces[mode.ordinal()]=faces(canopy);
                require(new SmartLeaves(dir).mode()==mode,mode+" did not persist");
            }
            require(faces[1]<faces[2]&&faces[2]<faces[0],"Face counts out of order");
            // A changed mode needs a rebuild; the same one doesn't.
            require(!settings.choose(SmartLeaves.Mode.SAME)&&settings.choose(SmartLeaves.Mode.OFF),"Rebuild decision");
            Files.writeString(dir.resolve("config/siliconshaders-smart-leaves.json"),"{\"mode\":\"sideways\"}");
            require(new SmartLeaves(dir).mode()==SmartLeaves.Mode.ALL,"Unknown mode not ignored");
            System.out.printf("PASS: %d leaf blocks, hook target, off/all/same against leaves, decay/water states and non-leaves, persistence; "
                +"radius-4 canopy (oak, birch side): %d faces vanilla cutout, %d same kind (-%.0f%%), %d all leaves (-%.0f%%)%n",
                leafBlocks,faces[0],faces[2],100-faces[2]*100.0/faces[0],faces[1],100-faces[1]*100.0/faces[0]);
        } finally { try(var files=Files.walk(dir)){for(var p:files.sorted(java.util.Comparator.reverseOrder()).toList())Files.delete(p);} }
    }
}
