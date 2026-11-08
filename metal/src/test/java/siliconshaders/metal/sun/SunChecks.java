package siliconshaders.metal.sun;

import java.nio.file.Files;

public final class SunChecks {
    public static void run() throws Exception {
        SunCullingChecks.run();
        projectionChecks();
        historyChangeChecks();
        packetChecks();
        var directory=Files.createTempDirectory("siliconshaders-sun-");
        try {
            var settings=new SunSettings(directory);
            require(!settings.state().enabled(),"Native shader enabled before opt-in");
            require(settings.state().shadowCulling(),"Shadow culling did not default to on");
            var comparison=new SunSettings(directory.resolve("culling-check"));comparison.culling(false);
            require(!new SunSettings(directory.resolve("culling-check")).state().shadowCulling(),"Culling toggle did not persist");
            comparison.nextLevels();comparison.distance(80);comparison.nextSize();comparison.smooth(false);comparison.enabled(true);
            require(!comparison.state().shadowCulling(),"Another option reset culling");
            require(settings.state().filterOptimization(),"Filter optimization must default to on");
            var filters=new SunSettings(directory.resolve("filter-check"));filters.filterOptimization(false);
            require(!new SunSettings(directory.resolve("filter-check")).state().filterOptimization(),"Filter toggle did not persist");
            filters.nextLevels();filters.distance(80);filters.nextSize();filters.smooth(false);filters.enabled(true);filters.culling(false);
            require(!filters.state().filterOptimization(),"Another option reset filter optimization");
            filters.filterOptimization(true);
            require(new SunSettings(directory.resolve("filter-check")).state().filterOptimization(),"Filter could not be re-enabled");
            require(settings.state().casterSelection(),"Parallel caster selection must default to on");
            var selection=new SunSettings(directory.resolve("selection-check"));selection.casterSelection(false);
            require(!new SunSettings(directory.resolve("selection-check")).state().casterSelection(),"Selection toggle did not persist");
            selection.enabled(true);selection.nextSize();selection.distance(128);selection.nextLevels();selection.smooth(false);selection.culling(false);selection.filterOptimization(false);
            require(!selection.state().casterSelection(),"Another option reset caster selection");
            selection.casterSelection(true);
            require(!selection.state().filterOptimization()&&!selection.state().shadowCulling(),"Selection toggle reset another option");
            require(new SunSettings(directory.resolve("selection-check")).state().casterSelection(),"Selection could not be re-enabled");
            require(settings.state().receiverScan(),"Receiver scan must default to optimized");
            var scan=new SunSettings(directory.resolve("scan-check"));scan.receiverScan(false);
            require(!new SunSettings(directory.resolve("scan-check")).state().receiverScan(),"Receiver scan toggle did not persist");
            scan.enabled(true);scan.nextSize();scan.distance(128);scan.nextLevels();scan.smooth(false);scan.culling(false);scan.filterOptimization(false);scan.casterSelection(false);
            require(!scan.state().receiverScan(),"Another option reset receiver scan");
            scan.receiverScan(true);
            require(!scan.state().filterOptimization()&&!scan.state().casterSelection()&&!scan.state().shadowCulling(),"Receiver scan reset another option");
            require(new SunSettings(directory.resolve("scan-check")).state().receiverScan(),"Receiver scan could not be re-enabled");
            require(settings.state().memoryOptimization(),"Memory optimization must default to on");
            var memory=new SunSettings(directory.resolve("memory-check"));memory.memoryOptimization(false);
            require(!new SunSettings(directory.resolve("memory-check")).state().memoryOptimization(),"Memory toggle did not persist");
            memory.enabled(true);memory.nextSize();memory.distance(128);memory.nextLevels();memory.smooth(false);memory.culling(false);memory.filterOptimization(false);memory.casterSelection(false);memory.receiverScan(false);
            require(!memory.state().memoryOptimization(),"Another option reset memory optimization");
            memory.memoryOptimization(true);
            require(!memory.state().filterOptimization()&&!memory.state().casterSelection()&&!memory.state().receiverScan()&&!memory.state().shadowCulling(),"Memory toggle reset another option");
            require(new SunSettings(directory.resolve("memory-check")).state().memoryOptimization(),"Memory mode could not be re-enabled");
            settings.enabled(true);
            require(new SunSettings(directory).state().enabled(),"Shader enable state did not persist");
            require(settings.state().smooth(),"Smooth shadow motion must default to on");
            require(settings.state().shadowDistance()==64,"Legacy shadow distance did not default to 64");
            for(int distance:new int[]{32,80,128,256}) {
                settings.distance(distance);
                require(new SunSettings(directory).state().shadowDistance()==distance,"Shadow distance did not persist");
                settings.smooth(false);settings.smooth(true);
                require(settings.state().shadowDistance()==distance,"Another option reset shadow distance");
            }
            boolean rejected=false;try{settings.distance(512);}catch(IllegalArgumentException expected){rejected=true;}
            require(rejected&&settings.state().shadowDistance()==256,"Invalid distance was applied");settings.distance(64);
            require(settings.state().shadowLevels()==3,"Default level count is not 3");
            for(int levels:new int[]{4,2,3}) {
                settings.nextLevels();require(settings.state().shadowLevels()==levels,"Incorrect shadow level cycle");
                require(new SunSettings(directory).state().equals(settings.state()),"Shadow levels did not persist");
                settings.smooth(false);settings.smooth(true);settings.distance(80);settings.enabled(false);settings.enabled(true);
                require(settings.state().shadowLevels()==levels,"Another control reset the level count");
            }
            settings.distance(64);
            settings.smooth(false);
            require(!new SunSettings(directory).state().smooth(),"Smoothing comparison toggle did not persist");
            for(int size:new int[]{2048,4096,0,512,1024}) {
                settings.nextSize();require(settings.state().shadowSize()==size,"Incorrect resolution cycle");
                require(settings.state().enabled(),"Disabling shadows also disabled sunlight");
                require(new SunSettings(directory).state().equals(settings.state()),"Quality selection did not persist");
            }
            Files.writeString(directory.resolve("config/siliconshaders-sun.json"),"{\"enabled\":true,\"shadowSize\":2048}");
            var migrated=new SunSettings(directory).state();
            require(migrated.enabled()&&migrated.shadowSize()==2048&&migrated.smooth()&&migrated.shadowLevels()==3,"Existing quality setting was not migrated");
            require(migrated.filterOptimization()&&migrated.casterSelection()&&migrated.receiverScan()&&migrated.memoryOptimization(),"Legacy settings disabled filter optimization");
            Files.writeString(directory.resolve("config/siliconshaders-sun.json"),"{\"enabled\":true,\"shadowSize\":999999}");
            var invalid=new SunSettings(directory);require(!invalid.state().enabled()&&!invalid.issue().isEmpty(),"Invalid shadow allocation accepted");
            Files.writeString(directory.resolve("config/siliconshaders-sun.json"),"{\"enabled\":true,\"shadowSize\":2048,\"shadowDistance\":64,\"shadowLevels\":99}");
            var badLevels=new SunSettings(directory);require(!badLevels.state().enabled()&&!badLevels.issue().isEmpty(),"Invalid level count accepted");
            Files.writeString(directory.resolve("config/siliconshaders-sun.json"),"bad json");
            require(!new SunSettings(directory).state().enabled(),"Corrupt configuration enabled the shader");
            System.out.println("PASS: native sun settings, quality cycle, sunlight without shadows, persistence and invalid allocation rejection");
        } finally {
            try(var files=Files.walk(directory)) { for(var path:files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
        }
    }
    private static void packetChecks() {
        var packet=org.lwjgl.system.MemoryUtil.memAlloc(Math.max(SunFrames.BLEND_BYTES,SunFrames.CASCADE_BYTES));
        try {
            long p=org.lwjgl.system.MemoryUtil.memAddress(packet);
            float boundary=(float)(1848*SunFrames.ANGLE_STEP);
            float[] upper=new float[16];
            SunFrames.write(p,Math.nextDown(boundary),-240.25,72.5,-480.75,2048,true,true,7,123,0.9f);
            float before=packet.getFloat(432);
            for(int i=0;i<16;i++)upper[i]=packet.getFloat(288+i*4);
            require(packet.getInt(436)==1&&packet.getLong(440)==123&&packet.getInt(448)==-241,"Blend cache metadata layout changed");
            SunFrames.write(p,Math.nextUp(boundary),-240.25,72.5,-480.75,2048,true,true,7,123,0.9f);
            require(before>0.999f&&packet.getFloat(432)<0.001f,"Blend does not meet at a solar sample boundary");
            for(int i=0;i<16;i++)require(upper[i]==packet.getFloat(144+i*4),"Adjacent intervals do not share an identical light matrix");
            require(packet.getInt(136)==2048&&packet.getInt(140)==1,"Physical sun frame layout changed");
            require(Math.abs(packet.getFloat(96)+(float)Math.sin(Math.nextUp(boundary)))<1e-6,"Physical sunlight was quantized with shadow samples");
            SunFrames.write(p,Math.nextDown((float)(2*Math.PI)),0,8,0,2048,true,true,1,124,1);
            for(int i=0;i<16;i++)upper[i]=packet.getFloat(288+i*4);
            require(packet.getFloat(432)>0.999f,"Solar wrap did not reach its shared endpoint");
            SunFrames.write(p,0,0,8,0,2048,true,true,1,124,1);
            for(int i=0;i<16;i++)require(upper[i]==packet.getFloat(144+i*4),"Solar wrap changed the shared sample");
            SunFrames.write(p,boundary,0,8,0,0,true,true,1,124,1);
            require(packet.getInt(436)==0&&packet.getInt(140)==1,"Sunlight-only mode enabled shadow blending");
            SunFrames.write(p,boundary,0,8,0,2048,false,true,1,124,1);
            for(int i=0;i<SunFrames.BLEND_BYTES;i++)require(packet.get(i)==0,"Disabled packet retained shadow state");
            var view=new org.joml.Matrix4f().perspective(1.2f,1.6f,0.1f,512,true);
            SunFrames.writeTemporal(p,boundary,-240.25,72.5,-480.75,2048,true,7,456,0.9f,view,0.002f,53);
            require(packet.getInt(136)==2048&&packet.getInt(140)==1,"Temporal packet changed the physical sun layout");
            require(packet.getDouble(208)==-240.25&&packet.getDouble(216)==72.5&&packet.getDouble(224)==-480.75,"Temporal camera lost precision");
            require(packet.getLong(232)==456&&packet.getFloat(240)==0.002f&&packet.getInt(244)==53,"Temporal revision, time or sample sequence layout drifted");
            float[] projection=new float[16];view.get(projection);
            for(int i=0;i<16;i++)require(packet.getFloat(144+i*4)==projection[i],"Temporal camera projection layout drifted");
            byte[] nearPacket=null;
            for(int levels:new int[]{2,3,4})for(int size:new int[]{0,512,1024,2048,4096})for(int distance:new int[]{32,64,128,256}) {
                SunFrames.writeCascaded(p,boundary,0,8,0,size,true,true,7,456,0.9f,view,0.002f,53,distance,levels);
                require(packet.getInt(252)==levels&&packet.getInt(248)==1,"Shadow level metadata drifted");
                require(packet.getInt(136)==size&&packet.getFloat(112)==24,"Near shadow density changed");
                float lastRange=0;
                for(int i=0;i<levels;i++) {
                    int at=SunFrames.cascadeOffset(i),expected=size==0?0:Math.max(256,size>>i);
                    require(packet.getInt(at+136)==expected&&packet.getFloat(at+116)==1.0f/Math.max(1,expected),"Resolution/texel footprint mismatch");
                    float range=packet.getFloat(at+112);require(range>lastRange,"Cascade coverage did not grow");lastRange=range;
                    for(int c=0;c<4;c++)require(packet.getFloat(at+96+c*4)==packet.getFloat(96+c*4),"Cascades changed the physical sun");
                }
                require(lastRange==distance,"Final cascade ignored shadow distance");
                for(int i=levels;i<4;i++)for(int at=SunFrames.cascadeOffset(i);at<SunFrames.cascadeOffset(i)+144;at++)require(packet.get(at)==0,"Unused level retained stale data");
                if(size==4096) {
                    byte[] bytes=new byte[144];for(int i=0;i<144;i++)bytes[i]=packet.get(i);
                    if(nearPacket==null)nearPacket=bytes;else require(java.util.Arrays.equals(bytes,nearPacket),"Level count/distance changed the accepted near packet");
                }
                SunFrames.writeCascaded(p,boundary,0,8,0,size,true,false,7,456,0.9f,view,0.002f,53,distance,levels);
                require(packet.getInt(248)==0&&packet.getInt(140)==1,"Smoothing toggle removed sunlight");
            }
            SunFrames.writeCascaded(p,boundary,0,8,0,4096,false,true,1,1,1,view,0.002f,0);
            for(int i=0;i<4;i++)for(int at=SunFrames.cascadeOffset(i);at<SunFrames.cascadeOffset(i)+144;at++)require(packet.get(at)==0,"Disabling sunlight left a cascade active");

        } finally { org.lwjgl.system.MemoryUtil.memFree(packet); }
        System.out.println("PASS: solar interpolation boundary continuity, physical sun, cache keys and disabled packet layout");
    }
    private static void projectionChecks() {
        var sun=new org.joml.Vector3f(-0.6f,0.8f,0);
        for(int size:new int[]{512,1024,2048,4096}) {
            // Move less than one light-space texel: a stationary object must retain its shadow-map coordinates.
            double x=0.003,y=0.002,z=0.001;
            var a=SunProjection.create(sun,0,0,0,size,64).transformPosition(new org.joml.Vector3f(5,2,1));
            var b=SunProjection.create(sun,x,y,z,size,64).transformPosition(new org.joml.Vector3f((float)(5-x),(float)(2-y),(float)(1-z)));
            require(Math.abs(a.x-b.x)<2e-7 && Math.abs(a.y-b.y)<2e-7,"Camera motion slid the world-anchored shadow grid");
            // Identical camera-relative scenes translated by whole anchor cells must be bit-identical,
            // including while the sun rotates. Absolute coordinates previously changed the raster phase.
            for(int frame=0;frame<512;frame++) {
                var movingSun=direction(0.9236124f+frame*0.000015f);
                var near=SunProjection.create(movingSun,0,8,0,size,64);
                var far=SunProjection.create(movingSun,-240,72,-480,size,64);
                var farther=SunProjection.create(movingSun,10000,8,10000,size,64);
                require(near.equals(far)&&near.equals(farther),"Absolute world position changed the rotating shadow projection");
            }
            // Negative positions inside one cell obey the same camera-offset convention.
            var before=SunProjection.create(sun,-1.001,8.2,-3.1,size,64).transformPosition(new org.joml.Vector3f(4.001f,-6.2f,4.1f));
            var after=SunProjection.create(sun,-0.999,8.2,-3.1,size,64).transformPosition(new org.joml.Vector3f(3.999f,-6.2f,4.1f));
            require(before.distance(after)<2e-7,"Negative world positions caused camera-relative shadow sliding");
        }
        // The previous choice of up axis changed at sun.y = .98. Neither this crossing nor noon may rotate the grid.
        for(float middle:new float[]{(float)Math.acos(0.98),0}) {
            var a=SunProjection.create(direction(middle-0.00001f),0,0,0,2048,64);
            var b=SunProjection.create(direction(middle+0.00001f),0,0,0,2048,64);
            var p=a.transformPosition(new org.joml.Vector3f(3,4,7));
            var q=b.transformPosition(new org.joml.Vector3f(3,4,7));
            require(Float.isFinite(p.x)&&Float.isFinite(q.x)&&p.distance(q)<0.00001f,"Sun motion rotated or broke the shadow basis");
        }
        System.out.println("PASS: local shadow anchor at all resolutions; translation invariance through solar motion; camera movement and continuous noon basis");
    }
    private static void historyChangeChecks() {
        var changes=new SunHistoryChanges();
        for(double cameraX:new double[]{0,-240,10000}) {
            var light=SunProjection.create(new org.joml.Vector3f(.6f,.8f,0),cameraX,8,0,2048,64);
            changes.changed((int)cameraX+4096,0,0);
            require(!changes.consume(light,cameraX,8,0,64),"Distant chunk upload reset shadow history");
            // A caster behind the camera can shadow visible ground. Test light volume, not camera frustum.
            changes.changed((int)cameraX,16,16);
            require(changes.consume(light,cameraX,8,0,64),"Offscreen caster update was ignored");
            require(!changes.consume(light,cameraX,8,0,64),"Changes were not consumed once");
            // A removal is represented by its OLD section origin, even if the new slot is empty.
            changes.changed((int)cameraX-16,0,0);
            require(changes.consume(light,cameraX,8,0,64),"Removed caster did not invalidate history");
            for(int i=0;i<1025;i++)changes.changed(500000,500000,500000);
            require(changes.consume(light,cameraX,8,0,64),"Dirty-list overflow lost geometry changes");
            require(!changes.consume(light,cameraX,8,0,64),"Overflow did not clear after reset");
        }
        System.out.println("PASS: distant chunk uploads preserve history; offscreen/removed casters and overflow reset it, including translated worlds");
    }
    private static org.joml.Vector3f direction(float angle) { return new org.joml.Vector3f(-(float)Math.sin(angle),(float)Math.cos(angle),0); }
    private static void require(boolean yes,String message) { if(!yes) throw new AssertionError(message); }
}
