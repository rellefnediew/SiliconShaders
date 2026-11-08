// Functional and overlapping-frame regression tests.
#include "sun_test_common.h"
extern id<MTLTexture> mco_sun_debug_cascade_map(void *,int);
extern void mco_sun_debug_stats(void *,uint64_t *);
extern int mco_sun_finish(void *,Enc *,char *,int);

int main(int argc,const char **argv) {
    @autoreleasepool {
        require(argc>=3 && argc<=7,@"Usage: sun_check TERRAIN.metal SUN.metal [compact] [smooth|temporal]");
        BOOL compact=NO,smooth=NO,temporal=NO,cascade=NO,farOnly=NO,cycle=NO;int levels=2;
        for(int i=3;i<argc;i++) { if(!strcmp(argv[i],"compact"))compact=YES;else if(!strcmp(argv[i],"smooth"))smooth=YES;else if(!strcmp(argv[i],"temporal"))temporal=YES;else if(!strcmp(argv[i],"cascade"))cascade=YES;else if(!strcmp(argv[i],"cascade-far")){cascade=YES;farOnly=YES;}else if(!strcmp(argv[i],"levels3")){cascade=YES;levels=3;}else if(!strcmp(argv[i],"levels4")){cascade=YES;levels=4;}else if(!strcmp(argv[i],"cycle")){cascade=YES;cycle=YES;}else require(NO,@"Unknown test mode"); }
        NSError *issue=nil;
        NSString *source=[NSString stringWithContentsOfFile:[NSString stringWithUTF8String:argv[1]] encoding:NSUTF8StringEncoding error:&issue];
        NSString *sunSource=[NSString stringWithContentsOfFile:[NSString stringWithUTF8String:argv[2]] encoding:NSUTF8StringEncoding error:&issue];
        require(source&&sunSource,@"Shader sources missing");source=[source stringByAppendingString:sunSource];
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
        Sun parameters={.clip={.columns={{.16,0,-.03,0},{-.12,0,-.04,0},{0,.2,0,0},{-.96,0,.18,1}}},
            .cam={0,8,0,0},.sun={.6,.8,0,1},.config={5,1.0/512,0.00015,1},.sections=1,.resolution=512,.on=1};
        int resolutions[]={512,1024,2048,4096,0,1024,1024,1024,0};
        uint8_t previous[128*128*4]={0};int largestStep=0,largeChanges=0;
        NSMutableData *reference=[NSMutableData dataWithLength:64*128*128*4];
        id<MTLTexture> asynchronous[64]={nil};id<MTLCommandBuffer> submitted[64]={nil};
        for(int sequence=0;sequence<137;sequence++) {
            @autoreleasepool {
                BOOL overlap=sequence>=73;int test=overlap?sequence-64:sequence;
                if(cycle)levels=2+test%3;
                BOOL disabled=test==8,transparent=test==5,alphaCutout=test==6;
                records[1].meta=transparent?(2u<<9):alphaCutout?(1u<<9):0;
                if(!overlap) memcpy(recs.contents,records,sizeof records);
                vertices[4].color[3]=vertices[5].color[3]=vertices[6].color[3]=vertices[7].color[3]=alphaCutout?0:255;
                packed[16]=alphaCutout?0x00ffffff:0xffffffff;
                if(!overlap) memcpy(arena.contents,compact?(const void *)packed:(const void *)vertices,compact?sizeof packed:sizeof vertices);
                parameters.resolution=test<9?resolutions[test]:512;parameters.on=!disabled;parameters.config.w=parameters.resolution>0?1:0;
                parameters.config.y=1.0/MAX(1,parameters.resolution);
                if(test>=9) {
                    // Continuous solar rotation: measure abrupt pixel changes at a shadow edge, without camera motion.
                    float angle=atan2f(.6,.8)+(test-41)*.0007f,s=sinf(angle),c=cosf(angle);
                    parameters.sun=(simd_float4){s,c,0,1};
                    parameters.clip=(simd_float4x4){.columns={{.2f*c,0,-.05f*s,0},{-.2f*s,0,-.05f*c,0},{0,.2f,0,0},{-1.6f*s,0,.5f-.4f*c,1}}};
                }
                MTLTextureDescriptor *td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:128 height:128 mipmapped:NO];
                td.storageMode=MTLStorageModeShared;td.usage=MTLTextureUsageRenderTarget;
                id<MTLTexture> color=[ctx->device newTextureWithDescriptor:td];
                td.pixelFormat=MTLPixelFormatDepth32Float;td.storageMode=MTLStorageModePrivate;id<MTLTexture> depth=[ctx->device newTextureWithDescriptor:td];
                id<MTLTexture> targets[]={color};float clear[]={1,0,0,0,1};
                mc_render_begin(enc,1,targets,clear,depth,1,0,128,128);
                uint8_t packet[688]={0};memcpy(packet,&parameters,144);
                if(smooth) {
                    float angle=atan2f(parameters.sun.x,parameters.sun.y);
                    double lower=floor(angle/0.0005)*0.0005;
                    for(int i=0;i<2;i++) {
                        Sun sample=parameters;
                        // Match the legacy fixture at its initial angle; moving frames use its rotating projection.
                        if(test>=9) {
                            float a=(float)(lower+i*0.0005),s=sinf(a),c=cosf(a);
                            sample.sun=(simd_float4){s,c,0,1};
                            sample.clip=(simd_float4x4){.columns={{.2f*c,0,-.05f*s,0},{-.2f*s,0,-.05f*c,0},{0,.2f,0,0},{-1.6f*s,0,.5f-.4f*c,1}}};
                        }
                        memcpy(packet+144+i*144,&sample,144);
                    }
                    *(float *)(packet+432)=(angle-lower)/0.0005;*(uint32_t *)(packet+436)=1;
                    *(uint64_t *)(packet+440)=test<9?test+1:10;
                    int anchor[]={1,9,1};memcpy(packet+448,anchor,12);
                }
                if(temporal) {
                    memcpy(packet+144,&projection,64);double cameraPosition[]={0,8,0};memcpy(packet+208,cameraPosition,24);
                    *(uint64_t *)(packet+232)=test<9?test+1:overlap?11:10;
                    *(float *)(packet+240)=1.0f/120;*(uint32_t *)(packet+244)=test;
                }
                if(cascade) {
                for(int i=1;i<levels;i++) {
                    Sun sample=parameters;float ratio=powf(64.0f/24,(float)i/(levels-1));
                    for(int c=0;c<4;c++){sample.clip.columns[c].x/=ratio;sample.clip.columns[c].y/=ratio;}
                    sample.config.x*=ratio;
                    if(levels>2||cycle){sample.resolution=parameters.resolution?MAX(256,parameters.resolution>>i):0;sample.config.y=1.0/MAX(1,sample.resolution);}
                    memcpy(packet+256+(i-1)*144,&sample,144);
                }
                *(uint32_t *)(packet+248)=temporal;*(uint32_t *)(packet+252)=levels;
                if(farOnly)for(int i=0;i<levels-1;i++)((Sun *)(packet+(i?256+(i-1)*144:0)))->clip.columns[3].x+=3;

                }
                require(mco_sun_frame(own,enc,packet,cascade?(levels==2&&!cycle?400:688):temporal?256:smooth?464:144,arena,sections,recs,atlas,sampler,error,sizeof error)>=0,[NSString stringWithUTF8String:error]);
                if(cascade&&parameters.resolution&&!disabled)for(int i=0;i<levels;i++) {
                    id<MTLTexture> map=mco_sun_debug_cascade_map(own,i);uint32_t expected=levels==2&&!cycle?parameters.resolution:MAX(256,parameters.resolution>>i);
                    require(map&&map.width==expected&&map.height==expected,@"Actual GPU shadow resolution differs from selected level");
                }
                if(smooth) {
                    // Repeating an unchanged frame must preserve its maps, including after alpha/geometry changes.
                    require(mco_sun_frame(own,enc,packet,464,arena,sections,recs,atlas,sampler,error,sizeof error)>=0,[NSString stringWithUTF8String:error]);
                }
                uint8_t drawFrame[80]={0};*(int32_t *)(drawFrame+4)=1;
                require(mco_draw(own,enc,0,drawFrame,sizeof drawFrame,arena,sections,recs,list,draw,0,uniforms,atlas,sampler,light,sampler,nil,error,sizeof error)>0,[NSString stringWithUTF8String:error]);
                if(temporal)require(mco_sun_finish(own,enc,error,sizeof error)>=0,[NSString stringWithUTF8String:error]);
                id<MTLCommandBuffer> command=mc_enc_commit(enc);
                if(overlap) {
                    asynchronous[test-9]=color;submitted[test-9]=command;[depth release];continue;
                }
                [command waitUntilCompleted];
                require(command.status!=MTLCommandBufferStatusError,command.error.localizedDescription);[command release];
                uint8_t pixels[128*128*4];[color getBytes:pixels bytesPerRow:128*4 fromRegion:MTLRegionMake2D(0,0,128,128) mipmapLevel:0];
                int shaded=pixels[(83*128+45)*4],lit=pixels[(83*128+90)*4],mirrored=pixels[(45*128+45)*4];
                BOOL shadowExpected=parameters.resolution>0&&!disabled&&!transparent&&!alphaCutout;
                if(test<9) printf("test=%d size=%u shadow=%d lit=%d\n",test,parameters.resolution,shaded,lit);
                require(lit>180,@"Ground did not render brightly");
                require(shadowExpected?shaded<lit-60:abs(shaded-lit)<8,@"Shadow, alpha cutout, translucent exclusion or OFF comparison failed");
                require(mirrored>180,@"Shadow map was mirrored into the wrong world position");
                if(test>9) for(int pixel=0;pixel<128*128;pixel++) {
                    int step=abs((int)pixels[pixel*4]-(int)previous[pixel*4]);
                    largestStep=MAX(largestStep,step);if(step>40) largeChanges++;
                }
                if(test>=9) memcpy(previous,pixels,sizeof previous);
                if(test>=9) memcpy((uint8_t *)reference.mutableBytes+(test-9)*sizeof pixels,pixels,sizeof pixels);
                [color release];[depth release];
            }
        }
        printf("Moving sun: 64 frames, largest pixel step %d/255, pixel changes over 40/255: %d\n",largestStep,largeChanges);
        if(!cycle)require(largestStep<(temporal?20:90),@"Moving solar edge changed too abruptly in the temporal fixture");
        int maxDifference=0;
        for(int frame=0;frame<64;frame++) {
            [submitted[frame] waitUntilCompleted];require(submitted[frame].status!=MTLCommandBufferStatusError,submitted[frame].error.localizedDescription);
            uint8_t pixels[128*128*4];[asynchronous[frame] getBytes:pixels bytesPerRow:128*4 fromRegion:MTLRegionMake2D(0,0,128,128) mipmapLevel:0];
            uint8_t *expected=(uint8_t *)reference.mutableBytes+frame*sizeof pixels;
            for(int byte=0;byte<sizeof pixels;byte++) maxDifference=MAX(maxDifference,abs((int)pixels[byte]-(int)expected[byte]));
            [asynchronous[frame] release];[submitted[frame] release];
        }
        printf("Overlapping frames: maximum difference from synchronous reference %d/255\n",maxDifference);
        require(maxDifference<=2,@"Shadow maps and solar matrices diverged with overlapping frames");
        uint64_t stats[2]={0};mco_sun_debug_stats(own,stats);
        printf("Shadow map encodes: %llu; cache hits: %llu; smooth=%d temporal=%d\n",stats[0],stats[1],smooth,temporal);
        if(smooth)require(stats[1]>0,@"Unchanged shadow samples never reused their cached maps");
        printf("PASS: real terrain shadow on %s (%s); 512/1024/2048/4096, sunlight without shadows, alpha cutout, translucent exclusion, disable/re-enable\n",name,compact?"40-byte quad records":"28-byte vertices");
    }
    return 0;
}
