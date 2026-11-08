package siliconshaders.metal.mixin.pack;

import siliconshaders.metal.own.BlockLights;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
abstract class BlockLightWorldMixin {
    @Inject(method="sendBlockUpdated",at=@At("HEAD"))
    private void siliconshaders$blockLight(BlockPos pos,BlockState before,BlockState after,int flags,CallbackInfo ci){BlockLights.changed(pos);}
    @Inject(method="setBlocksDirty",at=@At("HEAD"))
    private void siliconshaders$blockLightDirty(BlockPos pos,BlockState before,BlockState after,CallbackInfo ci){BlockLights.changed(pos);}
    @Inject(method="unload",at=@At("TAIL"))
    private void siliconshaders$blockLightUnload(net.minecraft.world.level.chunk.LevelChunk chunk,CallbackInfo ci){BlockLights.unloaded(chunk.getPos().x(),chunk.getPos().z());}
    @Inject(method="onChunkLoaded",at=@At("TAIL"))
    private void siliconshaders$blockLightChunk(ChunkPos chunk,CallbackInfo ci){
        ClientLevel level=(ClientLevel)(Object)this;
        for(int y=level.getMinSectionY();y<level.getMaxSectionY();y++)BlockLights.changed(new BlockPos(chunk.x()<<4,y<<4,chunk.z()<<4));
    }
}
