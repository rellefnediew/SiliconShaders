package siliconshaders.metal.sun;

/** Production two-level 4096/2048 packets for coarse coverage, entity reactivity and cascade overlap regressions. */
import java.nio.*;
import java.nio.file.*;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;
public class SunFarFrames {
 public static void main(String[] args)throws Exception {
  Path dir=Path.of(args[0]);Files.createDirectories(dir);int count=512,size=4096,bytes=SunFrames.CASCADE_BYTES;
  for(String mode:new String[]{"far-solar","far-phase","far-reactive","far-transition"}) {
   ByteBuffer out=ByteBuffer.allocate(32+count*(bytes+4)).order(ByteOrder.LITTLE_ENDIAN);
   out.putInt(0x31474445).putInt(count).putInt(size).putInt(-240).putInt(72).putInt(-480).putInt(bytes).putInt(1);
   for(int frame=0;frame<count;frame++) {
    float angle=0.1236124f+(mode.contains("solar")||mode.contains("reactive")?frame*0.000015f:0);
    var view=new Matrix4f().set(new float[]{.2f,0,0,0,0,0,.1f,0,0,.2f,0,0,0,0,1.2f,1});
    ByteBuffer p=MemoryUtil.memAlloc(bytes);
    try {
     SunFrames.writeCascaded(MemoryUtil.memAddress(p),angle,-240,72,-480,size,true,true,1,1,1,view,1.0f/120,frame,256,2);
     if(mode.contains("transition")) {
      float shift=-1.2f+2.4f*frame/(count-1);p.putFloat(48,p.getFloat(48)+shift);
      p.putFloat(256+48,p.getFloat(256+48)+shift*24/256);
     } else p.putFloat(48,p.getFloat(48)+3); // Force the actual 256-block, 2048-pixel distant map.
     if(mode.contains("phase")) {
      // Sweep a complete distant texel in the light grid, without moving the physical shadow.
      p.putFloat(256+48,p.getFloat(256+48)+2.0f/2048*frame/(count-1));
      p.putFloat(256+52,p.getFloat(256+52)+2.0f/2048*frame/(count-1));
     }
     if(mode.contains("reactive"))p.putInt(140,3); // A self/entity caster enables the runtime reactive path.
     out.put(p).putFloat(angle);
    }finally{MemoryUtil.memFree(p);}
   }
   Files.write(dir.resolve(mode+".bin"),out.array());
  }
 }
}
