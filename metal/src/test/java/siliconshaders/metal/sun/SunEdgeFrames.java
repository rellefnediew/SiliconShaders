package siliconshaders.metal.sun;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import org.joml.Vector3f;

/** Feeds the production projection into the native edge diagnostic; no copied matrix implementation. */
public final class SunEdgeFrames {
    public static void main(String[] args) throws Exception {
        Path directory=Path.of(args[0]);Files.createDirectories(directory);
        int count=512;
        for(int size:new int[]{512,1024,2048,4096}) for(String mode:new String[]{"production","unsnapped","frozen","near-origin","far-origin","smooth","smooth-frozen","production-short","smooth-short","temporal","temporal-short","temporal-frozen","cascade","cascade-short","cascade-frozen","cascade-transition"}) {
            int cameraX=mode.equals("near-origin")?0:mode.equals("far-origin")?10000:-240;
            int cameraY=mode.contains("origin")?8:72,cameraZ=mode.equals("near-origin")?0:mode.equals("far-origin")?10000:-480;
            boolean smooth=mode.startsWith("smooth"),cascade=mode.startsWith("cascade"),temporal=mode.startsWith("temporal")||cascade;int packetBytes=cascade?SunFrames.CASCADE_BYTES:temporal?SunFrames.TEMPORAL_BYTES:SunFrames.bytes(smooth);
            ByteBuffer data=ByteBuffer.allocate(32+count*(packetBytes+4)).order(ByteOrder.LITTLE_ENDIAN);
            data.putInt(0x31474445).putInt(count).putInt(size).putInt(cameraX).putInt(cameraY).putInt(cameraZ).putInt(packetBytes).putInt(mode.endsWith("short")?1:0);
            for(int frame=0;frame<count;frame++) {
                float angle=(mode.endsWith("short")?0.1236124f:0.9236124f)+(mode.contains("frozen")||mode.contains("transition")?0:frame*0.000015f);
                if(smooth||temporal) {
                    var packet=org.lwjgl.system.MemoryUtil.memAlloc(packetBytes);
                    try {
                        if(temporal) {
                            var view=new org.joml.Matrix4f().set(new float[]{0.2f,0,0,0, 0,0,0.1f,0, 0,0.2f,0,0, 0,0,1.2f,1});
                            if(cascade)SunFrames.writeCascaded(org.lwjgl.system.MemoryUtil.memAddress(packet),angle,cameraX,cameraY,cameraZ,size,true,true,1,mode.hashCode(),1,view,0.002f,frame);
                            else SunFrames.writeTemporal(org.lwjgl.system.MemoryUtil.memAddress(packet),angle,cameraX,cameraY,cameraZ,size,true,1,mode.hashCode(),1,view,0.002f,frame);
                        } else SunFrames.write(org.lwjgl.system.MemoryUtil.memAddress(packet),angle,cameraX,cameraY,cameraZ,size,true,true,1,1,1);
                        if(mode.contains("transition")) {
                            float shift=-1.2f+2.4f*frame/(count-1);
                            packet.putFloat(48,packet.getFloat(48)+shift);
                            for(int i=1;i<3;i++) {
                                int at=SunFrames.cascadeOffset(i);packet.putFloat(at+48,packet.getFloat(at+48)+shift*SunFrames.NEAR_RANGE/packet.getFloat(at+112));
                            }
                        }
                        data.put(packet).putFloat(angle);
                    } finally { org.lwjgl.system.MemoryUtil.memFree(packet); }
                    continue;
                }
                Vector3f sun=new Vector3f(-(float)Math.sin(angle),(float)Math.cos(angle),0).normalize();
                var matrix=SunProjection.create(sun,cameraX,cameraY,cameraZ,mode.equals("unsnapped")?0:size,64);
                int start=data.position();float[] values=new float[16];matrix.get(values);
                for(float value:values)data.putFloat(value);
                data.putInt(cameraX).putInt(cameraY).putInt(cameraZ).putInt(0);
                data.putFloat(0).putFloat(0).putFloat(0).putFloat(0);
                data.putFloat(sun.x).putFloat(sun.y).putFloat(sun.z).putFloat(1);
                data.putFloat(64).putFloat(1.0f/size).putFloat(0.00015f).putFloat(1);
                data.putInt(1).putInt(0).putInt(size).putInt(1);
                if(data.position()-start!=144)throw new AssertionError("SunFrame layout drift");
                data.putFloat(angle);
            }
            Files.write(directory.resolve(mode+"-"+size+".bin"),data.array());
        }
        System.out.println("Wrote production, unsnapped, frozen and translated-scene solar frames at 512/1024/2048/4096");
    }
}
