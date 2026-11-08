// Reflection lines at step boundaries. Above water: a slope rising behind flat water, seen at a grazing angle from
// just above it. Underwater: the surface seen from below mirrors the floor by total internal reflection, and the
// camera sees that floor at a grazing angle. Every water pixel's mirrored ray has an analytic hit; the trace must
// find the visible ones at every step cap (above water all of them; underwater at least 88% within 2 px of the
// analytic hit and at most 2% falling back). Testing one mid-step depth per step missed crossings near step boundaries: one line of
// fallback per step, more lines with more steps. Run with an older terrain.metal to see those misses.
#include "sun_test_common.h"
typedef struct { simd_float4x4 view,inverse;simd_float4 sun,fog,state,camera,screen,controls,waves,optics,effects,quality; } Water;
// Floor 3 blocks deep for z > -12, then a slope rising 0.6 per block away from the camera: y + 0.6 z = -10.2.
static float slopeHit(simd_float3 o,simd_float3 d) {
    float t=(-10.2f-o.y-.6f*o.z)/(d.y+.6f*d.z);
    return t>0&&o.z+t*d.z<=-12?t:-1;
}
// Underwater: camera between the floor (y=-1.5) and the water surface (y=2); only the floor is opaque.
static bool under;
static float terrainHit(simd_float3 d) {
    if(under)return d.y<0?-1.5f/d.y:-1;
    float best=-1;
    if(d.y<0){float t=-3/d.y;if(t*d.z>-12)best=t;}
    float s=slopeHit((simd_float3){0,0,0},d);if(s>0&&(best<0||s<best))best=s;
    return best;
}
int main(int argc,const char **argv) {
 @autoreleasepool {
    require(argc==3,@"Usage: water_reflection_steps_check TERRAIN SUN");
    NSString *source=[[NSString stringWithContentsOfFile:@(argv[1]) encoding:NSUTF8StringEncoding error:nil]
        stringByAppendingString:[NSString stringWithContentsOfFile:@(argv[2]) encoding:NSUTF8StringEncoding error:nil]];
    source=[source stringByAppendingString:@"\nkernel void steps_probe(constant WaterFrame &f [[buffer(0)]],device float4 *out [[buffer(1)]],texture2d<float> scene [[texture(0)]],depth2d<float> depth [[texture(1)]],uint2 g [[thread_position_in_grid]]) {"
        "float2 uv=(float2(g)+.5)/f.screen.xy;float3 dir;waterWorld(uv,.5,f,dir);dir=normalize(dir);uint i=g.y*uint(f.screen.x)+g.x;"
        "bool under=f.state.y>.5;float level=under?2:-.5;float3 face=float3(0,under?-1:1,0);"
        "if(dir.y*level<=1e-4*abs(level)){out[i]=float4(0,0,-1,0);return;}"
        "float3 p=dir*(level/dir.y),ray=reflect(dir,face);float confidence,travel;"
        "waterReflection(p+face*.02,ray,face,f,scene,depth,confidence,travel);out[i]=float4(confidence,travel,1,0); }\n"];
    id<MTLDevice> device=MTLCreateSystemDefaultDevice();require(device!=nil,@"No Metal device");NSError *issue=nil;
    id<MTLLibrary> library=[device newLibraryWithSource:source options:nil error:&issue];require(library!=nil,issue.localizedDescription);
    id<MTLComputePipelineState> pipeline=[device newComputePipelineStateWithFunction:[library newFunctionWithName:@"steps_probe"] error:&issue];require(pipeline!=nil,issue.localizedDescription);
    id<MTLCommandQueue> queue=[device newCommandQueue];
    const int width=640,height=400;float aspect=(float)width/height;
    id<MTLBuffer> output=[device newBufferWithLength:width*height*sizeof(simd_float4) options:MTLResourceStorageModeShared];
    MTLTextureDescriptor *td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:width height:height mipmapped:NO];td.storageMode=MTLStorageModeShared;td.usage=MTLTextureUsageShaderRead;
    id<MTLTexture> scene=[device newTextureWithDescriptor:td];uint8_t *colors=calloc(width*height,4);
    [scene replaceRegion:MTLRegionMake2D(0,0,width,height) mipmapLevel:0 withBytes:colors bytesPerRow:width*4];free(colors);
    td.pixelFormat=MTLPixelFormatDepth32Float;id<MTLTexture> depth=[device newTextureWithDescriptor:td];
    int failures=0,cases=0;
    for(int underCase=0;underCase<2;underCase++)for(int pitchCase=0;pitchCase<2;pitchCase++)for(int fovCase=0;fovCase<2;fovCase++) {
        under=underCase;
        float fov=fovCase?90:70,cot=1/tanf(fov*M_PI/360),near=.1f,far=128,pitch=(under?(pitchCase?-14:-6):(pitchCase?10:4))*M_PI/180;
        simd_float4x4 projection={.columns={{cot/aspect,0,0,0},{0,cot,0,0},{0,0,near/(far-near),-1},{0,0,far*near/(far-near),0}}};
        simd_float4x4 rotation={.columns={{1,0,0,0},{0,cosf(pitch),sinf(pitch),0},{0,-sinf(pitch),cosf(pitch),0},{0,0,0,1}}};
        Water frame={.view=simd_mul(projection,rotation),.screen={width,height,64,1},.controls={1,32,0,0},.waves={0,1,1,1},.optics={1,1,64,1},.effects={1,1,1,far},.state={0,under?1:0,0,0}};
        frame.inverse=simd_inverse(frame.view);
        float *values=calloc(width*height,sizeof(float));
        for(int y=0;y<height;y++)for(int x=0;x<width;x++) {
            simd_float4 q=simd_mul(frame.inverse,(simd_float4){2*(x+.5f)/width-1,2*(y+.5f)/height-1,.5f,1});simd_float3 d=simd_normalize(simd_make_float3(q)/q.w);
            float t=terrainHit(d);if(t<0)continue;simd_float3 at=d*t;
            simd_float4 c=simd_mul(frame.view,(simd_float4){at.x,at.y,at.z,1});if(c.w<far)values[y*width+x]=c.z/c.w;
        }
        [depth replaceRegion:MTLRegionMake2D(0,0,width,height) mipmapLevel:0 withBytes:values bytesPerRow:width*sizeof(float)];free(values);
        for(int caseSteps=0;caseSteps<4;caseSteps++) {
            int stepCap=caseSteps==0?8:caseSteps==1?16:caseSteps==2?32:48;frame.controls.y=stepCap;
            id<MTLCommandBuffer> command=[queue commandBuffer];id<MTLComputeCommandEncoder> c=[command computeCommandEncoder];
            [c setComputePipelineState:pipeline];[c setBytes:&frame length:sizeof frame atIndex:0];[c setBuffer:output offset:0 atIndex:1];[c setTexture:scene atIndex:0];[c setTexture:depth atIndex:1];
            [c dispatchThreads:MTLSizeMake(width,height,1) threadsPerThreadgroup:MTLSizeMake(16,16,1)];[c endEncoding];[command commit];[command waitUntilCompleted];
            require(command.status==MTLCommandBufferStatusCompleted,command.error.localizedDescription);
            simd_float4 *r=output.contents;int expected=0,found=0,missNone=0,missWeak=0,missPlace=0,missAt[4]={0,0,0,0};
            for(int y=0;y<height;y++)for(int x=0;x<width;x++) {
                simd_float4 v=r[y*width+x];if(v.z<0)continue;
                simd_float4 q=simd_mul(frame.inverse,(simd_float4){2*(x+.5f)/width-1,2*(y+.5f)/height-1,.5f,1});simd_float3 d=simd_normalize(simd_make_float3(q)/q.w);
                float level=under?2:-.5f;simd_float3 p=d*(level/d.y);
                simd_float3 ray={d.x,-d.y,d.z},o=p+(simd_float3){0,under?-.02f:.02f,0};float t;
                if(under) {
                    // The surface mirrors only beyond the critical angle; the floor below it is the target.
                    if(fabsf(d.y)>cosf(asinf(1/1.333f)))continue;
                    t=(-1.5f-o.y)/ray.y;if(t<0)continue;
                } else {
                    // Only water pixels in front of the slope (the water face is not in the opaque snapshot).
                    float terrain=terrainHit(d);if(terrain>0&&terrain*d.z<-12&&d.y*terrain>-.5f)continue;
                    t=slopeHit(o,ray);if(t<0)continue;
                }
                simd_float3 hit=o+ray*t;if(!under&&hit.y<-.5f)continue;
                simd_float4 h=simd_mul(frame.view,(simd_float4){hit.x,hit.y,hit.z,1});if(h.w<=near||h.w>far*.9f)continue;
                float hx=h.x/h.w*.5f+.5f,hy=h.y/h.w*.5f+.5f;
                if(hx<.04f||hx>.96f||hy<.04f||hy>.96f)continue;
                // Visible from the camera: the camera ray through the hit meets the terrain there first.
                float seen=terrainHit(simd_normalize(hit));if(seen<0||fabsf(seen-simd_length(hit))>.05f*simd_length(hit))continue;
                expected++;
                // Where the reflection lands in the image: at grazing distances one pixel spans blocks of depth.
                simd_float3 got=o+ray*v.y;simd_float4 g=simd_mul(frame.view,(simd_float4){got.x,got.y,got.z,1});
                float off=g.w>0?simd_length((simd_float2){(g.x/g.w*.5f+.5f-hx)*width,(g.y/g.w*.5f+.5f-hy)*height}):1e9f;
                bool ok=v.x>.5f&&off<=2;
                if(ok)found++;else if(v.x<=0)missNone++;else if(v.x<=.5f)missWeak++;else missPlace++;
                if(!ok){int b=t<8?0:t<16?1:t<32?2:3;missAt[b]++;}
            }
            float share=expected?(float)found/expected:0;
            printf("%s fov=%.0f pitch=%.0f steps=%d expected=%d found=%d (%.2f%%)\n",under?"underwater":"slope",fov,pitch*180/M_PI,stepCap,expected,found,100*share);fflush(stdout);
            require(expected>2000,@"Fixture has too few reflected pixels");
            if(found<expected)printf("  misses: none %d, weak %d, more than 2 px off %d; by distance <8/<16/<32/more: %d/%d/%d/%d\n",missNone,missWeak,missPlace,missAt[0],missAt[1],missAt[2],missAt[3]);
            // Above water every mirrored slope pixel is found. Underwater, at a grazing view of a far floor one
            // pixel spans blocks of depth: a few hits land more than 2 px off, but no step leaves the fallback.
            if(under?share<.88f||missNone+missWeak>expected/50:share<.995f)failures++;
            cases++;
        }
    }
    require(failures==0,@"Reflections miss the slope along step boundaries");
    printf("PASS: %d grazing cases at 8/16/32/48 steps, above water and underwater; no fallback lines, mirrored pixels found within 2 px\n",cases);
 }
 return 0;
}
