// The half-size underwater fog and caustics against the original full-size pass, on the production shaders: a ground plane
// with a pillar in front of it, so depth edges exist. Prints the image difference and the GPU cost; no Minecraft window.
// Usage: underwater_half_check TERRAIN SUN [OUTPUT_DIR]
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#import <ImageIO/ImageIO.h>
#import <simd/simd.h>
typedef struct { simd_float4x4 view,inverse;simd_float4 sun,fog,state,camera,screen,controls,waves,optics,effects,quality; } Water;
_Static_assert(sizeof(Water)==288,"Water frame ABI");
// Measured 0.0013 and 0.0025 on this fixture; without the depth check at the edges 0.0075.
#define UNDER_MAX_MEAN .004
#define UNDER_MAX_EDGE .005
static void require(BOOL ok,NSString *message){if(!ok){fprintf(stderr,"FAIL: %s\n",message.UTF8String);exit(1);}}
static id<MTLDevice> device;static id<MTLLibrary> library;static id<MTLCommandQueue> queue;
static id<MTLRenderPipelineState> pipeline(NSString *name,MTLPixelFormat first,MTLPixelFormat second) {
    NSError *e=nil;MTLRenderPipelineDescriptor *pd=[MTLRenderPipelineDescriptor new];
    pd.vertexFunction=[library newFunctionWithName:@"sun_screen_vs"];pd.fragmentFunction=[library newFunctionWithName:name];
    require(pd.fragmentFunction!=nil,name);
    pd.colorAttachments[0].pixelFormat=first;if(second!=MTLPixelFormatInvalid)pd.colorAttachments[1].pixelFormat=second;
    id<MTLRenderPipelineState> p=[device newRenderPipelineStateWithDescriptor:pd error:&e];require(p!=nil,e.localizedDescription);return p;
}
static id<MTLTexture> target(MTLPixelFormat format,int w,int h,BOOL shared) {
    MTLTextureDescriptor *d=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:format width:w height:h mipmapped:NO];
    d.storageMode=shared?MTLStorageModeShared:MTLStorageModePrivate;d.usage=MTLTextureUsageRenderTarget|MTLTextureUsageShaderRead;return [device newTextureWithDescriptor:d];
}
static void pass(id<MTLCommandBuffer> c,id<MTLRenderPipelineState> p,NSArray<id<MTLTexture>> *outputs,NSArray<id> *inputs,const Water *f) {
    MTLRenderPassDescriptor *rp=[MTLRenderPassDescriptor renderPassDescriptor];
    for(NSUInteger i=0;i<outputs.count;i++){rp.colorAttachments[i].texture=outputs[i];rp.colorAttachments[i].loadAction=MTLLoadActionDontCare;rp.colorAttachments[i].storeAction=MTLStoreActionStore;}
    id<MTLRenderCommandEncoder> r=[c renderCommandEncoderWithDescriptor:rp];[r setRenderPipelineState:p];[r setFragmentBytes:f length:sizeof *f atIndex:0];
    for(NSUInteger i=0;i<inputs.count;i++)if(inputs[i]!=[NSNull null])[r setFragmentTexture:inputs[i] atIndex:i];
    [r drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];[r endEncoding];
}
static void savePng(id<MTLTexture> t,NSString *path) {
    int w=(int)t.width,h=(int)t.height;NSMutableData *d=[NSMutableData dataWithLength:w*h*4];[t getBytes:d.mutableBytes bytesPerRow:w*4 fromRegion:MTLRegionMake2D(0,0,w,h) mipmapLevel:0];
    CGColorSpaceRef cs=CGColorSpaceCreateDeviceRGB();CGContextRef cx=CGBitmapContextCreate(d.mutableBytes,w,h,8,w*4,cs,(CGBitmapInfo)kCGImageAlphaNoneSkipLast);CGImageRef i=CGBitmapContextCreateImage(cx);
    CGImageDestinationRef o=CGImageDestinationCreateWithURL((__bridge CFURLRef)[NSURL fileURLWithPath:path],CFSTR("public.png"),1,NULL);CGImageDestinationAddImage(o,i,NULL);CGImageDestinationFinalize(o);
}
static int compareDouble(const void *a,const void *b){double x=*(const double *)a,y=*(const double *)b;return x<y?-1:x>y;}
int main(int argc,const char **argv){@autoreleasepool{
    require(argc>=3,@"Usage: underwater_half_check TERRAIN SUN [OUTPUT_DIR]");
    device=MTLCreateSystemDefaultDevice();queue=[device newCommandQueue];NSError *e=nil;
    NSString *source=[[NSString stringWithContentsOfFile:@(argv[1]) encoding:NSUTF8StringEncoding error:NULL] stringByAppendingString:[NSString stringWithContentsOfFile:@(argv[2]) encoding:NSUTF8StringEncoding error:NULL]];
    MTLCompileOptions *o=[MTLCompileOptions new];o.languageVersion=MTLLanguageVersion3_0;library=[device newLibraryWithSource:source options:o error:&e];require(library!=nil,e.localizedDescription);
    const int w=1920,h=1200;
    float cot=1/tanf(70*M_PI/360),pitch=32*M_PI/180;
    simd_float4x4 proj={.columns={{cot/((float)w/h),0,0,0},{0,cot,0,0},{0,0,.1f/79.9f,-1},{0,0,8.f/79.9f,0}}};
    simd_float4x4 rotation={.columns={{1,0,0,0},{0,cosf(pitch),sinf(pitch),0},{0,-sinf(pitch),cosf(pitch),0},{0,0,0,1}}};
    Water f={.view=simd_mul(proj,rotation),.sun={.6,.8,0,1},.fog={.05,.18,.25,1},.state={3170,1,1,1},.screen={w,h,64,1},.controls={1,32,0,0},.waves={1,1,1,1},.optics={1,1,64,1},.effects={1,1,1,80}};
    f.inverse=simd_inverse(f.view);
    // Reversed depth of a floor six blocks below the camera, filling most of the view, with a pillar on it nine blocks ahead.
    uint8_t *pixels=malloc(w*h*4);float *depths=malloc(w*h*4);bool *edge=calloc(w*h,1);
    for(int y=0;y<h;y++)for(int x=0;x<w;x++) {
        int i=y*w+x;
        simd_float4 q=simd_mul(f.inverse,(simd_float4){2*(x+.5f)/w-1,2*(y+.5f)/h-1,.5,1});simd_float3 ray=simd_normalize(q.xyz/q.w);
        float t=ray.y<-1e-3f?-6/ray.y:1e9f;
        float tz=-9/fminf(ray.z,-1e-3f),px=ray.x*tz;if(ray.z<0&&fabsf(px-1)<1&&ray.y*tz>-6)t=tz;
        float d=0;
        if(t<70){simd_float3 p=ray*t;simd_float4 c=simd_mul(f.view,(simd_float4){p.x,p.y,p.z,1});d=c.z/c.w;}
        depths[i]=d;
        pixels[i*4]=90+(x/8+y/8)%2*70;pixels[i*4+1]=120+(x/8+y/8)%2*60;pixels[i*4+2]=70+(x%64);pixels[i*4+3]=255;
    }
    for(int y=2;y<h-2;y++)for(int x=2;x<w-2;x++) {
        float d=depths[y*w+x];
        for(int dy=-2;dy<=2;dy++)for(int dx=-2;dx<=2;dx++)if(fabsf(depths[(y+dy)*w+x+dx]-d)>.03f*fmaxf(fmaxf(d,depths[(y+dy)*w+x+dx]),1e-6f))edge[y*w+x]=true;
    }
    MTLTextureDescriptor *td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:w height:h mipmapped:NO];td.storageMode=MTLStorageModeShared;td.usage=MTLTextureUsageShaderRead;
    id<MTLTexture> scene=[device newTextureWithDescriptor:td];[scene replaceRegion:MTLRegionMake2D(0,0,w,h) mipmapLevel:0 withBytes:pixels bytesPerRow:w*4];
    td.pixelFormat=MTLPixelFormatDepth32Float;id<MTLTexture> depth=[device newTextureWithDescriptor:td];[depth replaceRegion:MTLRegionMake2D(0,0,w,h) mipmapLevel:0 withBytes:depths bytesPerRow:w*4];
    id<MTLTexture> fullImage=target(MTLPixelFormatRGBA8Unorm,w,h,YES),halfImage=target(MTLPixelFormatRGBA8Unorm,w,h,YES);
    id<MTLTexture> lowA=target(MTLPixelFormatRGBA16Float,(w+1)/2,(h+1)/2,NO),lowB=target(MTLPixelFormatRGBA16Float,(w+1)/2,(h+1)/2,NO);
    id<MTLRenderPipelineState> full=pipeline(@"own_water_post_fs",MTLPixelFormatRGBA8Unorm,MTLPixelFormatInvalid),low=pipeline(@"own_water_low_fs",MTLPixelFormatRGBA16Float,MTLPixelFormatRGBA16Float),half=pipeline(@"own_water_post_half_fs",MTLPixelFormatRGBA8Unorm,MTLPixelFormatInvalid);
    enum { RUNS=60 };double timesFull[RUNS],timesHalf[RUNS];int counted=0;
    for(int run=-20;run<RUNS;run++)for(int k=0;k<2;k++)@autoreleasepool{
        id<MTLCommandBuffer> c=[queue commandBuffer];
        if(k==0)pass(c,full,@[fullImage],@[scene,depth],&f);
        else{pass(c,low,@[lowA,lowB],@[[NSNull null],depth],&f);pass(c,half,@[halfImage],@[scene,depth,lowA,lowB],&f);}
        [c commit];[c waitUntilCompleted];require(c.status==MTLCommandBufferStatusCompleted,c.error.localizedDescription);
        if(run>=0){(k?timesHalf:timesFull)[run]=(c.GPUEndTime-c.GPUStartTime)*1000;if(k)counted++;}
    }
    qsort(timesFull,RUNS,sizeof(double),compareDouble);qsort(timesHalf,RUNS,sizeof(double),compareDouble);
    // Caustics off, to prove the picture being compared actually has them.
    Water noCaustics=f;noCaustics.optics.w=0;id<MTLTexture> plainImage=target(MTLPixelFormatRGBA8Unorm,w,h,YES);
    {id<MTLCommandBuffer> c=[queue commandBuffer];pass(c,full,@[plainImage],@[scene,depth],&noCaustics);[c commit];[c waitUntilCompleted];}
    uint8_t *plain=malloc(w*h*4);[plainImage getBytes:plain bytesPerRow:w*4 fromRegion:MTLRegionMake2D(0,0,w,h) mipmapLevel:0];
    uint8_t *a=malloc(w*h*4),*b=malloc(w*h*4);
    [fullImage getBytes:a bytesPerRow:w*4 fromRegion:MTLRegionMake2D(0,0,w,h) mipmapLevel:0];[halfImage getBytes:b bytesPerRow:w*4 fromRegion:MTLRegionMake2D(0,0,w,h) mipmapLevel:0];
    long floorPixels=0;for(int i=0;i<w*h;i++)floorPixels+=depths[i]>0;
    double sum=0,edgeSum=0;long edges=0,visible=0,lit=0;int biggest=0,edgeBiggest=0;float variation=0;
    for(int i=0;i<w*h;i++) {
        int d=0;for(int n=0;n<3;n++)d=fmax(d,abs((int)a[i*4+n]-(int)b[i*4+n]));
        sum+=d;biggest=fmax(biggest,d);if(edge[i]){edgeSum+=d;edges++;edgeBiggest=fmax(edgeBiggest,d);}
        if(a[i*4]+a[i*4+1]+a[i*4+2]>15)visible++;if(d<=8)lit++;
    }
    double causticSum=0;long causticPixels=0;for(int i=0;i<w*h;i++){int d=abs((int)a[i*4+1]-(int)plain[i*4+1]);causticSum+=d;causticPixels+=d>4;}
    printf("caustics in the fixture: %.1f%% of pixels changed by more than 4/255, mean %.4f\n",100.0*causticPixels/(w*h),causticSum/(w*h)/255);
    double mean=sum/(w*h)/255,edgeMean=edges?edgeSum/edges/255:0,within=(double)lit/(w*h);
    for(int i=1;i<w*h;i++)variation=fmaxf(variation,abs((int)b[i*4+1]-(int)b[(i-1)*4+1])/255.f);
    printf("underwater half size: mean difference %.4f, within 8/255 %.1f%%, largest %d/255; at depth edges (%ld px) mean %.4f, largest %d/255\n",mean,within*100,biggest,edges,edgeMean,edgeBiggest);
    printf("underwater GPU, 1920x1200, median of %d warm runs: full %.3f ms, half %.3f ms (%.0f%%)\n",counted,timesFull[RUNS/2],timesHalf[RUNS/2],timesHalf[RUNS/2]/timesFull[RUNS/2]*100);
    if(argc>3){NSString *dir=@(argv[3]);[[NSFileManager defaultManager] createDirectoryAtPath:dir withIntermediateDirectories:YES attributes:nil error:NULL];savePng(fullImage,[dir stringByAppendingPathComponent:@"underwater-full.png"]);savePng(halfImage,[dir stringByAppendingPathComponent:@"underwater-half.png"]);}
    require(visible>w*h/2,@"Underwater fixture is black");
    require(floorPixels>w*h/2,@"Underwater fixture shows too little floor for caustics");
    require(edges>1000,@"Underwater fixture has no depth edges");
    require(variation>.01f,@"Underwater fixture is flat");
    require(causticPixels>w*h/50,@"Underwater fixture shows no caustics: nothing compared");
    require(mean<UNDER_MAX_MEAN,@"Half-size underwater picture differs too much");
    require(edgeMean<UNDER_MAX_EDGE,@"Half-size underwater fog bleeds across depth edges");
    printf("PASS: half-size underwater fog and caustics match the full-size pass, depth edges kept\n");
}return 0;}
