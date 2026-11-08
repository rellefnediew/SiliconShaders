// Entities over the cached far shadow map: drawing them into a small scratch image of their rectangle (and restoring
// only the previous rectangle) must give exactly the image of the whole-map copy, frame for frame, while the entity moves.
// Usage: sun_far_actor_check TERRAIN.metal SUN.metal
#include "sun_test_common.h"
extern int mco_sun_culling(void *,const void *);
extern int mco_sun_casters(void *,const void *,int);
extern void mco_sun_far_cache(void *,int,uint64_t);
extern int mco_sun_debug_far_mode(void *);
extern void mco_sun_debug_far_actors(void *,int,uint64_t *);
typedef struct { uint64_t vertices,indices,texture,sampler;simd_float4x4 model;simd_uint4 layout;simd_int4 draw;simd_float4 config,lo,hi; } Caster;
_Static_assert(sizeof(Caster)==176,"Caster ABI");

enum { FRAMES=10, SIDE=128 };

static double run(NSString *source,BOOL scratch,uint8_t images[FRAMES][SIDE*SIDE*4],uint64_t stats[3],int modes[FRAMES],int farResolution,int frames) {
    char error[16384]={0},name[256]={0};Ctx *ctx=mc_create(name,sizeof name,error,sizeof error);require(ctx!=NULL,@(error));
    void *own=mco_new(ctx,source.UTF8String,0,0,0,error,sizeof error);require(own!=NULL,@(error));
    Enc *enc=mc_enc_new(ctx);mco_sun_debug_far_actors(own,scratch?0:1,NULL);mco_sun_far_cache(own,1,0);
    // Ground quad (receiver) and a small quad above it (the entity), as in sun_caster_check.
    Vertex vertices[8]={0};
    for(int q=0;q<2;q++)for(int c=0;c<4;c++) {
        Vertex *v=&vertices[q*4+c];float radius=q?0.4:4;
        v->p[0]=(c==1||c==2)?radius:-radius;v->p[1]=q?2:0;v->p[2]=((c>=2)?radius:-radius)+(q?1.5:0);
        memset(v->color,255,4);v->light[0]=v->light[1]=240;v->uv[0]=v->uv[1]=0.5;
    }
    Section section={.count=2};Record records[2]={{0},{.start=1}};records[1].meta=2u<<9;
    uint32_t lists[2]={0,1},args[5]={384,2,0,0,0};
    id<MTLBuffer> arena=buffer(ctx,vertices,sizeof vertices),sections=buffer(ctx,&section,sizeof section),recs=buffer(ctx,records,sizeof records);
    id<MTLBuffer> list=buffer(ctx,lists,sizeof lists),draw=buffer(ctx,args,sizeof args);
    simd_float4x4 projection={.columns={{0.2,0,0,0},{0,0,0.1,0},{0,0.2,0,0},{0,0,1.2,1}}};
    uint8_t globals[48]={0},terrain[80]={0};int camera[3]={0,8,0};memcpy(globals,camera,12);
    simd_float4x4 identity=matrix_identity_float4x4;memcpy(terrain,&identity,64);int textureSize[2]={4,4};memcpy(terrain+64,textureSize,8);
    float fog[10]={0,0,0,0,1000,2000,1000,2000,1000,1000};
    id<MTLBuffer> p=buffer(ctx,&projection,64),g=buffer(ctx,globals,sizeof globals),t=buffer(ctx,terrain,sizeof terrain),f=buffer(ctx,fog,sizeof fog);
    int64_t uniforms[8]={(int64_t)p,0,(int64_t)g,0,(int64_t)t,0,(int64_t)f,0};
    id<MTLTexture> atlas=white(ctx),light=white(ctx);
    MTLSamplerDescriptor *sd=[[[MTLSamplerDescriptor alloc] init] autorelease];sd.minFilter=sd.magFilter=MTLSamplerMinMagFilterNearest;
    id<MTLSamplerState> sampler=[ctx->device newSamplerStateWithDescriptor:sd];
    Sun parameters={.clip={.columns={{.16,0,-.03,0},{-.12,0,-.04,0},{0,.2,0,0},{-.96,0,.18,1}}},
        .cam={0,8,0,0},.sun={.6,.8,0,1},.config={5,1.0f/farResolution,0.00015,1},.sections=1,.resolution=farResolution,.on=1};
    uint16_t actorIndices[]={0,1,2,2,3,0};
    id<MTLBuffer> actorVertices=buffer(ctx,vertices+4,4*sizeof(Vertex)),actorIndex=buffer(ctx,actorIndices,sizeof actorIndices);
    double times[256];int timed=0;
    for(int step=0;step<frames;step++) {int frame=step%FRAMES;
        // The entity walks across the receiver; frame 7 has no entity at all, frame 8 brings it back.
        float x=-1.2f+frame*.3f;int count=frame==7?0:1;
        Caster caster={.vertices=(uint64_t)actorVertices,.indices=(uint64_t)actorIndex,.texture=(uint64_t)atlas,.sampler=(uint64_t)sampler,
            .model=matrix_identity_float4x4,.layout={sizeof(Vertex),0,16,12},.draw={6,0,0,0},.config={0,0,64,0},.lo={x-1,-7,1,0},.hi={x+1,-5,2,0}};
        caster.model.columns[3].x=x;caster.model.columns[3].y=-8;
        struct { uint32_t count;float nearRadius;uint32_t pad[2];simd_float4 planes[16]; } cv={0};
        require(mco_sun_culling(own,&cv)==0,@"Caster culling packet failed");
        require(mco_sun_casters(own,&caster,count)>=0,@"Caster upload failed");
        MTLTextureDescriptor *td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:SIDE height:SIDE mipmapped:NO];
        td.storageMode=MTLStorageModeShared;td.usage=MTLTextureUsageRenderTarget;id<MTLTexture> color=[ctx->device newTextureWithDescriptor:td];
        td.pixelFormat=MTLPixelFormatDepth32Float;td.storageMode=MTLStorageModePrivate;id<MTLTexture> depth=[ctx->device newTextureWithDescriptor:td];
        id<MTLTexture> targets[]={color};float clear[]={0,0,0,1};mc_render_begin(enc,1,targets,clear,depth,1,0,SIDE,SIDE);
        // Two levels; the near level is moved off the receiver so it is lit by the far (cached) level only.
        uint8_t packet[688]={0};memcpy(packet,&parameters,144);
        Sun far=parameters;float ratio=64.0f/24;for(int c=0;c<4;c++){far.clip.columns[c].x/=ratio;far.clip.columns[c].y/=ratio;}far.config.x*=ratio;
        far.resolution=farResolution;far.config.y=1.0f/farResolution;
        memcpy(packet+256,&far,144);*(uint32_t *)(packet+252)=2;
        ((Sun *)packet)->clip.columns[3].x+=3;
        require(mco_sun_frame(own,enc,packet,400,arena,sections,recs,atlas,sampler,error,sizeof error)>=0,@(error));
        modes[frame]=mco_sun_debug_far_mode(own);
        uint8_t drawFrame[80]={0};*(int32_t *)(drawFrame+4)=1;
        require(mco_draw(own,enc,0,drawFrame,sizeof drawFrame,arena,sections,recs,list,draw,0,uniforms,atlas,sampler,light,sampler,nil,error,sizeof error)>0,@(error));
        id<MTLCommandBuffer> command=mc_enc_commit(enc);[command waitUntilCompleted];require(command.status!=MTLCommandBufferStatusError,command.error.localizedDescription);
        if(step>=2&&timed<256)times[timed++]=(command.GPUEndTime-command.GPUStartTime)*1000;
        if(images)[color getBytes:images[frame] bytesPerRow:SIDE*4 fromRegion:MTLRegionMake2D(0,0,SIDE,SIDE) mipmapLevel:0];
        [color release];[depth release];
    }
    mco_sun_debug_far_actors(own,-1,stats);
    for(int i=0;i<timed;i++)for(int j=i+1;j<timed;j++)if(times[j]<times[i]){double v=times[i];times[i]=times[j];times[j]=v;}
    return timed?times[timed/2]:0;
}

