// Analytic floor fixtures for shallow refraction and 110-degree screen edges. Rays whose floor point is off the
// screen sample just inside the edge, so the error to the analytic point counts only for on-screen points.
#include "sun_test_common.h"
typedef struct { simd_float4x4 view,inverse;simd_float4 sun,fog,state,camera,screen,controls,waves,optics,effects,quality; } Water;
int main(int argc,const char **argv) {
 @autoreleasepool {
    require(argc==3,@"Usage: water_optics_check TERRAIN SUN");
    NSString *source=[[NSString stringWithContentsOfFile:@(argv[1]) encoding:NSUTF8StringEncoding error:nil]
        stringByAppendingString:[NSString stringWithContentsOfFile:@(argv[2]) encoding:NSUTF8StringEncoding error:nil]];
    source=[source stringByAppendingString:@"\nkernel void optics_probe(constant WaterFrame &f [[buffer(0)]],device float4 *out [[buffer(1)]],depth2d<float> depth [[texture(0)]],constant float &floorY [[buffer(2)]],uint i [[thread_position_in_grid]]) { float2 uv=float2((float(i)+.5)/512.0,.42);float3 direction;waterWorld(uv,.5,f,direction);direction=normalize(direction);float3 p=direction*(-.5/direction.y);float3 n=waterNormal(p,float3(0,1,0),f);float3 ray=refract(direction,n,1.0/1.333);float3 exact=p+ray*((floorY-p.y)/ray.y);float4 c=f.view*float4(exact,1);float2 target=c.xy/c.w*.5+.5;float weight;float2 at=waterRefraction(p,float3(0,1,0),ray,uv,length(direction*(floorY/direction.y)-p),f,depth,weight);out[i]=float4(at,weight,all(target>0)&&all(target<1)?length((at-target)*f.screen.xy):0.0);out[512+i]=float4(n,1); }\n"];
    id<MTLDevice> device=MTLCreateSystemDefaultDevice();require(device!=nil,@"No Metal device");
    NSError *issue=nil;id<MTLLibrary> library=[device newLibraryWithSource:source options:nil error:&issue];require(library!=nil,issue.localizedDescription);
    id<MTLFunction> fn=[library newFunctionWithName:@"optics_probe"];id<MTLComputePipelineState> pipeline=[device newComputePipelineStateWithFunction:fn error:&issue];require(pipeline!=nil,issue.localizedDescription);
    id<MTLCommandQueue> queue=[device newCommandQueue];id<MTLBuffer> output=[device newBufferWithLength:1024*sizeof(simd_float4) options:MTLResourceStorageModeShared];
    int cases=0;
    for(int wide=0;wide<2;wide++)for(int shallow=0;shallow<2;shallow++) {
        int size=512;float cot=1/tanf((wide?110:60)*M_PI/360),near=.1,far=80,floorY=shallow?-.6:-4.5;
        simd_float4x4 projection={.columns={{cot,0,0,0},{0,cot,0,0},{0,0,near/(far-near),-1},{0,0,far*near/(far-near),0}}};
        simd_float4x4 rotation={.columns={{1,0,0,0},{0,.943858,.330350,0},{0,-.330350,.943858,0},{0,0,0,1}}};
        Water frame={.view=simd_mul(projection,rotation),.screen={size,size,64,1},.controls={1,20,0,0},.waves={1,1,1,1},.optics={1,1,64,1},.effects={1,1,0,0}};frame.inverse=simd_inverse(frame.view);
        float *values=calloc(size*size,sizeof(float));
        for(int y=0;y<size;y++)for(int x=0;x<size;x++) {
            simd_float4 q=simd_mul(frame.inverse,(simd_float4){2*(x+.5f)/size-1,2*(y+.5f)/size-1,.5,1});simd_float3 dir=simd_make_float3(q)/q.w;
            if(dir.y<0) {simd_float3 hit=dir*(floorY/dir.y);simd_float4 clip=simd_mul(frame.view,(simd_float4){hit.x,hit.y,hit.z,1});values[y*size+x]=clip.z/clip.w;}
        }
        MTLTextureDescriptor *td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatDepth32Float width:size height:size mipmapped:NO];td.storageMode=MTLStorageModeShared;td.usage=MTLTextureUsageShaderRead;
        id<MTLTexture> depth=[device newTextureWithDescriptor:td];[depth replaceRegion:MTLRegionMake2D(0,0,size,size) mipmapLevel:0 withBytes:values bytesPerRow:size*sizeof(float)];free(values);
        simd_float4 frozen[512];
        for(int mode=0;mode<5;mode++) {
            if(mode==4) {
                // Every depth pixel is in front of the water: never refract foreground blocks.
                float *front=malloc(size*size*sizeof(float));for(int i=0;i<size*size;i++)front[i]=.9f;
                [depth replaceRegion:MTLRegionMake2D(0,0,size,size) mipmapLevel:0 withBytes:front bytesPerRow:size*sizeof(float)];free(front);
            }
            frame.state.x=mode==3?37:0;frame.waves.x=mode==1?0:1;frame.waves.y=mode>=2?0:1;
            id<MTLCommandBuffer> command=[queue commandBuffer];id<MTLComputeCommandEncoder> c=[command computeCommandEncoder];
            [c setComputePipelineState:pipeline];[c setBytes:&frame length:sizeof frame atIndex:0];[c setBuffer:output offset:0 atIndex:1];[c setBytes:&floorY length:sizeof floorY atIndex:2];[c setTexture:depth atIndex:0];
            [c dispatchThreads:MTLSizeMake(512,1,1) threadsPerThreadgroup:MTLSizeMake(32,1,1)];[c endEncoding];[command commit];[command waitUntilCompleted];require(command.status==MTLCommandBufferStatusCompleted,command.error.localizedDescription);
            simd_float4 *result=output.contents;int hits=0;float maxError=0,maxJump=0;
            for(int i=0;i<512;i++) {
                require(isfinite(result[i].x)&&isfinite(result[i].y)&&isfinite(result[i].z)&&isfinite(result[i].w),@"Invalid refracted coordinates");
                if(result[i].z>.25){hits++;maxError=fmaxf(maxError,result[i].w);}
                if(i)maxJump=fmaxf(maxJump,fabsf(result[i].z-result[i-1].z));
                if(mode==1)require(simd_length(simd_make_float3(result[512+i])-(simd_float3){0,1,0})<1e-6,@"Zero amplitude left wave normals");
                if(mode==2)frozen[i]=result[512+i];
                if(mode==3)require(simd_length(frozen[i]-result[512+i])<1e-6,@"Zero wave speed still animated");
            }
            printf("fov=%d waterDepth=%.2f mode=%d hits=%d maxErrorPx=%.3f edgeJump=%.3f\n",wide?110:60,-.5-floorY,mode,hits,maxError,maxJump);fflush(stdout);
            if(mode==4)require(hits==0,@"Foreground blocks leaked through the water refraction");
            else require(hits>350,@"Refraction dropped visible shallow or wide-FOV floor");
            require(maxError<2.0,@"Refraction missed the analytic floor intersection");
            require(maxJump<.12,@"Refraction visibly snapped at the screen edge");cases++;
        }
        [depth release];
    }
    printf("PASS: %d shallow/deep and 60/110-degree optics cases; analytic refraction, smooth screen edges, foreground rejection, flat water and paused waves\n",cases);
 }
 return 0;
}
