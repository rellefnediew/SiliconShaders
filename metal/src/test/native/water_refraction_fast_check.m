// The fast water trace switch: waterRefractionLinear against waterRefractionMatrices on blocky terrain
// under the water, with columns above it (foreground rejection). Same hits, same coordinates, same weights;
// checks projection linearity separately (<0.0005 px), including bob off and opposite translated/rolled phases.
// Both paths share UV arithmetic so last-bit UV rounding does not pick opposite sides of a block edge;
// the matrix path still reconstructs every depth hit, while the fast path tests scalar eye-relative depth/plane values.
// Prints the GPU time of both for a full screen of water pixels.
#include "sun_test_common.h"
typedef struct { simd_float4x4 view,inverse;simd_float4 sun,fog,state,camera,screen,controls,waves,optics,effects,quality; } Water;
static float columnHeight(int x,int z) {
    unsigned h=(unsigned)(x*73856093)^(unsigned)(z*19349663);h=(h^(h>>13))*1274126177u;h^=h>>16;
    if(h%23==0)return 1.5f;                 // a column out of the water, in front of the floor
    return -.7f-(float)(h%48)*.11f;         // floor 0.2 .. 5.4 blocks under the surface
}
int main(int argc,const char **argv) {
 @autoreleasepool {
    require(argc==3,@"Usage: water_refraction_fast_check TERRAIN SUN");
    NSString *source=[[NSString stringWithContentsOfFile:@(argv[1]) encoding:NSUTF8StringEncoding error:nil]
        stringByAppendingString:[NSString stringWithContentsOfFile:@(argv[2]) encoding:NSUTF8StringEncoding error:nil]];
    NSString *probe=@"\nstatic void refraction_setup(constant WaterFrame &f,depth2d<float> depth,uint2 g,thread float3 &p,thread float3 &ray,thread float2 &uv,thread float &path) {"
        "uv=(float2(g)+.5)/f.screen.xy;float3 direction;waterWorld(uv,.5,f,direction);direction=normalize(direction-f.camera.xyz);"
        "p=f.camera.xyz+direction*((-.5-f.camera.y)/min(direction.y,-1e-4));float3 n=waterFacingNormal(p,float3(0,1,0),f);float3 view=normalize(f.camera.xyz-p);"
        "ray=normalize(refract(-view,n,1.0/1.333));float3 behind;path=waterWorld(uv,depth.read(g),f,behind)?length(behind-p):16; }\n"
        "kernel void refraction_both(constant WaterFrame &f [[buffer(0)]],device float4 *out [[buffer(1)]],depth2d<float> depth [[texture(0)]],uint2 g [[thread_position_in_grid]]) {"
        "float3 p,ray;float2 uv;float path;refraction_setup(f,depth,g,p,ray,uv,path);uint i=g.y*uint(f.screen.x)+g.x;"
        "float a,b;float2 at=waterRefractionMatrices(p,float3(0,1,0),ray,uv,path,f,depth,a),bt=waterRefractionLinear(p,float3(0,1,0),ray,uv,path,f,depth,b);"
        "float clipError=0;float4 origin=f.view*float4(p,1),toward=f.view*float4(ray,0);for(uint j=1;j<=10;j++){float along=clamp(path*2,.25,32.0)*float(j*j)/100.0;float4 matrixClip=f.view*float4(p+ray*along,1),linearClip=origin+toward*along;if(matrixClip.w>.001&&linearClip.w>.001)clipError=max(clipError,length(matrixClip.xy/matrixClip.w-linearClip.xy/linearClip.w)*f.screen.x*.5);}out[2*i]=float4(at,a,clipError);out[2*i+1]=float4(bt,b,0); }\n"
        "kernel void refraction_matrices(constant WaterFrame &f [[buffer(0)]],device float4 *out [[buffer(1)]],depth2d<float> depth [[texture(0)]],uint2 g [[thread_position_in_grid]]) {"
        "float3 p,ray;float2 uv;float path;refraction_setup(f,depth,g,p,ray,uv,path);float w;out[g.y*uint(f.screen.x)+g.x].xy=waterRefractionMatrices(p,float3(0,1,0),ray,uv,path,f,depth,w)*w; }\n"
        "kernel void refraction_linear(constant WaterFrame &f [[buffer(0)]],device float4 *out [[buffer(1)]],depth2d<float> depth [[texture(0)]],uint2 g [[thread_position_in_grid]]) {"
        "float3 p,ray;float2 uv;float path;refraction_setup(f,depth,g,p,ray,uv,path);float w;out[g.y*uint(f.screen.x)+g.x].xy=waterRefractionLinear(p,float3(0,1,0),ray,uv,path,f,depth,w)*w; }\n";
    source=[source stringByAppendingString:probe];
    id<MTLDevice> device=MTLCreateSystemDefaultDevice();require(device!=nil,@"No Metal device");
    NSError *issue=nil;id<MTLLibrary> library=[device newLibraryWithSource:source options:nil error:&issue];require(library!=nil,issue.localizedDescription);
    id<MTLComputePipelineState> both=[device newComputePipelineStateWithFunction:[library newFunctionWithName:@"refraction_both"] error:&issue];require(both!=nil,issue.localizedDescription);
    id<MTLComputePipelineState> timed[2]={[device newComputePipelineStateWithFunction:[library newFunctionWithName:@"refraction_matrices"] error:&issue],
        [device newComputePipelineStateWithFunction:[library newFunctionWithName:@"refraction_linear"] error:&issue]};
    require(timed[0]!=nil&&timed[1]!=nil,issue.localizedDescription);
    id<MTLCommandQueue> queue=[device newCommandQueue];
    const int size=384;
    id<MTLBuffer> output=[device newBufferWithLength:2*size*size*sizeof(simd_float4) options:MTLResourceStorageModeShared];
    int cases=0;long rays=0,hits=0,mismatches=0,over[3]={0,0,0};float worstPx=0,worstWeight=0;
    for(int fov=0;fov<2;fov++)for(int tilt=0;tilt<2;tilt++)for(int waves=0;waves<2;waves++)for(int bob=0;bob<3;bob++) {
        float cot=1/tanf((fov?90:60)*M_PI/360),near=.1f,far=96,pitch=(tilt?55:25)*M_PI/180;
        simd_float4x4 projection={.columns={{cot,0,0,0},{0,cot,0,0},{0,0,near/(far-near),-1},{0,0,far*near/(far-near),0}}};
        simd_float4x4 rotation={.columns={{1,0,0,0},{0,cosf(pitch),sinf(pitch),0},{0,-sinf(pitch),cosf(pitch),0},{0,0,0,1}}};
        float phase=bob==1?1:bob==2?-1:0,angle=phase*.015;simd_float4x4 bobMatrix={.columns={{cosf(angle),sinf(angle),0,0},{-sinf(angle),cosf(angle),0,0},{0,0,1,0},{phase*.03,phase*.02,0,1}}};simd_float4x4 renderedView=simd_mul(bobMatrix,rotation);simd_float3 eye=simd_inverse(renderedView).columns[3].xyz;
        Water frame={.view=simd_mul(projection,renderedView),.camera={eye.x,eye.y,eye.z,0},.screen={size,size,64,1},.controls={1,32,0,0},.waves={waves?3:1,1,1,1},.optics={1,1,64,1},.effects={1,1,1,0},.state={41,0,0,0}};
        frame.inverse=simd_inverse(frame.view);
        // Depth of the blocky terrain (water itself is not in the opaque snapshot), marched on the CPU.
        float *values=calloc(size*size,sizeof(float));
        for(int y=0;y<size;y++)for(int x=0;x<size;x++) {
            simd_float4 q=simd_mul(frame.inverse,(simd_float4){2*(x+.5f)/size-1,2*(y+.5f)/size-1,.5f,1});simd_float3 dir=simd_normalize(simd_make_float3(q)/q.w-eye);
            for(float t=.2f;t<far*.9f;t+=.02f) {
                simd_float3 at=eye+dir*t;
                if(at.y<=columnHeight((int)floorf(at.x),(int)floorf(at.z))) {
                    simd_float4 clip=simd_mul(frame.view,(simd_float4){at.x,at.y,at.z,1});values[y*size+x]=clip.z/clip.w;break;
                }
            }
        }
        MTLTextureDescriptor *td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatDepth32Float width:size height:size mipmapped:NO];td.storageMode=MTLStorageModeShared;td.usage=MTLTextureUsageShaderRead;
        id<MTLTexture> depth=[device newTextureWithDescriptor:td];[depth replaceRegion:MTLRegionMake2D(0,0,size,size) mipmapLevel:0 withBytes:values bytesPerRow:size*sizeof(float)];free(values);
        id<MTLCommandBuffer> command=[queue commandBuffer];id<MTLComputeCommandEncoder> c=[command computeCommandEncoder];
        [c setComputePipelineState:both];[c setBytes:&frame length:sizeof frame atIndex:0];[c setBuffer:output offset:0 atIndex:1];[c setTexture:depth atIndex:0];
        [c dispatchThreads:MTLSizeMake(size,size,1) threadsPerThreadgroup:MTLSizeMake(16,16,1)];[c endEncoding];[command commit];[command waitUntilCompleted];
        require(command.status==MTLCommandBufferStatusCompleted,command.error.localizedDescription);
        simd_float4 *r=output.contents;long caseHits=0,caseMismatch=0;float casePx=0;
        for(int i=0;i<size*size;i++) {
            simd_float4 a=r[2*i],b=r[2*i+1];rays++;
            require(isfinite(b.x)&&isfinite(b.y)&&isfinite(b.z),@"Invalid fast refraction");
            bool ha=a.z>0,hb=b.z>0;
            require(a.w<.0005f,@"Linear clip progression diverged from full matrix projection");
            if(ha!=hb){caseMismatch++;continue;}
            if(ha)caseHits++;
            float px=simd_length(simd_make_float2(a-b))*size;
            // A weight-0 ray returns its coordinates without contributing to the image.
            if(ha){if(px>.01f)over[0]++;if(px>.1f)over[1]++;if(px>1)over[2]++;casePx=fmaxf(casePx,px);}
            worstWeight=fmaxf(worstWeight,fabsf(a.z-b.z));
        }
        printf("fov=%d pitch=%d waves=%d bob=%d hits=%ld hitChanges=%ld maxDiffPx=%.5f\n",fov?90:60,tilt?55:25,waves?3:1,bob,caseHits,caseMismatch,casePx);fflush(stdout);
        require(caseHits>size*size/4,@"Too few refraction hits for a meaningful comparison");
        hits+=caseHits;mismatches+=caseMismatch;worstPx=fmaxf(worstPx,casePx);cases++;
        // GPU time of each path over the same water pixels (16 rounds after one warmup, median).
        if(fov==1&&tilt==0&&waves==1&&bob==0) {
            double median[2];
            for(int k=0;k<2;k++) {
                double times[17];
                for(int round=0;round<17;round++) {
                    id<MTLCommandBuffer> cb=[queue commandBuffer];id<MTLComputeCommandEncoder> e=[cb computeCommandEncoder];
                    [e setComputePipelineState:timed[k]];[e setBytes:&frame length:sizeof frame atIndex:0];[e setBuffer:output offset:0 atIndex:1];[e setTexture:depth atIndex:0];
                    for(int repeat=0;repeat<8;repeat++)[e dispatchThreads:MTLSizeMake(size,size,1) threadsPerThreadgroup:MTLSizeMake(16,16,1)];
                    [e endEncoding];[cb commit];[cb waitUntilCompleted];times[round]=(cb.GPUEndTime-cb.GPUStartTime)*1000/8;
                }
                for(int x=1;x<17;x++)for(int y=x;y>1&&times[y]<times[y-1];y--){double t=times[y];times[y]=times[y-1];times[y-1]=t;}
                median[k]=times[9];
            }
            printf("GPU per %dx%d water pixels: matrices %.4f ms, linear %.4f ms (%.0f%%)\n",size,size,median[0],median[1],100*median[1]/median[0]);
        }
        [depth release];
    }
    // Float rounding can move a threshold test by an ulp; a hit must not appear or vanish beyond that.
    require(mismatches*10000<=rays,@"Fast refraction changed which rays hit");
    printf("hits differing by more than 0.01/0.1/1 px: %ld/%ld/%ld of %ld, largest %.5f px, weight %.6f\n",over[0],over[1],over[2],hits,worstPx,worstWeight);
    // At a block edge a last-bit difference can pick the neighbouring depth pixel during refinement:
    // allowed for at most 1 in 10000 hits, and never by a whole pixel.
    require(over[0]*10000<=hits&&over[2]==0&&worstWeight<.001f,@"Fast refraction moved refracted coordinates");
    printf("PASS: %d cases, %ld rays, %ld hits; %ld hit changes, %ld hits moved over 0.01 px (largest %.3f px), weight %.6f\n",cases,rays,hits,mismatches,over[0],worstPx,worstWeight);
 }
 return 0;
}
