// Analytic reflection walls seen obliquely, foreground ghosts and grazing waves.
#include "sun_test_common.h"
typedef struct { simd_float4x4 view,inverse;simd_float4 sun,fog,state,camera,screen,controls,waves,optics,effects,quality; } Water;
int main(int argc,const char **argv) {
 @autoreleasepool {
    require(argc==3||argc==4,@"Usage: water_reflection_check TERRAIN SUN [BEFORE_TERRAIN]");
    NSString *source=[[NSString stringWithContentsOfFile:@(argv[1]) encoding:NSUTF8StringEncoding error:nil]
        stringByAppendingString:[NSString stringWithContentsOfFile:@(argv[2]) encoding:NSUTF8StringEncoding error:nil]];
    if(argc==4) {
        NSString *before=[NSString stringWithContentsOfFile:@(argv[3]) encoding:NSUTF8StringEncoding error:nil];
        NSRange start=[before rangeOfString:@"static float3 waterReflection("],end=[before rangeOfString:@"// Trace the transmitted ray"];
        NSString *legacy=[[before substringWithRange:NSMakeRange(start.location,end.location-start.location)] stringByReplacingOccurrencesOfString:@"waterReflection(" withString:@"waterReflectionBefore("];
        source=[source stringByAppendingString:legacy];
    }
    NSString *legacy=argc==4?@"float beforeConfidence,beforeTravel;waterReflectionBefore(p,ray,f,scene,depth,beforeConfidence,beforeTravel);out[256+i].w=beforeConfidence;":@"";
    source=[source stringByAppendingFormat:@"\nkernel void reflection_probe(constant WaterFrame &f [[buffer(0)]],device float4 *out [[buffer(1)]],constant uint &mode [[buffer(2)]],texture2d<float> scene [[texture(0)]],depth2d<float> depth [[texture(1)]],uint i [[thread_position_in_grid]]) { if(i>=256)return; float2 uv=float2(.12+.76*(float(i)+.5)/256.0,.5);float3 dir;waterWorld(uv,.5,f,dir);dir=normalize(dir);float3 p=dir*(-.5/dir.y);float3 normal=waterFacingNormal(p,float3(0,1,0),f),ray=reflect(normalize(p),normal);if(mode==1){p=float3((uv.x-.5)*20,-.5,-100);ray=normalize(float3(1,.05,-.005));}if(mode==2){p=float3((uv.x-.5)*100,-.5,-100);normal=waterFacingNormal(p,float3(0,1,0),f);ray=reflect(normalize(p),normal);} float confidence,travel;float3 rgb=waterReflection(p,ray,float3(0,1,0),f,scene,depth,confidence,travel);float along=(-32-p.z)/ray.z;float3 hit=p+ray*along;float4 c=f.view*float4(hit,1);float2 target=c.xy/c.w*.5+.5;float edge=min(min(target.x,target.y),min(1-target.x,1-target.y));bool expected=along>0&&along<48&&c.w>0&&edge>.08;out[i]=float4(rgb,confidence);out[256+i]=float4(expected?1:0,dot(ray,float3(0,1,0)),travel,0);%@ }\n",legacy];
    if(argc==4)for(int variant=0;variant<2;variant++) {
        NSString *call=variant==0?@"waterReflectionBefore(p,ray,f,scene,depth,confidence,travel)":@"waterReflection(p,ray,float3(0,1,0),f,scene,depth,confidence,travel)";
        source=[source stringByAppendingFormat:@"\nkernel void reflection_bench_%d(constant WaterFrame &f [[buffer(0)]],device float4 *out [[buffer(1)]],texture2d<float> scene [[texture(0)]],depth2d<float> depth [[texture(1)]],uint i [[thread_position_in_grid]]) {float2 uv=float2(.12+.76*(float(i&255)+.5)/256.0,.49+.02*(float(i>>8)+.5)/256.0);float3 dir;waterWorld(uv,.5,f,dir);dir=normalize(dir);float3 p=dir*(-.5/dir.y),ray=reflect(dir,float3(0,1,0));float confidence,travel;float3 rgb=%@;out[i]=float4(rgb,confidence); }\n",variant,call];
    }
    id<MTLDevice> device=MTLCreateSystemDefaultDevice();require(device!=nil,@"No Metal device");NSError *issue=nil;
    id<MTLLibrary> library=[device newLibraryWithSource:source options:nil error:&issue];require(library!=nil,issue.localizedDescription);
    id<MTLFunction> fn=[library newFunctionWithName:@"reflection_probe"];id<MTLComputePipelineState> pipeline=[device newComputePipelineStateWithFunction:fn error:&issue];require(pipeline!=nil,issue.localizedDescription);
    id<MTLCommandQueue> queue=[device newCommandQueue];id<MTLBuffer> output=[device newBufferWithLength:512*sizeof(simd_float4) options:MTLResourceStorageModeShared];
    int size=512;MTLTextureDescriptor *td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:size height:size mipmapped:NO];
    td.storageMode=MTLStorageModeShared;td.usage=MTLTextureUsageShaderRead;id<MTLTexture> scene=[device newTextureWithDescriptor:td];
    uint8_t *colors=malloc(size*size*4);for(int i=0;i<size*size;i++){colors[i*4]=240;colors[i*4+1]=35;colors[i*4+2]=20;colors[i*4+3]=255;}
    [scene replaceRegion:MTLRegionMake2D(0,0,size,size) mipmapLevel:0 withBytes:colors bytesPerRow:size*4];free(colors);
    td.pixelFormat=MTLPixelFormatDepth32Float;id<MTLTexture> depth=[device newTextureWithDescriptor:td];int checked=0,legacyGhosts=0;Water benchmarkFrame;
    for(int wide=0;wide<2;wide++)for(int side=-1;side<=1;side++)for(uint32_t mode=0;mode<3;mode++) {
        float cot=1/tanf((wide?110:60)*M_PI/360),near=.1,far=256,angle=side*.95f;
        simd_float4x4 projection={.columns={{cot,0,0,0},{0,cot,0,0},{0,0,near/(far-near),-1},{0,0,far*near/(far-near),0}}};
        simd_float4x4 yaw={.columns={{cosf(angle),0,sinf(angle),0},{0,1,0,0},{-sinf(angle),0,cosf(angle),0},{0,0,0,1}}};
        simd_float4x4 pitch={.columns={{1,0,0,0},{0,.995004,.0998334,0},{0,-.0998334,.995004,0},{0,0,0,1}}};
        Water frame={.view=simd_mul(projection,simd_mul(pitch,yaw)),.screen={size,size,64,1},.controls={1,32,0,0},.waves={mode==2?4:0,1,1,1},.optics={1,1,64,1},.effects={1,1,0,256}};frame.inverse=simd_inverse(frame.view);
        benchmarkFrame=frame;
        float *values=calloc(size*size,sizeof(float));float wall=mode==1?-99:-32;
        for(int y=0;y<size;y++)for(int x=0;x<size;x++) {
            simd_float4 q=simd_mul(frame.inverse,(simd_float4){2*(x+.5f)/size-1,2*(y+.5f)/size-1,.5,1});simd_float3 dir=simd_make_float3(q)/q.w;
            if(dir.z<0){simd_float3 hit=dir*(wall/dir.z);simd_float4 clip=simd_mul(frame.view,(simd_float4){hit.x,hit.y,hit.z,1});values[y*size+x]=clip.z/clip.w;}
        }
        [depth replaceRegion:MTLRegionMake2D(0,0,size,size) mipmapLevel:0 withBytes:values bytesPerRow:size*4];free(values);
        id<MTLCommandBuffer> command=[queue commandBuffer];id<MTLComputeCommandEncoder> c=[command computeCommandEncoder];
        [c setComputePipelineState:pipeline];[c setBytes:&frame length:sizeof frame atIndex:0];[c setBuffer:output offset:0 atIndex:1];[c setBytes:&mode length:sizeof mode atIndex:2];[c setTexture:scene atIndex:0];[c setTexture:depth atIndex:1];
        [c dispatchThreads:MTLSizeMake(256,1,1) threadsPerThreadgroup:MTLSizeMake(32,1,1)];[c endEncoding];[command commit];[command waitUntilCompleted];require(command.status==MTLCommandBufferStatusCompleted,command.error.localizedDescription);
        simd_float4 *result=output.contents;int expected=0,hits=0,ghosts=0;
        for(int i=0;i<256;i++) {
            require(isfinite(result[i].w)&&isfinite(result[256+i].y),@"Nonfinite reflection at grazing angle");
            if(mode==0&&result[256+i].x>0){expected++;if(result[i].w>.5&&result[i].x>result[i].y*4)hits++;}
            if(mode==1){if(result[i].w>0)ghosts++;if(result[256+i].w>.1)legacyGhosts++;}
            require(result[256+i].y>=-1e-5,@"A wave reflected through the water plane");
        }
        printf("fov=%d yaw=%d mode=%u analytic=%d hits=%d foregroundGhosts=%d\n",wide?110:60,side*54,mode,expected,hits,ghosts);fflush(stdout);
        require(ghosts==0,@"Reflections accepted foreground surfaces without a ray intersection");
        require(hits>=expected*.95,@"Oblique water reflection dropped visible wall intersections");checked++;
    }
    if(argc==4){printf("old foreground ghosts=%d\n",legacyGhosts);require(legacyGhosts>0,@"Fixture did not reproduce the previous foreground reflection bug");}
    if(argc==4) {
        id<MTLBuffer> benchOutput=[device newBufferWithLength:65536*sizeof(simd_float4) options:MTLResourceStorageModeShared];
        id<MTLComputePipelineState> bench[2];
        for(int variant=0;variant<2;variant++) {
            id<MTLFunction> function=[library newFunctionWithName:[NSString stringWithFormat:@"reflection_bench_%d",variant]];
            bench[variant]=[device newComputePipelineStateWithFunction:function error:&issue];require(bench[variant]!=nil,issue.localizedDescription);[function release];
        }
        double timings[2][8];
        for(int round=0;round<12;round++)for(int order=0;order<2;order++) {
            int variant=order^(round&1);benchmarkFrame.controls.y=variant?32:20;
            id<MTLCommandBuffer> command=[queue commandBuffer];id<MTLComputeCommandEncoder> c=[command computeCommandEncoder];
            [c setComputePipelineState:bench[variant]];[c setBytes:&benchmarkFrame length:sizeof benchmarkFrame atIndex:0];[c setBuffer:benchOutput offset:0 atIndex:1];[c setTexture:scene atIndex:0];[c setTexture:depth atIndex:1];
            [c dispatchThreads:MTLSizeMake(65536,1,1) threadsPerThreadgroup:MTLSizeMake(64,1,1)];[c endEncoding];[command commit];[command waitUntilCompleted];require(command.status==MTLCommandBufferStatusCompleted,command.error.localizedDescription);
            if(round>=4)timings[variant][round-4]=(command.GPUEndTime-command.GPUStartTime)*1000;
        }
        for(int v=0;v<2;v++)for(int i=0;i<8;i++)for(int j=i+1;j<8;j++)if(timings[v][j]<timings[v][i]){double t=timings[v][i];timings[v][i]=timings[v][j];timings[v][j]=t;}
        printf("SSR fixture GPU median for 65536 rays: old20=%.4fms new32=%.4fms (8 paired samples; not whole-game FPS)\n",(timings[0][3]+timings[0][4])*.5,(timings[1][3]+timings[1][4])*.5);
        [benchOutput release];[bench[0] release];[bench[1] release];
    }
    printf("PASS: %d oblique reflection cases, 60/110-degree FOV, foreground rejection and strong grazing waves\n",checked);
 }
 return 0;
}
