// Compare the shipped water shaders with an immutable pre-optimization source on the same GPU.
// Full sample budgets and settings; includes zero coefficients, total internal reflection and HDR.
#include "sun_test_common.h"
typedef struct {simd_float4x4 view,inverse;simd_float4 sun,fog,state,camera,screen,controls,waves,optics,effects,quality;} Water;
static NSString *readSource(const char *p){NSError *e=nil;NSString *s=[NSString stringWithContentsOfFile:@(p) encoding:NSUTF8StringEncoding error:&e];require(s!=nil,e.localizedDescription);return s;}
static NSString *renamed(NSString *s){
 NSArray *names=@[@"waterLinear",@"waterDisplay",@"waterWorld",@"waterEye",@"waterNormal",@"waterViewDepth",@"waterCausticPoint",@"waterCausticLayer",@"waterCaustic",@"waterFacingNormal",@"waterReflectionPlane",@"waterReflectionPreset",@"waterReflection",@"waterRefractionInside",@"waterRefractionMatrices",@"waterRefractionState",@"waterRefractionLinear",@"waterRefraction",@"waterFresnel",@"ownWaterShade"];
 // Replace whole identifiers, longest names first are unnecessary with word boundaries.
 for(NSString *name in names){NSRegularExpression *re=[NSRegularExpression regularExpressionWithPattern:[NSString stringWithFormat:@"\\b%@\\b",name] options:0 error:nil];s=[re stringByReplacingMatchesInString:s options:0 range:NSMakeRange(0,s.length) withTemplate:[@"baseline_" stringByAppendingString:name]];}return s;
}
static id<MTLRenderPipelineState> pipeline(id<MTLDevice> d,id<MTLLibrary> lib,NSString *name,MTLPixelFormat format){NSError *e=nil;MTLRenderPipelineDescriptor *pd=[MTLRenderPipelineDescriptor new];pd.vertexFunction=[lib newFunctionWithName:@"sun_screen_vs"];pd.fragmentFunction=[lib newFunctionWithName:name];pd.colorAttachments[0].pixelFormat=format;id<MTLRenderPipelineState> p=[d newRenderPipelineStateWithDescriptor:pd error:&e];require(p!=nil,e.localizedDescription);[pd.vertexFunction release];[pd.fragmentFunction release];[pd release];return p;}
static id<MTLCommandBuffer> draw(id<MTLCommandQueue> q,id<MTLRenderPipelineState> p,id<MTLTexture> output,id<MTLTexture> scene,id<MTLTexture> depth,Water *f){id<MTLCommandBuffer> cb=[q commandBuffer];MTLRenderPassDescriptor *pd=[MTLRenderPassDescriptor renderPassDescriptor];pd.colorAttachments[0].texture=output;pd.colorAttachments[0].loadAction=MTLLoadActionDontCare;pd.colorAttachments[0].storeAction=MTLStoreActionStore;id<MTLRenderCommandEncoder> r=[cb renderCommandEncoderWithDescriptor:pd];[r setRenderPipelineState:p];[r setFragmentBytes:f length:sizeof *f atIndex:0];[r setFragmentTexture:scene atIndex:0];[r setFragmentTexture:depth atIndex:1];[r drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];[r endEncoding];[cb commit];return cb;}
static double median(double *a,int n){for(int i=1;i<n;i++)for(int j=i;j>0&&a[j]<a[j-1];j--){double v=a[j];a[j]=a[j-1];a[j-1]=v;}return (a[(n-1)/2]+a[n/2])*.5;}
int main(int argc,const char **argv){@autoreleasepool{
 require(argc==5,@"Usage: optimization_equivalence_check TERRAIN SUN ORIGINAL_TERRAIN ORIGINAL_SUN");
 NSString *terrain=readSource(argv[1]),*sun=readSource(argv[2]),*oldTerrain=readSource(argv[3]),*oldSun=readSource(argv[4]);
 NSRange start=[oldTerrain rangeOfString:@"static float3 waterLinear("],stop=[oldTerrain rangeOfString:@"struct SunResult"];
 require(start.location!=NSNotFound&&stop.location>start.location,@"Original water implementation missing");
 NSString *water=renamed([oldTerrain substringWithRange:NSMakeRange(start.location,stop.location-start.location)]);
 NSRange ps=[oldSun rangeOfString:@"fragment float4 own_water_post_fs("],pe=[oldSun rangeOfString:@"// Transparent surface optics"];
 require(ps.location!=NSNotFound&&pe.location>ps.location,@"Original underwater implementation missing");
 NSString *post=[renamed([oldSun substringWithRange:NSMakeRange(ps.location,pe.location-ps.location)]) stringByReplacingOccurrencesOfString:@"own_water_post_fs" withString:@"baseline_water_post_fs"];
 NSString *wrapper=@"\nfragment float4 optimization_surface_fs(SunScreenVertex in [[stage_in]],constant WaterFrame &f [[buffer(0)]],texture2d<float> scene [[texture(0)]],depth2d<float> depth [[texture(1)]]) {constexpr sampler linear(coord::normalized,address::clamp_to_edge,filter::linear);float2 uv=in.position.xy/f.screen.xy;float3 q;if(!waterWorld(uv,.5,f,q))return float4(0);float3 eye=waterEye(f),dir=normalize(q-eye);float plane=f.state.y>.5?.5:-.5;float t=(plane-eye.y)/dir.y;if(t<=0||!isfinite(t))return scene.sample(linear,uv);float3 p=eye+dir*t;return float4(ownWaterShade(p,float3(0,f.state.y>.5?-1:1,0),in.position.xy,float4(.15,.35,.5,.9),scene.sample(linear,uv).rgb,f,scene,depth),1);}\n";
 NSString *reference=[renamed(wrapper) stringByReplacingOccurrencesOfString:@"optimization_surface_fs" withString:@"baseline_surface_fs"];
 NSString *source=[NSString stringWithFormat:@"%@%@%@%@%@%@",terrain,sun,water,post,wrapper,reference];
 id<MTLDevice> device=MTLCreateSystemDefaultDevice();require(device!=nil,@"No Metal GPU");NSError *error=nil;id<MTLLibrary> lib=[device newLibraryWithSource:source options:nil error:&error];require(lib!=nil,error.localizedDescription);id<MTLCommandQueue> queue=[device newCommandQueue];printf("GPU %s\n",device.name.UTF8String);fflush(stdout);
 long compared=0,changed=0;float maxDifference=0;
 for(int test=0;test<22;test++){@autoreleasepool{
  BOOL perf=test>=14;int w=perf?1920:640,h=perf?1200:400;BOOL surface=(test<14?test%2:test>=16),inside=surface&&(test==3||test==7||test==16||test==17),hdr=test==12||test==13;
  float pitch=(inside?-35:35)*M_PI/180,cot=1/tanf((test==8||test==9?110:70)*M_PI/360);
  simd_float4x4 proj={.columns={{cot/((float)w/h),0,0,0},{0,cot,0,0},{0,0,.1f/79.9f,-1},{0,0,8.f/79.9f,0}}};
  simd_float4x4 rotation={.columns={{1,0,0,0},{0,cosf(pitch),sinf(pitch),0},{0,-sinf(pitch),cosf(pitch),0},{0,0,0,1}}};
  Water f={.view=simd_mul(proj,rotation),.sun={.6,.8,0,1},.fog={.05,.18,.25,1},.state={(float)(test*317),surface?(inside?1:0):1,1,1},.screen={w,h,64,1},.controls={1,32,0,0},.waves={1,1,1,1},.optics={1,1,64,1},.effects={1,1,1,80}};f.inverse=simd_inverse(f.view);
  if(test==2||test==3||test==15)f.optics.w=0;
  if(test==4||test==5||test==19)f.sun.w=0;
  if(test==6||test==7||test==20)f.optics.x=0;
  if(test==10||test==11)f.quality.y=1;
  if(test==17||test==21)f.waves.w=0;
  uint8_t *pixels=malloc(w*h*4);float *depthValues=malloc(w*h*4);
  for(int y=0;y<h;y++)for(int x=0;x<w;x++){int i=y*w+x;pixels[i*4]=(x*13+y*3)&255;pixels[i*4+1]=(x/16+y/8)&255;pixels[i*4+2]=128+(x%128);pixels[i*4+3]=255;simd_float4 q=simd_mul(f.inverse,(simd_float4){2*(x+.5f)/w-1,2*(y+.5f)/h-1,.5,1});simd_float3 ray=simd_make_float3(q)/q.w;float floor=-(2+((x/80+y/80)%3)*.4f);float t=ray.y<-.001f?floor/ray.y:1e10f;t=fminf(t,20/fmaxf(.001,-ray.z));simd_float3 p=ray*t;simd_float4 c=simd_mul(f.view,(simd_float4){p.x,p.y,p.z,1});depthValues[i]=c.z/c.w;}
  MTLTextureDescriptor *td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:w height:h mipmapped:NO];td.storageMode=MTLStorageModeShared;td.usage=MTLTextureUsageShaderRead;id<MTLTexture> scene=[device newTextureWithDescriptor:td];[scene replaceRegion:MTLRegionMake2D(0,0,w,h) mipmapLevel:0 withBytes:pixels bytesPerRow:w*4];td.pixelFormat=MTLPixelFormatDepth32Float;id<MTLTexture> depth=[device newTextureWithDescriptor:td];[depth replaceRegion:MTLRegionMake2D(0,0,w,h) mipmapLevel:0 withBytes:depthValues bytesPerRow:w*4];free(pixels);free(depthValues);
  MTLPixelFormat format=hdr?MTLPixelFormatRGBA16Float:MTLPixelFormatRGBA8Unorm;td.pixelFormat=format;td.usage=MTLTextureUsageRenderTarget;id<MTLTexture> out[2]={[device newTextureWithDescriptor:td],[device newTextureWithDescriptor:td]};
  id<MTLRenderPipelineState> pso[2]={pipeline(device,lib,surface?@"baseline_surface_fs":@"baseline_water_post_fs",format),pipeline(device,lib,surface?@"optimization_surface_fs":@"own_water_post_fs",format)};
  double timings[2][32];int n=perf?32:1;for(int iteration=perf?-6:0;iteration<n;iteration++)for(int pair=0;pair<2;pair++){@autoreleasepool{int k=(iteration&1)?pair:1-pair;id<MTLCommandBuffer> cb=draw(queue,pso[k],out[k],scene,depth,&f);[cb waitUntilCompleted];require(cb.status==MTLCommandBufferStatusCompleted,cb.error.localizedDescription);if(iteration>=0)timings[k][iteration]=(cb.GPUEndTime-cb.GPUStartTime)*1000;}}
  size_t bytes=w*h*(hdr?8:4);void *a=malloc(bytes),*b=malloc(bytes);[out[0] getBytes:a bytesPerRow:w*(hdr?8:4) fromRegion:MTLRegionMake2D(0,0,w,h) mipmapLevel:0];[out[1] getBytes:b bytesPerRow:w*(hdr?8:4) fromRegion:MTLRegionMake2D(0,0,w,h) mipmapLevel:0];float maximum=0;long diff=0;
  for(int i=0;i<w*h*4;i++){float av=hdr?((__fp16 *)a)[i]:((uint8_t *)a)[i]/255.f,bv=hdr?((__fp16 *)b)[i]:((uint8_t *)b)[i]/255.f;require(isfinite(av)&&isfinite(bv),@"Nonfinite color");float d=fabsf(av-bv);maximum=fmaxf(maximum,d);diff+=d>0;}
  compared+=(long)w*h*4;changed+=diff;maxDifference=fmaxf(maxDifference,maximum);printf("case=%d kind=%s inside=%d hdr=%d max_difference=%.9f changed_channels=%ld reference_ms=%.6f optimized_ms=%.6f\n",test,surface?"water":"underwater",inside,hdr,maximum,diff,median(timings[0],n),median(timings[1],n));fflush(stdout);require(maximum<=1.f/255+1e-6,@"Water output changed beyond one 8-bit quantization step");free(a);free(b);for(int k=0;k<2;k++){[out[k] release];[pso[k] release];}[scene release];[depth release];
 }}
 printf("PASS: %ld color channels compared, %ld changed, max float color difference %.9f; full quality and unchanged sample counts\n",compared,changed,maxDifference);
}return 0;}