int main(int argc,const char **argv) {
    @autoreleasepool {
        require(argc==3,@"Usage: sun_far_actor_check TERRAIN.metal SUN.metal");
        NSString *source=[NSString stringWithContentsOfFile:@(argv[1]) encoding:NSUTF8StringEncoding error:nil];
        NSString *sunSource=[NSString stringWithContentsOfFile:@(argv[2]) encoding:NSUTF8StringEncoding error:nil];
        require(source&&sunSource,@"Shader sources missing");source=[source stringByAppendingString:sunSource];
        static uint8_t whole[FRAMES][SIDE*SIDE*4],scratch[FRAMES][SIDE*SIDE*4];uint64_t wholeStats[3],scratchStats[3];int wholeModes[FRAMES],scratchModes[FRAMES];
        run(source,NO,whole,wholeStats,wholeModes,512,FRAMES);run(source,YES,scratch,scratchStats,scratchModes,512,FRAMES);
        int shadowed=0;
        for(int frame=0;frame<FRAMES;frame++) {
            int differing=0,dark=0;
            for(int i=0;i<SIDE*SIDE;i++){if(memcmp(whole[frame]+i*4,scratch[frame]+i*4,4))differing++;if(whole[frame][i*4]<150)dark++;}
            printf("frame %d: far mode %d/%d, shadowed pixels %d, differing pixels %d\n",frame,wholeModes[frame],scratchModes[frame],dark,differing);
            require(differing==0,[NSString stringWithFormat:@"Frame %d differs from the whole-map copy",frame]);
            require(wholeModes[frame]==scratchModes[frame],@"Far cache decisions differ");
            if(frame&&frame!=7)require(scratchModes[frame]==2,@"Far cache was not reused, so the entity layer path was not exercised");
            if(frame!=7&&dark>0)shadowed++;
        }
        require(shadowed>=8,@"The entity did not cast a far shadow in the test scene");
        // Shadows must move: the first and last entity frames differ.
        require(memcmp(whole[0],whole[FRAMES-1],SIDE*SIDE*4)!=0,@"Entity shadow did not move");
        printf("whole-map path: %llu full copies; scratch path: %llu full copies, %llu rectangle restores, %llu scratch draws\n",
            wholeStats[0],scratchStats[0],scratchStats[1],scratchStats[2]);
        require(wholeStats[0]>=9,@"Reference run did not copy the whole map each entity frame");
        require(scratchStats[0]<=1&&scratchStats[2]>=7,@"Scratch path did not replace the whole-map copies");
        for(int resolution=2048;resolution<=4096;resolution*=2){uint64_t s[3];int m[FRAMES];
            double a=run(source,NO,NULL,s,m,resolution,60),b=run(source,YES,NULL,s,m,resolution,60);
            printf("far map %dx%d with one entity: whole-map copy %.3f ms, scratch rectangle %.3f ms GPU per frame (median)\n",resolution,resolution,a,b);}
        printf("PASS: far-map entity shadows drawn in a scratch rectangle are identical to whole-map copies over %d moving frames, with an entity leaving and returning\n",FRAMES);
    }
    return 0;
}
