package siliconshaders.metal.pack;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import siliconshaders.metal.MetalBridge;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import static java.lang.foreign.ValueLayout.*;

final class PackNative {
    private static final MethodHandle NEW=fn("mc_pack_new",JAVA_LONG,JAVA_LONG,JAVA_LONG,JAVA_INT,JAVA_LONG,JAVA_INT);
    private static final MethodHandle DRAW=fn("mc_pack_draw",JAVA_INT,JAVA_LONG,JAVA_LONG,JAVA_LONG,JAVA_INT,JAVA_INT,JAVA_INT,JAVA_FLOAT,JAVA_LONG,JAVA_INT);
    private static final MethodHandle RELEASE=fn("mc_pack_release",null,JAVA_LONG);
    private static MethodHandle fn(String name,MemoryLayout result,MemoryLayout... args) {
        return Linker.nativeLinker().downcallHandle(MetalBridge.library().find(name).orElseThrow(),
            result==null?FunctionDescriptor.ofVoid(args):FunctionDescriptor.of(result,args));
    }
    static long create(long ctx,String json,int format) {
        ByteBuffer text=MemoryUtil.memUTF8(json);
        try (MemoryStack stack=MemoryStack.stackPush()) {
            long error=errorBuffer(stack);
            long handle=(long)NEW.invokeExact(ctx,MemoryUtil.memAddress(text),format,error,4096);
            if (handle==0) throw new IllegalArgumentException(MemoryUtil.memUTF8(error));
            return handle;
        } catch (Throwable e) { throw rethrow(e); }
        finally { MemoryUtil.memFree(text); }
    }
    static void draw(long enc,long pack,long texture,int width,int height,int water,float time) {
        try (MemoryStack stack=MemoryStack.stackPush()) {
            long error=errorBuffer(stack);
            int ok=(int)DRAW.invokeExact(enc,pack,texture,width,height,water,time,error,4096);
            if (ok==0) throw new IllegalStateException(MemoryUtil.memUTF8(error));
        } catch (Throwable e) { throw rethrow(e); }
    }
    static void release(long pack) { try { RELEASE.invokeExact(pack); } catch(Throwable e) { throw rethrow(e); } }
    private static RuntimeException rethrow(Throwable e) { if(e instanceof Error error) throw error; return e instanceof RuntimeException r?r:new IllegalStateException(e); }
    private static long errorBuffer(org.lwjgl.system.MemoryStack stack) {long error=stack.nmalloc(1,4096);org.lwjgl.system.MemoryUtil.memPutByte(error,(byte)0);return error;}
}
