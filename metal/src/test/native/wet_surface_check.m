// The production wet-surface shading (sun.metal sunWetSurface) on the GPU, isolated: dry = unchanged, masks, controls, finite
// output and its full-screen cost. No Minecraft window.
#include "sun_test_common.h"
typedef struct {uint32_t enabled,srgb;float exposure,saturation,contrast,moon,pad[2];simd_float4 env,p[19],hand[3],materials[4],aurora[3];} Lighting;
_Static_assert(sizeof(Lighting)==512,"Lighting ABI");
static void complete(Enc *enc){id<MTLCommandBuffer> cmd=mc_enc_commit(enc);[cmd waitUntilCompleted];require(cmd.status==MTLCommandBufferStatusCompleted,cmd.error.localizedDescription);}
static Lighting lighting(float wet,float puddles){
    Lighting l={.enabled=1,.exposure=1,.saturation=1,.contrast=1,.env={1,1,0,1}};
    l.p[0]=(simd_float4){1,.85,.6,1};l.p[1]=l.p[0];l.p[10]=(simd_float4){.22,.44,.96,1};l.p[11]=(simd_float4){.64,.8,1,1};
    l.p[12]=(simd_float4){.16,.22,.5,.06};l.p[13]=(simd_float4){.3,.38,.62,.1};l.p[14]=(simd_float4){1,.46,.22,1};l.p[15]=(simd_float4){1,.5,1,1};
    l.p[7]=(simd_float4){.85,.6,.45,0};l.p[18]=(simd_float4){1,0,wet,puddles};return l;
}
static float *probe(Enc *enc,Ctx *ctx,id<MTLRenderPipelineState> pipeline,Lighting l,uint32_t kind,int w,int h){
    MTLTextureDescriptor *d=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA16Float width:w height:h mipmapped:NO];
    d.storageMode=MTLStorageModeShared;d.usage=MTLTextureUsageRenderTarget;id<MTLTexture> color=[ctx->device newTextureWithDescriptor:d];
    id<MTLTexture> colors[]={color};float clear[]={0,0,0,0,0};mc_render_begin(enc,1,colors,clear,nil,0,0,w,h);
    [enc->render setRenderPipelineState:pipeline];[enc->render setCullMode:MTLCullModeNone];
    [enc->render setFragmentBytes:&l length:sizeof l atIndex:0];[enc->render setFragmentBytes:&kind length:4 atIndex:1];
    Sun sun={.cam={-300,70,500,0},.offset={-.25,-.5,-.75,12.5},.sun={.6,.8,0,1},.config={8,1.f/512,.00015,1}};
    [enc->render setFragmentBytes:&sun length:sizeof sun atIndex:2];[enc->render drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];complete(enc);
    int count=w*h*4;_Float16 *raw=malloc(count*2);[color getBytes:raw bytesPerRow:w*8 fromRegion:MTLRegionMake2D(0,0,w,h) mipmapLevel:0];
    float *p=malloc(count*4);for(int i=0;i<count;i++){p[i]=(float)raw[i];require(isfinite(p[i]),@"Wet surface shading produced NaN/Inf");}free(raw);[color release];return p;
}
static double difference(float *a,float *b,int n){double t=0;for(int i=0;i<n;i++)if(i%4!=3)t+=fabs(a[i]-b[i]);return t/(n*.75);}
int main(int argc,const char **argv){@autoreleasepool{
    require(argc==3,@"Usage: wet_surface_check TERRAIN SUN");
    NSString *source=[[NSString stringWithContentsOfFile:@(argv[1]) encoding:NSUTF8StringEncoding error:nil] stringByAppendingString:[NSString stringWithContentsOfFile:@(argv[2]) encoding:NSUTF8StringEncoding error:nil]];
    char err[16384]={0},name[256];Ctx *ctx=mc_create(name,sizeof name,err,sizeof err);require(ctx!=NULL,@(err));Enc *enc=mc_enc_new(ctx);
    // kind 0: open ground, 1: wall, 2: covered (half sky light), 3: next to a torch. Ground below the camera, looking along +z.
    NSString *test=@"\nfragment float4 wet_probe_fs(float4 q [[position]],constant SunLighting &l [[buffer(0)]],constant uint &kind [[buffer(1)]],constant SunFrame &sun [[buffer(2)]]){"
        "float3 n=kind==1?float3(-1,0,0):float3(0,1,0);float sky=kind==2?0.5:1.0,block=kind==3?1.0:0.0;"
        "float3 p=kind==1?float3(6,q.y*.05-2,q.x*.25+2):float3(q.x*.25-32,-6,q.y*.25+2);"
        "float3 lit=float3(.20,.25,.18);return float4(sunWetSurface(lit,block,sky,n,float3(1),p,sun,l),1);}\n"
    // Material surfaces (one profile per 8-pixel column, column 0 none): kind 0 the former stacking (material, then the wet
    // sheen on top), 1 the shared path, 2 dry material only, 3 whether the material drew the wet shading itself.
        "fragment float4 wet_material_fs(float4 q [[position]],constant SunLighting &l [[buffer(0)]],constant uint &kind [[buffer(1)]],constant SunFrame &sun [[buffer(2)]]){"
        "uint id=(uint(q.x)/8)%SURFACE_PROFILE_COUNT;float3 n=float3(0,1,0),p=float3(q.x*.25-32,-6,q.y*.25+2),texel=float3(.6,.62,.65),lit=float3(.20,.25,.18);"
        "float3 dry=sunSurfaceMaterial(lit,texel,id,1,n,float3(1),p,sun,l);"
        "if(kind==0)return float4(sunWetSurface(dry,0,1,n,float3(1),p,sun,l),1);if(kind==2)return float4(dry,1);"
        "SunWet wet=sunWetness(0,1,n,p,sun,l);bool shaded;float3 c=sunSurfaceMaterial(sunWetDarken(lit,wet),texel,id,1,n,float3(1),p,sun,l,wet,shaded);"
        "if(kind==3)return float4(shaded?1:0,0,0,1);if(!shaded)c=sunWetSheen(c,wet,1,float3(1),p,sun,l);return float4(c,1);}\n";
    NSError *issue=nil;MTLCompileOptions *opts=[MTLCompileOptions new];opts.languageVersion=MTLLanguageVersion3_0;
    id<MTLLibrary> lib=[ctx->device newLibraryWithSource:[source stringByAppendingString:test] options:opts error:&issue];require(lib!=nil,issue.localizedDescription);
    MTLRenderPipelineDescriptor *pd=[MTLRenderPipelineDescriptor new];pd.vertexFunction=[lib newFunctionWithName:@"sun_screen_vs"];pd.fragmentFunction=[lib newFunctionWithName:@"wet_probe_fs"];
    pd.colorAttachments[0].pixelFormat=MTLPixelFormatRGBA16Float;id<MTLRenderPipelineState> pipeline=[ctx->device newRenderPipelineStateWithDescriptor:pd error:&issue];require(pipeline!=nil,issue.localizedDescription);
    int w=256,h=128,n=w*h*4;float base[3]={.20f,.25f,.18f};
    float *dry=probe(enc,ctx,pipeline,lighting(0,.5),0,w,h);
    for(int i=0;i<n;i++)require(fabsf(dry[i]-(i%4==3?1:base[i%4]))<1e-3f,@"Dry weather changed the shading");
    float *wet=probe(enc,ctx,pipeline,lighting(1,.5),0,w,h),*wall=probe(enc,ctx,pipeline,lighting(1,.5),1,w,h);
    float *covered=probe(enc,ctx,pipeline,lighting(1,.5),2,w,h),*torch=probe(enc,ctx,pipeline,lighting(1,.5),3,w,h);
    double ground=difference(dry,wet,n),side=difference(dry,wall,n);
    require(ground>.01,@"Open ground did not get wet");
    require(side>1e-4&&side<ground,@"Walls should get only a thinner film");
    require(difference(dry,covered,n)<1e-4,@"Covered ground got wet");require(difference(dry,torch,n)<1e-4,@"Ground next to a torch got wet");
    float *film=probe(enc,ctx,pipeline,lighting(1,0),0,w,h),*puddles=probe(enc,ctx,pipeline,lighting(1,1),0,w,h);
    double a=difference(dry,film,n),b=difference(dry,puddles,n);require(b>a*1.3,@"Puddle coverage ineffective");
    float *strong=probe(enc,ctx,pipeline,lighting(2,.5),0,w,h);require(difference(dry,strong,n)>ground,@"Strength ineffective");
    printf("PASS: dry unchanged; open ground %.4f, wall %.4f, film %.4f, puddles %.4f; covered and torch-lit stay dry; strength; finite\n",ground,side,a,b);
    {
        // Wet materials: one highlight and reflection (lower roughness, more gloss), never the material's plus a second sheen.
        MTLFunctionConstantValues *values=[MTLFunctionConstantValues new];bool on=true;[values setConstantValue:&on type:MTLDataTypeBool atIndex:51];
        MTLRenderPipelineDescriptor *md=[MTLRenderPipelineDescriptor new];md.vertexFunction=[lib newFunctionWithName:@"sun_screen_vs"];
        md.fragmentFunction=[lib newFunctionWithName:@"wet_material_fs" constantValues:values error:&issue];require(md.fragmentFunction!=nil,issue.localizedDescription);
        md.colorAttachments[0].pixelFormat=MTLPixelFormatRGBA16Float;id<MTLRenderPipelineState> material=[ctx->device newRenderPipelineStateWithDescriptor:md error:&issue];require(material!=nil,issue.localizedDescription);
        Lighting ml=lighting(1,1);ml.materials[0]=(simd_float4){1,1,1,1};ml.materials[1]=(simd_float4){1,1,1,1};ml.materials[2]=(simd_float4){1,1,1,0};
        Lighting mdry=ml;mdry.p[18].z=0;
        float *stacked=probe(enc,ctx,material,ml,0,w,h),*shared=probe(enc,ctx,material,ml,1,w,h),*plain=probe(enc,ctx,material,ml,2,w,h),*flags=probe(enc,ctx,material,ml,3,w,h);
        float *dryShared=probe(enc,ctx,material,mdry,1,w,h),*dryPlain=probe(enc,ctx,material,mdry,2,w,h);
        for(int i=0;i<n;i++)require(fabsf(dryShared[i]-dryPlain[i])<1e-4f,@"Dry material shading changed");
        int materials=0;double stackedGain=0,sharedGain=0,wetChange=0;
        for(int column=0;column<w/8;column++){
            bool shaded=flags[(column*8)*4]>.5f;materials+=shaded;
            for(int y=0;y<h;y++)for(int x=column*8;x<column*8+8;x++)for(int c=0;c<3;c++){
                int i=(y*w+x)*4+c;
                if(!shaded){require(fabsf(stacked[i]-shared[i])<1e-4f,@"Plain surface: shared path differs from the wet sheen");continue;}
                stackedGain+=fmax(0,stacked[i]-plain[i]);sharedGain+=fmax(0,shared[i]-plain[i]);wetChange+=fabs(shared[i]-plain[i]);
            }
        }
        require(materials>4,@"Too few material profiles shaded");require(!(flags[0]>.5f),@"No-profile column took the material path");
        require(wetChange>.01,@"Wet materials unchanged");require(sharedGain<stackedGain,@"Wet materials still stack two highlights");
        printf("PASS: wet materials: %d profiles take one shared highlight and reflection (gain %.1f, was %.1f stacked); plain surfaces keep the sheen; dry unchanged\n",materials,sharedGain,stackedGain);
        free(stacked);free(shared);free(plain);free(flags);free(dryShared);free(dryPlain);
    }
    free(dry);free(wet);free(wall);free(covered);free(torch);free(film);free(puddles);free(strong);
    if(getenv("SILICONSHADERS_WET_BENCH")){
        double samples[2][32];
        for(int frame=0;frame<80;frame++)@autoreleasepool{
            int on=frame%2;MTLTextureDescriptor *d=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA16Float width:1920 height:1080 mipmapped:NO];
            d.storageMode=MTLStorageModePrivate;d.usage=MTLTextureUsageRenderTarget;id<MTLTexture> t=[ctx->device newTextureWithDescriptor:d];
            id<MTLTexture> colors[]={t};float clear[]={0,0,0,0,0};mc_render_begin(enc,1,colors,clear,nil,0,0,1920,1080);
            Lighting l=lighting(on,.5);uint32_t kind=0;Sun sun={.cam={-300,70,500,0},.offset={-.25,-.5,-.75,12.5},.sun={.6,.8,0,1},.config={8,1.f/512,.00015,1}};
            [enc->render setRenderPipelineState:pipeline];[enc->render setFragmentBytes:&l length:sizeof l atIndex:0];[enc->render setFragmentBytes:&kind length:4 atIndex:1];
            [enc->render setFragmentBytes:&sun length:sizeof sun atIndex:2];[enc->render drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
            id<MTLCommandBuffer> c=mc_enc_commit(enc);[c waitUntilCompleted];if(frame>=16)samples[on][(frame-16)/2]=(c.GPUEndTime-c.GPUStartTime)*1000;[t release];
        }
        for(int e=0;e<2;e++)for(int i=0;i<32;i++)for(int j=i+1;j<32;j++)if(samples[e][j]<samples[e][i]){double x=samples[e][i];samples[e][i]=samples[e][j];samples[e][j]=x;}
        double off=(samples[0][15]+samples[0][16])*.5,on=(samples[1][15]+samples[1][16])*.5;
        printf("BENCH: %s, 1920x1080, every pixel open wet ground (worst case), median of 32: dry %.4f ms wet %.4f ms delta %.4f ms (GPU command time, not gameplay)\n",ctx->device.name.UTF8String,off,on,on-off);
    }
    return 0;
}}
