// Functional and overlapping-frame regression tests.
#include "sun_test_common.h"
extern void mco_sun_debug_stats(void *,uint64_t *);
extern int mco_sun_finish(void *,Enc *,char *,int);

int main(int argc,const char **argv) {
    @autoreleasepool {
        require(argc==3||argc==4,@"Usage: sun_temporal_check TERRAIN.metal SUN.metal [compact]");
        BOOL compact=argc==4;
        NSError *issue=nil;
        NSString *source=[NSString stringWithContentsOfFile:[NSString stringWithUTF8String:argv[1]] encoding:NSUTF8StringEncoding error:&issue];
        NSString *sunSource=[NSString stringWithContentsOfFile:[NSString stringWithUTF8String:argv[2]] encoding:NSUTF8StringEncoding error:&issue];
        require(source&&sunSource,@"Shader sources missing");source=[source stringByAppendingString:sunSource];
        // Expose history acceptance and filtered visibility without changing reconstruction or rejection.
        require([source containsString:@"float3 visible=mask.rgb;float distance=0;"],@"Temporal diagnostic hook missing");
        source=[source stringByReplacingOccurrencesOfString:@"float3 visible=mask.rgb;float distance=0;" withString:@"float historyAccepted=0;float3 visible=mask.rgb;float distance=0;"];
        source=[source stringByReplacingOccurrencesOfString:@"constexpr sampler linearSampler" withString:@"historyAccepted=1;constexpr sampler linearSampler"];
        source=[source stringByReplacingOccurrencesOfString:@"return {color,float4(visible,distance)};" withString:@"return {float4(historyAccepted,visible.x,0,1),float4(visible,distance)};"];
        if(compact) source=[@"#define OWN_QREC 1\n#define OWN_EXACT_POS 1\n" stringByAppendingString:source];
        char error[16384]={0},name[256]={0};Ctx *ctx=mc_create(name,sizeof name,error,sizeof error);require(ctx!=NULL,[NSString stringWithUTF8String:error]);
        void *own=mco_new(ctx,source.UTF8String,compact,0,0,error,sizeof error);require(own!=NULL,[NSString stringWithUTF8String:error]);
        Enc *enc=mc_enc_new(ctx);
        Vertex vertices[8]={0};
        for(int q=0;q<2;q++) for(int c=0;c<4;c++) {
            Vertex *v=&vertices[q*4+c];float radius=q?0.4:4;
            v->p[0]=(c==1||c==2)?radius:-radius;v->p[1]=q?2:0;v->p[2]=((c>=2)?radius:-radius)+(q?1.5:0);
            memset(v->color,255,4);v->light[0]=v->light[1]=240;v->uv[0]=v->uv[1]=0.5;
        }
        Section section={.count=2};Record records[2]={{0},{.start=1}};
        uint32_t lists[2]={0,1},args[5]={384,2,0,0,0};
        uint32_t packed[20]={0};
        for(int q=0;q<2;q++) {
            uint32_t *w=packed+q*10;
            int lo=(int)lroundf(((q?-0.4f:-4.0f)+8)*2048),hi=(int)lroundf(((q?0.4f:4.0f)+8)*2048),y=(q?10:8)*2048;
            int zlo=(int)lroundf(((q?1.1f:-4.0f)+8)*2048),zhi=(int)lroundf(((q?1.9f:4.0f)+8)*2048);
            w[0]=(1u<<3)|(5u<<6)|(4u<<9);w[1]=lo|(hi<<16);w[2]=y|(y<<16);w[3]=zlo|(zhi<<16);
            w[4]=w[5]=0x80008000;w[6]=w[7]=0xffffffff;w[8]=w[9]=0xf0f0f0f0;
        }
        id<MTLBuffer> arena=buffer(ctx,compact?(const void *)packed:(const void *)vertices,compact?sizeof packed:sizeof vertices),sections=buffer(ctx,&section,sizeof section),recs=buffer(ctx,records,sizeof records);
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
        Sun base={.clip={.columns={{.16,0,-.03,0},{-.12,0,-.04,0},{0,.2,0,0},{-.96,0,.18,1}}},
            .cam={0,8,0,0},.sun={.6,.8,0,1},.config={5,1.0/512,0.00015,1},.sections=1,.resolution=512,.on=1};
        char errorText[4096]={0};
        // Repeated cycles with no caller pool expose leaked profiling attachments.
        for(int iteration=0;iteration<(testProfileOn()?108:18);iteration++) {
            int frame=iteration%18;
            testProfileBegin(enc);
            int width=frame>=8?96:128,height=width,block=frame>=4?16:0;
            float fraction=frame>=3?0.5f:frame==2?0.25f:0;
            float zFraction=frame>=3?0.5f:0;
            simd_float4x4 viewProjection=projection;
            if(frame>=3)viewProjection=simd_mul(projection,simd_matrix4x4(simd_quaternion(0.02f,(simd_float3){0,1,0})));
            memcpy(p.contents,&viewProjection,64);
            BOOL useTemporal=frame!=14;
            Sun parameters=base;parameters.cam.x=block;parameters.offset.x=-fraction;parameters.offset.z=-zFraction;
            parameters.clip.columns[3]+=parameters.clip.columns[0]*fraction+parameters.clip.columns[2]*zFraction;
            parameters.resolution=frame>=10?1024:512;parameters.config.y=1.0f/parameters.resolution;
            section.x=block;memcpy(sections.contents,&section,sizeof section);
            records[1].meta=frame==16?2u<<9:0;memcpy(recs.contents,records,sizeof records);
            *(int32_t *)globals=block;*(float *)(globals+16)=-fraction;*(float *)(globals+24)=-zFraction;memcpy(g.contents,globals,sizeof globals);
            uint8_t packet[256]={0};memcpy(packet,&parameters,144);memcpy(packet+144,&viewProjection,64);
            double cameraPosition[]={block+fraction,8,zFraction};memcpy(packet+208,cameraPosition,24);
            *(uint64_t *)(packet+232)=frame>=17?13:frame>=16?12:frame>=6?11:10;
            *(float *)(packet+240)=frame==12?0.3f:1.0f/120;*(uint32_t *)(packet+244)=frame;
            MTLTextureDescriptor *td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:width height:height mipmapped:NO];
            td.storageMode=MTLStorageModeShared;td.usage=MTLTextureUsageRenderTarget;id<MTLTexture> color=[ctx->device newTextureWithDescriptor:td];
            td.pixelFormat=MTLPixelFormatDepth32Float;td.storageMode=MTLStorageModePrivate;id<MTLTexture> depth=[ctx->device newTextureWithDescriptor:td];
            id<MTLTexture> targets[]={color};float clear[5]={1,0,0,0,1};mc_render_begin(enc,1,targets,clear,depth,1,0,width,height);
            require(mco_sun_frame(own,enc,packet,useTemporal?256:144,arena,sections,recs,atlas,sampler,errorText,sizeof errorText)>=0,[NSString stringWithUTF8String:errorText]);
            uint8_t drawFrame[80]={0};*(int32_t *)(drawFrame+4)=1;
            require(mco_draw(own,enc,0,drawFrame,sizeof drawFrame,arena,sections,recs,list,draw,0,uniforms,atlas,sampler,light,sampler,nil,errorText,sizeof errorText)>0,[NSString stringWithUTF8String:errorText]);
            int finished=mco_sun_finish(own,enc,errorText,sizeof errorText);require(finished==useTemporal,@"Temporal switch did not restore the original pass");
            id<MTLCommandBuffer> command=testProfileCommit(enc,ctx);[command waitUntilCompleted];require(command.status!=MTLCommandBufferStatusError,command.error.localizedDescription);[command release];
            uint8_t *pixels=malloc(width*height*4);[color getBytes:pixels bytesPerRow:width*4 fromRegion:MTLRegionMake2D(0,0,width,height) mipmapLevel:0];
            int accepted=0,total=0,minVisibility=255;
            for(int y=height*0.6;y<height*0.8;y++)for(int x=width*0.2;x<width*0.8;x++) {
                total++;accepted+=pixels[(y*width+x)*4]>250;minVisibility=MIN(minVisibility,pixels[(y*width+x)*4+1]);
            }
            float ratio=(float)accepted/total;
            BOOL warm=frame==1||frame==2||frame==3||frame==5||frame==7||frame==9||frame==11||frame==13;
            if(useTemporal)require(warm?ratio>0.8:accepted==0,@"Camera reprojection or history reset failed");
            if(frame==16)require(minVisibility>245,@"Removed caster left a stale shadow");
            if(frame==17)require(minVisibility<100,@"Restored caster did not shadow immediately");
            printf("temporal frame=%d accepted=%.3f expected=%s minVisibility=%d size=%d\n",frame,ratio,warm?"reuse":"reset",minVisibility,width);
            free(pixels);[color release];[depth release];
        }
        printf("PASS: shadow history reprojection through camera motion; teleport, geometry, viewport, quality, time-gap and toggle resets; immediate caster removal/restore (%s)\n",compact?"compact":"raw");
    }
    return 0;
}
