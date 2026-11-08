package siliconshaders.metal;

import java.io.BufferedWriter;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import siliconshaders.metal.sun.SunSettings;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/** Opt-in, sparse GPU stage measurements. Never introduces a GPU wait or render-thread file I/O. */
final class SunProfile {
    private static final Path ROOT=Path.of(System.getProperty("user.dir"));
    private static final Path MARKER=ROOT.resolve("config/siliconshaders-sun-profile.once");
    static final boolean ON=Boolean.getBoolean("siliconshaders.sun.profile") || Files.isRegularFile(MARKER);
    static final int EVERY=30, GROUPS=512, STRIDE=160;
    private static final java.lang.management.ThreadMXBean THREADS=ManagementFactory.getThreadMXBean();
    private static final ArrayBlockingQueue<String> QUEUE=new ArrayBlockingQueue<>(32);
    private static volatile boolean closing;
    private static long previousTime,previousCpu,dropped;
    private static Thread writer;

    record Frame(long submit,long epoch,double wallMs,double cpuMs,SunSettings.State settings,
                 boolean world,boolean menu,int width,int height,double x,double y,double z,float yaw,float pitch) {
        String prefix() {
            return submit+","+epoch+","+wallMs+","+cpuMs+","+settings.enabled()+","+settings.shadowSize()+","+
                settings.shadowDistance()+","+settings.shadowLevels()+","+settings.smooth()+","+settings.shadowCulling()+","+
                world+","+menu+","+width+","+height+","+x+","+y+","+z+","+yaw+","+pitch+","+settings.filterOptimization()+","+settings.casterSelection()+","+settings.receiverScan()+","+settings.memoryOptimization();
        }
    }

    static void start() {
        if(writer!=null)return;
        Path out=ROOT.resolve("logs/sun-profile-"+LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))+".csv");
        writer=new Thread(()->{
            try {
                Files.createDirectories(out.getParent());
                try(BufferedWriter w=Files.newBufferedWriter(out,StandardOpenOption.CREATE_NEW)) {
                    w.write("submit,epochMs,wallMs,renderThreadCpuMs,enabled,size,distance,levels,smooth,culling,world,menu,width,height,x,y,z,yaw,pitch,filterOptimization,casterSelection,receiverScan,memoryOptimization,group,label,spanMs,vertexMs,fragmentMs,startMs,endMs,mainCommandMs,truncated,dropped\n");
                    while(!closing || !QUEUE.isEmpty()) {
                        String rows=QUEUE.poll(200,TimeUnit.MILLISECONDS);
                        if(rows!=null){w.write(rows);w.flush();}
                    }
                }
            } catch(Exception e){siliconshaders.metal.Log.warn("[sun profile] Cannot write measurements: "+e);}
        },"siliconshaders-sun-profile");
        writer.setDaemon(true);writer.start();
        Runtime.getRuntime().addShutdownHook(new Thread(()->{closing=true;try{writer.join(2000);}catch(InterruptedException e){Thread.currentThread().interrupt();}},"siliconshaders-sun-profile-close"));
        try{Files.deleteIfExists(MARKER);}catch(Exception e){siliconshaders.metal.Log.warn("[sun profile] Cannot remove one-shot marker: "+e);}
        siliconshaders.metal.Log.info("[sun profile] Every "+EVERY+" submits; asynchronous results: "+out);
    }

    static Frame frame(long submit,boolean sampled) {
        long now=System.nanoTime(),cpu=THREADS.getCurrentThreadCpuTime();
        double wall=previousTime==0?Double.NaN:(now-previousTime)*1e-6;
        double cpuMs=previousCpu<0||cpu<0||previousTime==0?Double.NaN:(cpu-previousCpu)*1e-6;
        previousTime=now;previousCpu=cpu;
        if(!sampled)return null;
        var client=net.minecraft.client.Minecraft.getInstance();var player=client.player;
        var texture=client.gameRenderer==null?null:client.gameRenderer.mainRenderTarget().getColorTexture();
        return new Frame(submit,System.currentTimeMillis(),wall,cpuMs,SunSettings.get().state(),client.level!=null,
            client.gui.screen()!=null,texture==null?0:texture.getWidth(0),texture==null?0:texture.getHeight(0),
            player==null?0:player.getX(),player==null?0:player.getY(),player==null?0:player.getZ(),
            player==null?0:player.getYRot(),player==null?0:player.getXRot());
    }

    static double duration(double start,double end) {
        return Double.isFinite(start)&&Double.isFinite(end)&&start>=0&&end>=start?(end-start)*.001:Double.NaN;
    }
    static String csv(String value){return '"'+value.replace("\"","\"\"").replace('\n',' ').replace('\r',' ')+'"';}

    /** Called by normal frame retirement after the existing wait, while the command buffer is still retained. */
    static void completed(long ctx,long command,long samples,int count,String[] labels,Frame frame,long benchEpoch) {
        try(MemoryStack stack=MemoryStack.stackPush()) {
            long out=stack.nmalloc(8,Math.max(1,count)*8);Native.profileRead(ctx,samples,count,out); // releases samples
            String prefix=frame==null?"":frame.prefix();StringBuilder rows=new StringBuilder(count*50);
            double main=Native.cmdGpuMicros(command)*.001;int truncated=count>=GROUPS*4?1:0;
            for(int g=0;g<count/4;g++) {
                double vs=MemoryUtil.memGetDouble(out+g*32L),ve=MemoryUtil.memGetDouble(out+g*32L+8);
                double fs=MemoryUtil.memGetDouble(out+g*32L+16),fe=MemoryUtil.memGetDouble(out+g*32L+24);
                double start=Double.POSITIVE_INFINITY,end=-1;
                for(double t:new double[]{vs,ve,fs,fe})if(Double.isFinite(t)&&t>=0){start=Math.min(start,t);end=Math.max(end,t);}
                BenchGpu.stage(benchEpoch,labels[g],duration(start,end));
                if(frame==null)continue;
                rows.append(prefix).append(',').append(g).append(',').append(csv(labels[g]))
                    .append(',').append(duration(start,end)).append(',').append(duration(vs,ve)).append(',').append(duration(fs,fe))
                    .append(',').append(start*.001).append(',').append(end*.001).append(',').append(main).append(',').append(truncated).append(',').append(dropped).append('\n');
            }
            if(frame!=null&&!QUEUE.offer(rows.toString()))dropped++;
        }
    }
}
