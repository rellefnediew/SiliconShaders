// Actual terrain + sorted transparent water, and the standalone underwater pass.
// Offscreen only; no Minecraft process or UI is started.
#include "sun_test_common.h"
#import <CoreGraphics/CoreGraphics.h>
#import <ImageIO/ImageIO.h>
extern void mco_sun_materials(void *,const float *,int);
extern void mco_sun_gbuffer(void *,int,int);
extern int mco_water_frame(void *,const void *,int);
extern int mco_water_begin(void *,Enc *,char *,int);
extern int mco_water_post(void *,Enc *,id<MTLTexture>,id<MTLTexture>,char *,int);
extern int mco_water_debug_ready(void *);
extern void mco_water_debug_stats(void *,uint64_t *);
extern id<MTLTexture> mco_water_debug_scene(void *);
extern id<MTLTexture> mco_water_debug_depth(void *);
typedef struct { simd_float4x4 view,inverse;simd_float4 sun,fog,state,camera,screen,controls,waves,optics,effects,quality; } Water;
static void png(const char *path,uint8_t *pixels,int size) {
    CGColorSpaceRef space=CGColorSpaceCreateDeviceRGB();
    CGContextRef context=CGBitmapContextCreate(pixels,size,size,8,size*4,space,kCGImageAlphaPremultipliedLast|kCGBitmapByteOrder32Big);
    CGImageRef image=CGBitmapContextCreateImage(context);NSURL *url=[NSURL fileURLWithPath:@(path)];
    CGImageDestinationRef destination=CGImageDestinationCreateWithURL((CFURLRef)url,CFSTR("public.png"),1,NULL);
    CGImageDestinationAddImage(destination,image,NULL);CGImageDestinationFinalize(destination);
    CFRelease(destination);CGImageRelease(image);CGContextRelease(context);CGColorSpaceRelease(space);
}
static void quad(Vertex *vertices,int q,float x0,float x1,float y,float z0,float z1,uint32_t color,int water) {
    for(int c=0;c<4;c++) {
        Vertex *v=&vertices[q*4+c];v->p[0]=(c==1||c==2)?x1:x0;v->p[1]=y;v->p[2]=c>=2?z0:z1;
        memcpy(v->color,&color,4);v->uv[0]=v->uv[1]=.5;v->light[0]=water?255:0;v->light[1]=240;
    }
}
int main(int argc,const char **argv) {
 @autoreleasepool {
    require(argc==4,@"Usage: water_check TERRAIN SUN OUTPUT_DIRECTORY");
    NSString *source=[[NSString stringWithContentsOfFile:@(argv[1]) encoding:NSUTF8StringEncoding error:nil]
        stringByAppendingString:[NSString stringWithContentsOfFile:@(argv[2]) encoding:NSUTF8StringEncoding error:nil]];
    char error[16384]={0},name[256]={0};Ctx *ctx=mc_create(name,sizeof name,error,sizeof error);require(ctx!=NULL,[NSString stringWithUTF8String:error]);
    void *own=mco_new(ctx,source.UTF8String,0,0,0,error,sizeof error);
    if(getenv("SILICONSHADERS_TRANSPARENT_MATERIALS")){float settings[15]={1,1,1,1,1,1,1,1,1,1,1,0,1,1,1};mco_sun_materials(own,settings,15);mco_sun_gbuffer(own,1,0);}
require(own!=NULL,[NSString stringWithUTF8String:error]);Enc *enc=mc_enc_new(ctx);
    // Reversed zero-to-one perspective, matching the actual backend depth convention.
    float cot=1/tanf(60*M_PI/360),near=.1,far=80;
    simd_float4x4 projection={.columns={{cot,0,0,0},{0,cot,0,0},{0,0,near/(far-near),-1},{0,0,far*near/(far-near),0}}};
    simd_float4x4 rotation={.columns={{1,0,0,0},{0,.943858,.330350,0},{0,-.330350,.943858,0},{0,0,0,1}}};
    simd_float4x4 view=simd_mul(projection,rotation);
    Vertex vertices[7*4]={0};
    quad(vertices,0,-8,0,-2,-16,-1,0xffb08050,0);quad(vertices,1,0,8,-2,-16,-1,0xff70b090,0);
    quad(vertices,2,-8,8,-.5,-16,-1,0xccb06428,1);
    // Bright red vertical wall is visible above the water and in the reflected ray.
    for(int c=0;c<4;c++) {
        Vertex *v=&vertices[12+c];v->p[0]=(c==1||c==2)?1.5:-1.5;v->p[1]=c>=2?2.5:-.5;v->p[2]=-5;
        v->color[0]=240;v->color[1]=35;v->color[2]=20;v->color[3]=255;v->uv[0]=v->uv[1]=.5;v->light[1]=240;
    }
    Section section={.count=4};Record recs[4]={{.meta=6u<<6},{.start=1,.meta=6u<<6},{.start=2,.meta=(1u<<18)|(2u<<9)},{.start=3,.meta=6u<<6}};
    uint32_t opaqueList[]={0,1,3},waterList[]={2},opaqueArgs[]={384,3,0,0,0},waterArgs[]={384,1,0,0,0};
    id<MTLBuffer> arena=buffer(ctx,vertices,sizeof vertices),sections=buffer(ctx,&section,sizeof section),records=buffer(ctx,recs,sizeof recs);
    id<MTLBuffer> opaque=buffer(ctx,opaqueList,sizeof opaqueList),water=buffer(ctx,waterList,sizeof waterList),odraw=buffer(ctx,opaqueArgs,sizeof opaqueArgs),wdraw=buffer(ctx,waterArgs,sizeof waterArgs);
    uint8_t globals[48]={0},terrain[80]={0};memcpy(terrain,&rotation,64);int ts[]={4,4};memcpy(terrain+64,ts,8);
    float fog[]={.08,.14,.20,1,1000,2000,1000,2000,1000,1000};
    id<MTLBuffer> p=buffer(ctx,&projection,64),g=buffer(ctx,globals,sizeof globals),t=buffer(ctx,terrain,sizeof terrain),f=buffer(ctx,fog,sizeof fog);
    int64_t uniforms[]={(int64_t)p,0,(int64_t)g,0,(int64_t)t,0,(int64_t)f,0};
    id<MTLTexture> atlas=white(ctx),light=white(ctx);MTLSamplerDescriptor *sd=[MTLSamplerDescriptor new];sd.minFilter=sd.magFilter=MTLSamplerMinMagFilterNearest;
    id<MTLSamplerState> sampler=[ctx->device newSamplerStateWithDescriptor:sd];
    NSString *probe=@"\nkernel void water_probe(constant WaterFrame &f [[buffer(0)]], device float4 *output [[buffer(1)]], texture2d<float> scene [[texture(0)]], depth2d<float> depth [[texture(1)]], uint i [[thread_position_in_grid]]) { if(i>=2){output[i]=float4(waterFresnel(i==2?1:i==3?0:i==4?.5:i==5?.9:.665,i>=4));return;}float confidence,travel;float3 ray=i==0?normalize(float3(0,.3,-1)):normalize(float3(3,.4,-.5));float3 rgb=waterReflection(float3(0,-1,-2),ray,float3(0,1,0),f,scene,depth,confidence,travel);output[i]=float4(rgb,confidence); }\n";
    NSError *issue=nil;id<MTLLibrary> library=[ctx->device newLibraryWithSource:[source stringByAppendingString:probe] options:nil error:&issue];require(library!=nil,issue.localizedDescription);
    id<MTLFunction> probeFunction=[library newFunctionWithName:@"water_probe"];
    id<MTLComputePipelineState> probePipeline=[ctx->device newComputePipelineStateWithFunction:probeFunction error:&issue];require(probePipeline!=nil,issue.localizedDescription);
    id<MTLBuffer> probeOutput=[ctx->device newBufferWithLength:112 options:MTLResourceStorageModeShared];
    uint8_t baseline[192*192*4]={0},phase0[192*192*4]={0},phase2[192*192*4]={0},glassReference[192*192*4]={0};int checked=0;
    id<MTLTexture> queued[4]={nil};id<MTLCommandBuffer> commands[4]={nil};
    for(int mode=0;mode<15;mode++) {
        BOOL enabled=mode!=0,inside=mode==3||mode==4||mode==10,exiting=mode==5,glass=mode==6||mode==7,resize=mode==8;
        int size=resize?128:192;BOOL srgb=mode==4,half=mode==10,bgra=mode==9,overlap=mode>=11;
        MTLPixelFormat format=half?MTLPixelFormatRGBA16Float:bgra?MTLPixelFormatBGRA8Unorm:srgb?MTLPixelFormatRGBA8Unorm_sRGB:MTLPixelFormatRGBA8Unorm;
        MTLTextureDescriptor *td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:format width:size height:size mipmapped:NO];
        td.storageMode=MTLStorageModeShared;td.usage=MTLTextureUsageRenderTarget;id<MTLTexture> color=[ctx->device newTextureWithDescriptor:td];require(color.pixelFormat==format,@"Render format fixture mismatch");
        td.pixelFormat=MTLPixelFormatDepth32Float;td.storageMode=MTLStorageModePrivate;td.usage=MTLTextureUsageRenderTarget|(getenv("SILICONSHADERS_WATER_READABLE_DEPTH")?MTLTextureUsageShaderRead:0);
        id<MTLTexture> depth=[ctx->device newTextureWithDescriptor:td];id<MTLTexture> targets[]={color};float clear[]={1,.05,.1,.2,1};
        mc_render_begin(enc,1,targets,clear,depth,1,0,size,size);
        Water frame={.view=view,.inverse=simd_inverse(view),.sun={.6,.8,0,1},.fog={.05,.18,.25,1},
            .state={mode==2||(overlap&&((mode-11)&1))?2:0,inside?1:0,inside||exiting?1:0,inside?1:0},.screen={size,size,12,enabled?1:0},.controls={1,32,0,0},.waves={1,1,1,1},.optics={1,1,64,1},.effects={1,1,0,0}};
        if(getenv("SILICONSHADERS_WATER_QUALITY")){float q[3]={0};sscanf(getenv("SILICONSHADERS_WATER_QUALITY"),"%f,%f,%f",&q[0],&q[1],&q[2]);frame.quality=(simd_float4){q[0],q[1],0,q[2]};} // presets: reflection,underwater,half size
        require(mco_water_frame(own,&frame,sizeof frame)==0,@"Water frame rejected");
        Sun sun={.clip=matrix_identity_float4x4,.sun={.6,.8,0,1},.config={16,1,.00015,0},.sections=1,.resolution=0,.on=1};
        require(mco_sun_frame(own,enc,&sun,144,arena,sections,records,atlas,sampler,error,sizeof error)>0,[NSString stringWithUTF8String:error]);
        uint8_t df[80]={0};*(int *)(df+4)=1;
        require(mco_draw(own,enc,0,df,80,arena,sections,records,opaque,odraw,0,uniforms,atlas,sampler,light,sampler,nil,error,sizeof error)>0,[NSString stringWithUTF8String:error]);
        if(enabled&&mode!=6)require(mco_water_begin(own,enc,error,sizeof error)==1,[NSString stringWithUTF8String:error]);
        if(glass)for(int c=0;c<4;c++)vertices[8+c].light[0]=0;
        else for(int c=0;c<4;c++)vertices[8+c].light[0]=255;
        if(!overlap)memcpy(arena.contents,vertices,sizeof vertices);
        require(mco_draw(own,enc,3|(1<<18),df,80,arena,sections,records,water,wdraw,0,uniforms,atlas,sampler,light,sampler,nil,error,sizeof error)>0,[NSString stringWithUTF8String:error]);
        int post=mco_water_post(own,enc,color,depth,error,sizeof error);require(post==(inside||exiting?1:0),[NSString stringWithUTF8String:error]);
        id<MTLCommandBuffer> command=mc_enc_commit(enc);
        if(overlap){queued[mode-11]=color;commands[mode-11]=command;[depth release];continue;}
        [command waitUntilCompleted];require(command.status==MTLCommandBufferStatusCompleted,command.error.localizedDescription);[command release];
        if(mode==1) {
            id<MTLCommandBuffer> probeCommand=[ctx->queue commandBuffer];id<MTLComputeCommandEncoder> c=[probeCommand computeCommandEncoder];
            [c setComputePipelineState:probePipeline];[c setBytes:&frame length:sizeof frame atIndex:0];[c setBuffer:probeOutput offset:0 atIndex:1];
            [c setTexture:mco_water_debug_scene(own) atIndex:0];[c setTexture:mco_water_debug_depth(own) atIndex:1];
            [c dispatchThreads:MTLSizeMake(7,1,1) threadsPerThreadgroup:MTLSizeMake(7,1,1)];[c endEncoding];[probeCommand commit];[probeCommand waitUntilCompleted];
            require(probeCommand.status==MTLCommandBufferStatusCompleted,probeCommand.error.localizedDescription);
            simd_float4 *result=probeOutput.contents;
            printf("SSR hit rgb=%.4f,%.4f,%.4f confidence=%.3f; miss confidence=%.3f\n",result[0].x,result[0].y,result[0].z,result[0].w,result[1].w);fflush(stdout);
            require(result[0].w>.1&&result[0].x>4*result[0].y,@"Reflection ray failed to hit the actual red world object");
            require(result[1].w==0,@"Offscreen ray fabricated a screen reflection");
            require(fabsf(result[2].x-.02037f)<.0001&&result[3].x==1&&result[4].x==1&&result[5].x<.03&&result[6].x>.3&&result[6].x<1,@"Fresnel or underwater total internal reflection is incorrect");
        }
        uint8_t pixels[sizeof baseline]={0};
        if(half) {
            __fp16 floating[192*192*4];[color getBytes:floating bytesPerRow:size*8 fromRegion:MTLRegionMake2D(0,0,size,size) mipmapLevel:0];
            for(int i=0;i<size*size*4;i++){float value=floating[i];if(!isfinite(value)||value<0)fprintf(stderr,"invalid float index=%d xy=%d,%d channel=%d value=%g\n",i,(i/4)%size,(i/4)/size,i%4,value);require(isfinite(value)&&value>=0,@"Water produced invalid floating-point color");pixels[i]=(uint8_t)lroundf(fminf(value,1)*255);}
        } else [color getBytes:pixels bytesPerRow:size*4 fromRegion:MTLRegionMake2D(0,0,size,size) mipmapLevel:0];
        if(bgra)for(int i=0;i<size*size;i++){uint8_t r=pixels[i*4];pixels[i*4]=pixels[i*4+2];pixels[i*4+2]=r;}
        int bright=0,difference=0,changed=0;
        for(int i=0;i<size*size;i++){if(pixels[i*4]+pixels[i*4+1]+pixels[i*4+2]>30)bright++;require(pixels[i*4+3]==255,@"Water changed scene alpha");}
        require(bright>size*size/2,@"Empty water test image");
        if(mode==0)memcpy(baseline,pixels,sizeof baseline);
        if(mode==1)memcpy(phase0,pixels,sizeof phase0);
        if(mode==2)memcpy(phase2,pixels,sizeof phase2);
        if(mode==6){require(!memcmp(baseline,pixels,sizeof baseline),@"Decoded water light does not match original vanilla material");memcpy(glassReference,pixels,sizeof glassReference);}
        if(mode==1||mode==2||mode==3||mode==7) {
            uint8_t *reference=mode==1?baseline:mode==7?glassReference:phase0;
            for(int i=0;i<size*size*4;i++){int d=abs((int)pixels[i]-reference[i]);difference=MAX(difference,d);if(d>2)changed++;}
            require(mode==7?difference==0:changed>100,mode==7?@"Ordinary glass changed with water pipeline":@"Water/refraction/underwater effect did not alter the image");
        }
        NSString *path=[@(argv[3]) stringByAppendingPathComponent:[NSString stringWithFormat:@"water-%d.png",mode]];png(path.UTF8String,pixels,size);
        printf("mode=%d size=%d ready=%d post=%d max difference=%d changed=%d\n",mode,size,mco_water_debug_ready(own),post,difference,changed);fflush(stdout);
        [color release];[depth release];checked++;
    }
    for(int i=0;i<4;i++) {
        [commands[i] waitUntilCompleted];require(commands[i].status==MTLCommandBufferStatusCompleted,commands[i].error.localizedDescription);
        uint8_t pixels[192*192*4];[queued[i] getBytes:pixels bytesPerRow:192*4 fromRegion:MTLRegionMake2D(0,0,192,192) mipmapLevel:0];
        uint8_t *expected=(i&1)?phase2:phase0;int difference=0;
        for(int n=0;n<sizeof pixels;n++)difference=MAX(difference,abs((int)pixels[n]-expected[n]));
        printf("overlapping water frame %d difference=%d\n",i,difference);require(difference==0,@"In-flight water snapshots or uniforms diverged");
        [queued[i] release];[commands[i] release];checked++;
    }
    // Isolate the underwater post pass: the same colored block face at 2, 8 and 32 blocks.
    // Near color contrast must survive; distance controls extinction, not camera height.
    uint8_t missingDepthReference[64*64*4];
    for(int test=0;test<5;test++) {
        int size=64;float distance=test==0?2:test==1?8:32;BOOL missingDepth=test>=3;
        uint8_t input[64*64*4];float inputDepth[64*64];
        simd_float4 clip=simd_mul(projection,(simd_float4){0,0,-distance,1});
        for(int y=0;y<size;y++)for(int x=0;x<size;x++) {
            int i=y*size+x;BOOL red=(x/8)&1;
            input[4*i]=red?210:45;input[4*i+1]=red?45:210;input[4*i+2]=55;input[4*i+3]=255;
            inputDepth[i]=missingDepth?0:clip.z/clip.w;
        }
        MTLTextureDescriptor *td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:size height:size mipmapped:NO];
        td.storageMode=MTLStorageModeShared;td.usage=MTLTextureUsageRenderTarget;
        id<MTLTexture> color=[ctx->device newTextureWithDescriptor:td];[color replaceRegion:MTLRegionMake2D(0,0,size,size) mipmapLevel:0 withBytes:input bytesPerRow:size*4];
        td.pixelFormat=MTLPixelFormatDepth32Float;td.usage=MTLTextureUsageRenderTarget|(getenv("SILICONSHADERS_WATER_READABLE_DEPTH")?MTLTextureUsageShaderRead:0);id<MTLTexture> depth=[ctx->device newTextureWithDescriptor:td];
        [depth replaceRegion:MTLRegionMake2D(0,0,size,size) mipmapLevel:0 withBytes:inputDepth bytesPerRow:size*4];
        Water frame={.view=projection,.inverse=simd_inverse(projection),.sun={.6,.8,0,1},.fog={.05,.18,.25,1},
            .state={test==4?30:0,1,1,1},.screen={size,size,test==3?8:test==4?32:64,1},.controls={1,32,0,0},.waves={1,1,1,1},.optics={1,1,64,0},.effects={0,0,0,0}};
        if(getenv("SILICONSHADERS_WATER_QUALITY")){float q[3]={0};sscanf(getenv("SILICONSHADERS_WATER_QUALITY"),"%f,%f,%f",&q[0],&q[1],&q[2]);frame.quality=(simd_float4){q[0],q[1],0,q[2]};} // presets: reflection,underwater,half size
        require(mco_water_frame(own,&frame,sizeof frame)==0,@"Underwater contrast frame rejected");
        require(mco_water_post(own,enc,color,depth,error,sizeof error)==1,[NSString stringWithUTF8String:error]);
        id<MTLCommandBuffer> command=mc_enc_commit(enc);[command waitUntilCompleted];require(command.status==MTLCommandBufferStatusCompleted,command.error.localizedDescription);[command release];
        uint8_t pixels[sizeof input];[color getBytes:pixels bytesPerRow:size*4 fromRegion:MTLRegionMake2D(0,0,size,size) mipmapLevel:0];
        int red=(32*size+28)*4,green=(32*size+36)*4;
        float retained=(float)(pixels[red]-pixels[green])/(input[red]-input[green]);
        printf("underwater distance=%.0f retained red/green contrast=%.3f\n",distance,retained);
        if(!missingDepth)require(retained>(test<2?.95:.75),@"Nearby underwater blocks lost their color contrast");
        if(test==3)memcpy(missingDepthReference,pixels,sizeof pixels);
        if(test==4) {
            int difference=0;for(int i=0;i<sizeof pixels;i++)difference=MAX(difference,abs((int)pixels[i]-missingDepthReference[i]));
            printf("missing-depth adaptation 0..30 seconds: max difference=%d\n",difference);
            require(difference==0,@"Cleared world depth and vanilla water vision progressively turned the screen blue");
        }
        [color release];[depth release];
    }
    uint64_t stats[2];mco_water_debug_stats(own,stats);require(stats[0]>=13&&stats[1]==9,@"Unexpected water pass counters");
    printf("PASS: %d actual water passes, wave motion/refraction, underwater/exit, unchanged glass, alpha, resize, sRGB/BGRA/float16, queued frames and copy-only depth on %s\n",checked,name);
 }
 return 0;
}
