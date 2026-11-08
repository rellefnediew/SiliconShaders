// Compare animated QREC terrain/caster vertices with explicitly displaced raw vertices.
// All rendering is offscreen; no Minecraft process or world is opened.
#include "sun_test_common.h"
#include "mcsun_surface.h"
extern int mco_sun_surface(void *,const void *);
extern int mco_sun_finish(void *,Enc *,char *,int);
extern void mco_sun_geometry_optimization(void *,int);
extern void mco_sun_far_cache(void *,int,uint64_t);
extern int mco_sun_debug_far_mode(void *);
extern void mco_sun_gbuffer(void *,int,int);
extern int mco_sun_debug_lighting(void *);
extern id<MTLTexture> mco_sun_debug_cascade_map(void *,int);

static uint32_t bounds(int x,int y,int z) { return (x+16)|((y+16)<<8)|((z+16)<<16); }
static uint16_t code(float p) { return (uint16_t)lroundf((p+8)*2048); }
static void pack(uint32_t *q,const Vertex *v,uint32_t wind) {
    memset(q,0,40);q[0]=wind;q[6]=q[7]=0xffffffff;q[8]=q[9]=0xf0f0f0f0;
    for(int a=0;a<3;a++) {
        uint16_t first=code(v[0].p[a]),second=first;
        for(int c=1;c<4;c++)if(code(v[c].p[a])!=first)second=code(v[c].p[a]);
        q[1+a]=first|((uint32_t)second<<16);
        for(int c=0;c<4;c++)if(code(v[c].p[a])!=first)q[0]|=1u<<(3*c+a);
    }
    q[4]=q[5]=0x80008000;
}
static void getDepth(Ctx *ctx,id<MTLTexture> map,float *out) {
    NSUInteger row=map.width*4;
    id<MTLBuffer> read=[ctx->device newBufferWithLength:row*map.height options:MTLResourceStorageModeShared];
    id<MTLCommandBuffer> cmd=[ctx->queue commandBuffer];id<MTLBlitCommandEncoder> b=[cmd blitCommandEncoder];
    [b copyFromTexture:map sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0,0,0)
        sourceSize:MTLSizeMake(map.width,map.height,1) toBuffer:read destinationOffset:0 destinationBytesPerRow:row destinationBytesPerImage:row*map.height];
    [b endEncoding];[cmd commit];[cmd waitUntilCompleted];require(cmd.status==MTLCommandBufferStatusCompleted,cmd.error.localizedDescription);
    memcpy(out,read.contents,row*map.height);[read release];
}
int main(int argc,const char **argv) {
 @autoreleasepool {
    require(argc==3||argc==4,@"Usage: sun_wind_check TERRAIN SUN [native-light|night-transition]");
    BOOL nativeLight=argc==4,nightTransition=nativeLight&&!strcmp(argv[3],"night-transition");
    require(!nativeLight||nightTransition||!strcmp(argv[3],"native-light"),@"Unknown mode");
    NSString *terrain=[NSString stringWithContentsOfFile:@(argv[1]) encoding:NSUTF8StringEncoding error:nil];
    NSString *source=[terrain stringByAppendingString:[NSString stringWithContentsOfFile:@(argv[2]) encoding:NSUTF8StringEncoding error:nil]];
    char error[16384]={0},name[256]={0};Ctx *ctx=mc_create(name,sizeof name,error,sizeof error);require(ctx!=NULL,[NSString stringWithUTF8String:error]);
    void *animated=mco_new(ctx,[@"#define OWN_QREC 1\n" stringByAppendingString:source].UTF8String,1,0,0,error,sizeof error);
    require(animated!=NULL,[NSString stringWithUTF8String:error]);
    void *reference=mco_new(ctx,source.UTF8String,0,0,0,error,sizeof error);require(reference!=NULL,[NSString stringWithUTF8String:error]);
    NSString *probe=@"\nkernel void wind_probe(device const float4 *input [[buffer(0)]], device float4 *output [[buffer(1)]], constant uint4 &tag [[buffer(2)]], constant float4 &frame [[buffer(3)]], constant int4 &origin [[buffer(4)]], uint i [[thread_position_in_grid]]) { output[i]=float4(ownWindPosition(input[i].xyz,origin.xyz,tag[i/4],i&3,frame.xy),1); }\n";
    NSError *issue=nil;id<MTLLibrary> library=[ctx->device newLibraryWithSource:[terrain stringByAppendingString:probe] options:nil error:&issue];require(library!=nil,issue.localizedDescription);
    id<MTLFunction> function=[library newFunctionWithName:@"wind_probe"];
    id<MTLComputePipelineState> pipeline=[ctx->device newComputePipelineStateWithFunction:function error:&issue];require(pipeline!=nil,issue.localizedDescription);
    Vertex original[8]={0};simd_float4 input[8];
    for(int q=0;q<2;q++)for(int c=0;c<4;c++) {
        Vertex *v=&original[q*4+c];
        v->p[0]=(c==1||c==2)?(q?.4:4):(q?-.4:-4);
        v->p[1]=q?(c>=2?2:0):0;v->p[2]=q?1.5:(c>=2?4:-4);
        for(int a=0;a<3;a++)v->p[a]=(float)code(v->p[a])/2048-8;
        memset(v->color,255,4);v->uv[0]=v->uv[1]=.5;v->light[0]=v->light[1]=240;
        input[q*4+c]=(simd_float4){v->p[0],v->p[1],v->p[2],1};
    }
    id<MTLBuffer> in=buffer(ctx,input,sizeof input),out=[ctx->device newBufferWithLength:sizeof input options:MTLResourceStorageModeShared];
    uint8_t compactArena[192]={0};uint32_t *packed=(uint32_t *)compactArena;
    id<MTLBuffer> qrec=buffer(ctx,compactArena,sizeof compactArena),raw=buffer(ctx,original,sizeof original);
    Section section={.count=2};Record records[2]={{.meta=6u<<6,.lo=bounds(-5,-1,-5),.hi=bounds(5,1,5)},
        {.start=1,.meta=(6u<<6)|(1u<<9),.lo=bounds(-1,-1,1),.hi=bounds(1,3,2)}};
    uint32_t order[]={0,1},args[]={384,2,0,0,0};id<MTLBuffer> sections=buffer(ctx,&section,sizeof section),recs=buffer(ctx,records,sizeof records),list=buffer(ctx,order,sizeof order),draw=buffer(ctx,args,sizeof args);
    simd_float4x4 view={.columns={{.2,0,0,0},{0,.12,.1,0},{0,.2,0,0},{0,.96,1.2,1}}};
    uint8_t globals[48]={0},tdata[80]={0};int camera[]={0,8,0};memcpy(globals,camera,12);
    simd_float4x4 identity=matrix_identity_float4x4;memcpy(tdata,&identity,64);int size[]={4,4};memcpy(tdata+64,size,8);
    float fog[]={0,0,0,0,1000,2000,1000,2000,1000,1000};
    id<MTLBuffer> p=buffer(ctx,&view,64),g=buffer(ctx,globals,sizeof globals),t=buffer(ctx,tdata,sizeof tdata),f=buffer(ctx,fog,sizeof fog);
    int64_t uniforms[]={(int64_t)p,0,(int64_t)g,0,(int64_t)t,0,(int64_t)f,0};
    id<MTLTexture> atlas=white(ctx),light=white(ctx);MTLSamplerDescriptor *sd=[MTLSamplerDescriptor new];sd.minFilter=sd.magFilter=MTLSamplerMinMagFilterNearest;
    id<MTLSamplerState> sampler=[ctx->device newSamplerStateWithDescriptor:sd];Enc *enc=mc_enc_new(ctx);
    uint8_t expected[128*128*4];float *expectedMap=malloc(512*512*4),*actualMap=malloc(512*512*4);int checks=0;
    for(int kind=1;kind<=2;kind++)for(int stub=0;stub<2;stub++)for(int indexed=0;indexed<2;indexed++)for(int deferred=0;deferred<2;deferred++)for(int step=0;step<4;step++) {
        uint32_t header=(kind<<21)|(2u<<27)|(2u<<29);if(kind==1)header|=(2u<<23)|(2u<<25);
        simd_uint4 tags={0,header,0,0};float phase=step==0?0:step==1?1.25:2.5,strength=step>=2?1.75:1;
        simd_float4 wind={phase,strength,0,0};simd_int4 origin={0};
        id<MTLCommandBuffer> cmd=[ctx->queue commandBuffer];id<MTLComputeCommandEncoder> compute=[cmd computeCommandEncoder];
        [compute setComputePipelineState:pipeline];[compute setBuffer:in offset:0 atIndex:0];[compute setBuffer:out offset:0 atIndex:1];
        [compute setBytes:&tags length:16 atIndex:2];[compute setBytes:&wind length:16 atIndex:3];[compute setBytes:&origin length:16 atIndex:4];
        [compute dispatchThreads:MTLSizeMake(8,1,1) threadsPerThreadgroup:MTLSizeMake(8,1,1)];[compute endEncoding];[cmd commit];[cmd waitUntilCompleted];
        require(cmd.status==MTLCommandBufferStatusCompleted,cmd.error.localizedDescription);
        simd_float4 *positions=out.contents;Vertex displaced[8];memcpy(displaced,original,sizeof displaced);
        for(int v=0;v<8;v++) {
            float distance=simd_length(positions[v].xyz-input[v].xyz);
            if(v<4 || (kind==2&&v<6))require(distance==0,@"Static ground or plant root moved");
            else require(distance>.001&&distance<.15,@"Leaves/tips did not move within their culling bounds");
            for(int a=0;a<3;a++)displaced[v].p[a]=positions[v][a];
        }
        memcpy(raw.contents,displaced,sizeof displaced);pack(packed,original,0);pack(packed+10,original+4,header);if(stub) {
            packed[10]=0x80000000u|header;packed[11]=2;
            for(int c=0;c<4;c++) {
                uint8_t *vertex=compactArena+128+16*c;
                for(int a=0;a<3;a++){uint16_t value=code(original[4+c].p[a]);memcpy(vertex+2*a,&value,2);}
                vertex[6]=vertex[7]=240;memset(vertex+8,255,4);uint16_t uv=32768;memcpy(vertex+12,&uv,2);memcpy(vertex+14,&uv,2);
            }
        }
        memcpy(qrec.contents,compactArena,sizeof compactArena);
        for(int side=0;side<2;side++) {
            void *own=side?animated:reference;mco_sun_geometry_optimization(own,indexed);mco_sun_far_cache(own,1,0);
            mco_sun_gbuffer(own,nativeLight&&deferred,0);
            BOOL night=nightTransition&&(step==1||step==2);
            MTLTextureDescriptor *td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:128 height:128 mipmapped:NO];
            td.storageMode=MTLStorageModeShared;td.usage=MTLTextureUsageRenderTarget;id<MTLTexture> color=[ctx->device newTextureWithDescriptor:td];
            td.pixelFormat=MTLPixelFormatDepth32Float;td.storageMode=MTLStorageModePrivate;td.usage|=MTLTextureUsageShaderRead;id<MTLTexture> depth=[ctx->device newTextureWithDescriptor:td];
            id<MTLTexture> targets[]={color};float clear[]={1,0,0,0,1};mc_render_begin(enc,1,targets,clear,depth,1,0,128,128);
            OwnSunSurfaceView surface={.clip=view,.camera={0,8,0},.enabled=deferred};require(mco_sun_surface(own,&surface)==0,@"Surface rejected");
            Sun sun={.clip={.columns={{.16,0,-.03,0},{-.12,0,-.04,0},{0,.2,0,0},{-.96,0,.18,1}}},.cam={0,8,0,0},
                .offset={0,0,0,phase},.sun={.6,.8,0,1},.config={5,1.0/512,.00015,1},.sections=1,.resolution=512,.on=1};
            if(night)sun.sun=(simd_float4){.6,-.8,0,0};
            memcpy((uint8_t *)&sun+76,&strength,4);uint8_t packet[400]={0};memcpy(packet,&sun,144);memcpy(packet+144,&view,64);
            double cam[]={0,8,0};memcpy(packet+208,cam,24);*(float *)(packet+240)=1.0/60;*(uint32_t *)(packet+244)=checks;*(uint32_t *)(packet+248)=1;
            Sun far=sun;far.config.x=10;for(int c=0;c<4;c++){far.clip.columns[c].x*=.5;far.clip.columns[c].y*=.5;}memcpy(packet+256,&far,144);
            require(mco_sun_frame(own,enc,packet,400,side?qrec:raw,sections,recs,atlas,sampler,error,sizeof error)>0,[NSString stringWithUTF8String:error]);
            require(mco_sun_debug_far_mode(own)==(night?0:step==3&&!nightTransition?2:1),@"Wind did not invalidate the far cache, or a frozen clock stopped reuse");
            uint8_t df[80]={0};*(int *)(df+4)=1;
            require(mco_draw(own,enc,1,df,80,side?qrec:raw,sections,recs,list,draw,0,uniforms,atlas,sampler,light,sampler,nil,error,sizeof error)>0,[NSString stringWithUTF8String:error]);
            require(mco_sun_finish(own,enc,error,sizeof error)==1,[NSString stringWithUTF8String:error]);
            require(mco_sun_debug_lighting(own)==(nativeLight&&deferred),@"Native lighting path was not exercised");
            id<MTLCommandBuffer> drawCmd=mc_enc_commit(enc);[drawCmd waitUntilCompleted];require(drawCmd.status==MTLCommandBufferStatusCompleted,drawCmd.error.localizedDescription);[drawCmd release];
            uint8_t pixels[sizeof expected];[color getBytes:pixels bytesPerRow:128*4 fromRegion:MTLRegionMake2D(0,0,128,128) mipmapLevel:0];
            if(!night)getDepth(ctx,mco_sun_debug_cascade_map(own,0),side?actualMap:expectedMap);
            int covered=0,mapCovered=0;
            for(int i=0;i<128*128;i++)if(pixels[i*4]>20)covered++;
            float *map=side?actualMap:expectedMap;if(!night)for(int i=0;i<512*512;i++)if(map[i]<1)mapCovered++;
            require(covered>1000&&(night||mapCovered>1000),@"Empty terrain/shadow image would hide a wind failure");
            if(!side)memcpy(expected,pixels,sizeof pixels);
            else {
                int difference=0;float depthDifference=0;
                for(int i=0;i<sizeof pixels;i++)difference=MAX(difference,abs((int)pixels[i]-expected[i]));
                if(!night)for(int i=0;i<512*512;i++)depthDifference=fmaxf(depthDifference,fabsf(actualMap[i]-expectedMap[i]));
                printf("kind=%d stub=%d indexed=%d deferred=%d step=%d native=%d night=%d color=%d depth=%.8f\n",kind,stub,indexed,deferred,step,nativeLight,night,difference,depthDifference);fflush(stdout);
                require(difference<=2&&depthDifference<1e-6,@"Terrain/shadow wind differs from explicitly displaced geometry");checks++;
            }
            [color release];[depth release];
        }
    }
    printf("PASS: %d animated/reference terrain and shadow pairs, rooted plants/leaves, QREC stubs, clear/storm strength, forward/deferred, temporal history and indexed/record casters on %s\n",checks,name);
    free(expectedMap);free(actualMap);
 }
 return 0;
}
