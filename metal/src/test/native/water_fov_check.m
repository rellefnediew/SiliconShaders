// Every analytic reflection target spans the camera viewport, including side pixels.
#include "sun_test_common.h"
typedef struct { simd_float4x4 view,inverse;simd_float4 sun,fog,state,camera,screen,controls,waves,optics,effects,quality; } Water;
int main(int argc,const char **argv) {
 @autoreleasepool {
    require(argc==3,@"Usage: water_fov_check TERRAIN SUN");
    NSString *source=[[NSString stringWithContentsOfFile:@(argv[1]) encoding:NSUTF8StringEncoding error:nil]
        stringByAppendingString:[NSString stringWithContentsOfFile:@(argv[2]) encoding:NSUTF8StringEncoding error:nil]];
    source=[source stringByAppendingString:@"\nkernel void fov_probe(constant WaterFrame &f [[buffer(0)]],device float4 *out [[buffer(1)]],constant float &distance [[buffer(2)]],texture2d<float> scene [[texture(0)]],depth2d<float> depth [[texture(1)]],uint i [[thread_position_in_grid]]) {float margin=3/f.screen.x;float2 uv=float2(mix(margin,1-margin,float(i)/511.0),.65);float3 dir;waterWorld(uv,.5,f,dir);float4 clip=f.view*float4(dir,1);float3 hit=dir*(distance/clip.w);float3 mirror=float3(0,-1,0);float3 p=mix(mirror,hit,.5/(hit.y+1));float3 ray=reflect(normalize(p),float3(0,1,0));float confidence,travel;float3 rgb=waterReflection(p,ray,float3(0,1,0),f,scene,depth,confidence,travel);out[i]=float4(rgb,confidence); }\n"];
    id<MTLDevice> device=MTLCreateSystemDefaultDevice();require(device!=nil,@"No Metal device");NSError *issue=nil;
    id<MTLLibrary> library=[device newLibraryWithSource:source options:nil error:&issue];require(library!=nil,issue.localizedDescription);
    id<MTLFunction> function=[library newFunctionWithName:@"fov_probe"];id<MTLComputePipelineState> pipeline=[device newComputePipelineStateWithFunction:function error:&issue];require(pipeline!=nil,issue.localizedDescription);
    id<MTLCommandQueue> queue=[device newCommandQueue];id<MTLBuffer> output=[device newBufferWithLength:512*sizeof(simd_float4) options:MTLResourceStorageModeShared];
    int cases=0,missing=0;
    for(int wide=0;wide<3;wide++)for(int landscape=0;landscape<2;landscape++)for(int distant=0;distant<3;distant++) {
        int width=landscape?820:512,height=512;float aspect=(float)width/height,fov=wide==0?60:wide==1?90:110,distance=distant==0?24:distant==1?96:160;
        float cot=1/tanf(fov*M_PI/360),near=.1,far=256;
        simd_float4x4 projection={.columns={{cot/aspect,0,0,0},{0,cot,0,0},{0,0,near/(far-near),-1},{0,0,far*near/(far-near),0}}};
        simd_float4x4 pitch={.columns={{1,0,0,0},{0,.995004,.0998334,0},{0,-.0998334,.995004,0},{0,0,0,1}}};
        Water frame={.view=simd_mul(projection,pitch),.screen={width,height,64,1},.controls={1,32,0,0},.waves={0,1,1,1},.optics={1,1,64,1},.effects={1,1,0,128}};frame.inverse=simd_inverse(frame.view);
        MTLTextureDescriptor *td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:width height:height mipmapped:NO];td.storageMode=MTLStorageModeShared;td.usage=MTLTextureUsageShaderRead;
        id<MTLTexture> scene=[device newTextureWithDescriptor:td];uint8_t *colors=malloc(width*height*4);float *values=malloc(width*height*4);
        float z=-near/(far-near)+(far*near/(far-near))/distance;
        for(int i=0;i<width*height;i++){colors[4*i]=240;colors[4*i+1]=35;colors[4*i+2]=20;colors[4*i+3]=255;values[i]=z;}
        [scene replaceRegion:MTLRegionMake2D(0,0,width,height) mipmapLevel:0 withBytes:colors bytesPerRow:width*4];free(colors);
        td.pixelFormat=MTLPixelFormatDepth32Float;id<MTLTexture> depth=[device newTextureWithDescriptor:td];[depth replaceRegion:MTLRegionMake2D(0,0,width,height) mipmapLevel:0 withBytes:values bytesPerRow:width*4];free(values);
        id<MTLCommandBuffer> command=[queue commandBuffer];id<MTLComputeCommandEncoder> c=[command computeCommandEncoder];
        [c setComputePipelineState:pipeline];[c setBytes:&frame length:sizeof frame atIndex:0];[c setBuffer:output offset:0 atIndex:1];[c setBytes:&distance length:sizeof distance atIndex:2];[c setTexture:scene atIndex:0];[c setTexture:depth atIndex:1];
        [c dispatchThreads:MTLSizeMake(512,1,1) threadsPerThreadgroup:MTLSizeMake(32,1,1)];[c endEncoding];[command commit];[command waitUntilCompleted];require(command.status==MTLCommandBufferStatusCompleted,command.error.localizedDescription);
        simd_float4 *result=output.contents;int hits=0;float left=result[0].w,right=result[511].w;
        for(int i=0;i<512;i++){require(isfinite(result[i].w),@"Nonfinite full-FOV reflection");if(result[i].w>.95&&result[i].x>4*result[i].y)hits++;}
        printf("fov=%.0f aspect=%.2f viewDepth=%.0f hits=%d/512 expected=%d edgeConfidence=%.3f/%.3f\n",fov,aspect,distance,hits,distant<2?512:0,left,right);fflush(stdout);missing+=distant<2?512-hits:hits;cases++;
        if(wide==1&&landscape==1&&distant==0) {
            // Edge fade (camera.w, the menu's reflection edge fade): nearly gone at the edge, full inside, rising in between.
            frame.camera.w=.08f;
            command=[queue commandBuffer];c=[command computeCommandEncoder];
            [c setComputePipelineState:pipeline];[c setBytes:&frame length:sizeof frame atIndex:0];[c setBuffer:output offset:0 atIndex:1];[c setBytes:&distance length:sizeof distance atIndex:2];[c setTexture:scene atIndex:0];[c setTexture:depth atIndex:1];
            [c dispatchThreads:MTLSizeMake(512,1,1) threadsPerThreadgroup:MTLSizeMake(32,1,1)];[c endEncoding];[command commit];[command waitUntilCompleted];require(command.status==MTLCommandBufferStatusCompleted,command.error.localizedDescription);
            result=output.contents;int full=0;bool rising=true;
            for(int i=0;i<512;i++){if(result[i].w>.999f)full++;if(i>0&&i<256&&result[i].w+1e-4f<result[i-1].w)rising=false;if(i>=256&&i<511&&result[i].w+1e-4f<result[i+1].w)rising=false;}
            printf("edge fade 0.08: edgeConfidence=%.3f/%.3f center=%.3f full=%d/512\n",result[0].w,result[511].w,result[256].w,full);fflush(stdout);
            require(result[0].w<.05f&&result[511].w<.05f&&result[256].w>.999f&&rising&&full>350&&full<480,@"Reflection edge fade is not a smooth fade toward the screen edges");
            frame.camera.w=0;cases++;
        }
        [scene release];[depth release];
    }
    printf("full-FOV missing reflections=%d across %d cases\n",missing,cases);
    require(missing==0,@"Visible reflection targets disappeared inside the actual camera FOV");
    printf("PASS: %d full-viewport reflection cases at 60/90/110-degree FOV, square/landscape, 24/96-block visible targets, far-plane rejection, three-pixel side margins and the edge fade\n",cases);
 }
 return 0;
}
