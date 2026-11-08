package siliconshaders.metal.own;

import siliconshaders.metal.sun.SunFrames;
import org.lwjgl.system.MemoryUtil;

/** Pure metadata/clock contract, without initializing Minecraft or a graphics surface. */
public final class WindChecks {
    private static void require(boolean condition,String message) { if(!condition)throw new AssertionError(message); }
    private static int weight(int header,int corner) { return (header>>>(23+2*corner))&3; }
    public static void main(String[] args) {
        float[] lower={0,0,0, 1,0,0, 1,1,0, 0,1,0};
        float[] upper={0,1,0, 1,1,0, 1,2,0, 0,2,0};
        int grass=OwnWind.header(2,0,lower),leaves=OwnWind.header(1,0,lower);
        require(OwnWind.header(0,0,lower)==0,"Static geometry acquired wind");
        require(weight(grass,0)==0&&weight(grass,1)==0&&weight(grass,2)==2&&weight(grass,3)==2,"Plant root/top weights");
        for(int c=0;c<4;c++)require(weight(leaves,c)==2,"Leaves need a continuous whole-surface field");
        int tallLower=OwnWind.header(3,0,lower),tallUpper=OwnWind.header(4,1,upper);
        require(weight(tallLower,2)==weight(tallUpper,0)&&weight(tallUpper,2)==2,"Tall plant joint disconnected");
        int edgeLower=OwnWind.header(3,15,new float[]{0,15,0,1,15,0,1,16,0,0,16,0});
        int edgeUpper=OwnWind.header(4,0,lower);
        require(weight(edgeLower,2)==weight(edgeUpper,0),"Tall plant joint split across section boundary");
        require((grass&~OwnWind.MASK)==0&&(leaves&~OwnWind.MASK)==0,"Wind overwrote position, color, UV or stub bits");
        int stub=OwnQrec.STUB|grass;
        require((stub&OwnQrec.STUB)!=0&&weight(stub,2)==2,"Stub flag/weights overlap");
        OwnQuads.Layer layer=new OwnQuads.Layer();layer.windKind=2;layer.windBlockY=0;
        layer.quad(lower,new int[]{-1,-1,-1,-1},new float[]{0,0,1,0,1,1,0,1},new int[]{0x00f000f0,0x00f000f0,0x00f000f0,0x00f000f0});
        require(layer.count[6]==1,"Animated quad retained a static facing plane");
        require((MemoryUtil.memGetInt(layer.qaddr[6])&OwnWind.MASK)==grass,"Mesher lost wind metadata");
        require((layer.lo[6][0]&255)<16&&(layer.hi[6][0]&255)>17,"Animated culling box excludes wind excursion");
        long copied=MemoryUtil.nmemAlloc(80),stubCopy=MemoryUtil.nmemAlloc(64);
        try {
            layer.copyRecsTo(copied,-1,0,0,0);
            require((MemoryUtil.memGetInt(copied)&OwnWind.MASK)==grass,"Sorting/copy lost wind metadata");
            layer.windKind=1;float[] irregular=lower.clone();irregular[6]=.5f;
            layer.quad(irregular,new int[]{-1,-1,-1,-1},new float[]{0,0,1,0,1,1,0,1},new int[]{0x00f000f0,0x00f000f0,0x00f000f0,0x00f000f0});
            require(OwnQrec.isStub(layer.qaddr[6]+40),"Irregular model did not exercise stub fallback");
            require((MemoryUtil.memGetInt(layer.qaddr[6]+40)&OwnWind.MASK)==leaves,"Stub fallback lost wind metadata");
            layer.copyRecsTo(copied,-1,stubCopy,17,0);
            require((MemoryUtil.memGetInt(copied+40)&OwnWind.MASK)==leaves&&MemoryUtil.memGetInt(copied+44)==17,"Stub relocation lost metadata or pointer");
        } finally { MemoryUtil.nmemFree(copied);MemoryUtil.nmemFree(stubCopy); }
        layer.reset();require(layer.windKind==0,"Worker reset leaked previous plant classification");
        long packet=MemoryUtil.nmemCalloc(1,SunFrames.CASCADE_BYTES);
        try {
            OwnWind.packet(packet,4,123.5f,1.75f);
            for(int i=0;i<4;i++) {
                long at=packet+SunFrames.cascadeOffset(i);
                require(MemoryUtil.memGetFloat(at+76)==1.75f&&MemoryUtil.memGetFloat(at+92)==123.5f,"Cascade wind differs");
                require(MemoryUtil.memGetInt(at+72)==0&&MemoryUtil.memGetInt(at+88)==0&&MemoryUtil.memGetInt(at+96)==0,"Wind overwrote physical frame");
            }
        } finally { MemoryUtil.nmemFree(packet); }
        OwnWind.Clock clear=new OwnWind.Clock(),rain=new OwnWind.Clock(),storm=new OwnWind.Clock();
        long now=1_000_000_000L;
        for(int i=0;i<600;i++) {
            now+=16_666_667L;clear.update(now,false,0,0);rain.update(now,false,1,0);storm.update(now,false,1,1);
        }
        require(clear.strength()==1&&rain.strength()>1.49f&&storm.strength()>1.74f,"Weather intensity not applied");
        float a=clear.update(now,false,0,0),b=rain.update(now,false,1,0),c=storm.update(now,false,1,1);
        require(a<b&&b<c,"Weather speed not applied");
        require(storm.update(now+60_000_000_000L,true,0,0)==c,"Pause advanced wind phase");
        float strength=storm.strength();storm.update(now+60_016_666_667L,false,0,0);
        require(storm.strength()<strength&&storm.strength()>1.7f,"Weather transition jumped");
        System.out.println("PASS: plant roots, tall-plant joints, leaves, reserved header/stub bits, four cascade packets, weather speed/intensity, smooth transitions and pause");
    }
}
