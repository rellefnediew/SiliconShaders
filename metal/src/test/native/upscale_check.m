// Render resolution: the actual MetalFX path of mco_upscale on offscreen targets, under Metal API validation.
#include "sun_test_common.h"
extern int mco_upscale(void *,Enc *,id<MTLTexture>,id<MTLTexture>,char *,int);
extern int mco_upscale_supported(void *);
extern void mco_upscale_reset(void *);
extern void mco_upscale_debug_stats(void *,uint64_t *);
static id<MTLTexture> texture(Ctx *ctx,MTLPixelFormat format,int width,int height,MTLStorageMode storage,MTLTextureUsage usage) {
    MTLTextureDescriptor *td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:format width:width height:height mipmapped:NO];
    td.storageMode=storage;td.usage=usage;return [ctx->device newTextureWithDescriptor:td];
}
// A smooth gradient with a hard-edged block: bilinear and MetalFX agree on the gradient, MetalFX keeps the edge crisper.
static uint8_t pattern(int x,int y,int width,int height,int channel) {
    BOOL block=x>width/3&&x<width*2/3&&y>height/3&&y<height*2/3;
    if(channel==3)return 255;
    if(block)return channel==0?230:channel==1?40:60;
    return (uint8_t)(channel==0?x*255/(width-1):channel==1?y*255/(height-1):128);
}
int main(int argc,const char **argv){@autoreleasepool{
    require(argc==3,@"Usage: upscale_check TERRAIN SUN");
    NSString *source=[[NSString stringWithContentsOfFile:@(argv[1]) encoding:NSUTF8StringEncoding error:nil] stringByAppendingString:[NSString stringWithContentsOfFile:@(argv[2]) encoding:NSUTF8StringEncoding error:nil]];
    char error[16384]={0},name[256]={0};Ctx *ctx=mc_create(name,sizeof name,error,sizeof error);require(ctx!=NULL,@(error));
    void *own=mco_new(ctx,source.UTF8String,0,0,0,error,sizeof error);require(own!=NULL,@(error));Enc *enc=mc_enc_new(ctx);
    require(mco_upscale_supported(own),@"MetalFX spatial scaling unsupported on this Mac");
    MTLPixelFormat formats[]={MTLPixelFormatRGBA8Unorm,MTLPixelFormatBGRA8Unorm,MTLPixelFormatRGBA8Unorm_sRGB};
    int sizes[][4]={{160,100,320,200},{160,100,240,150},{97,61,194,122}};int checked=0;uint64_t stats[2]={0};
    for(int f=0;f<3;f++)for(int s=0;s<3;s++)for(int direct=0;direct<2;direct++){@autoreleasepool{
        int iw=sizes[s][0],ih=sizes[s][1],ow=sizes[s][2],oh=sizes[s][3];MTLPixelFormat format=formats[f];
        // The world target as the game creates it: private, render target and shader read.
        id<MTLTexture> world=texture(ctx,format,iw,ih,MTLStorageModePrivate,MTLTextureUsageRenderTarget|MTLTextureUsageShaderRead);
        id<MTLTexture> staging=texture(ctx,format,iw,ih,MTLStorageModeShared,MTLTextureUsageShaderRead);
        uint8_t *pixels=malloc(iw*ih*4);BOOL bgra=format==MTLPixelFormatBGRA8Unorm;
        for(int y=0;y<ih;y++)for(int x=0;x<iw;x++)for(int c=0;c<4;c++)pixels[(y*iw+x)*4+(bgra&&c<3?2-c:c)]=pattern(x,y,iw,ih,c);
        [staging replaceRegion:MTLRegionMake2D(0,0,iw,ih) mipmapLevel:0 withBytes:pixels bytesPerRow:iw*4];free(pixels);
        // Direct: the main target carries the usage MetalFX writes with; otherwise the copied result path is taken.
        MTLTextureUsage mainUsage=direct?MTLTextureUsageRenderTarget|MTLTextureUsageShaderRead|MTLTextureUsageShaderWrite:MTLTextureUsageRenderTarget;
        id<MTLTexture> main=texture(ctx,format,ow,oh,MTLStorageModePrivate,mainUsage),depth=texture(ctx,MTLPixelFormatDepth32Float,iw,ih,MTLStorageModePrivate,MTLTextureUsageRenderTarget);
        id<MTLCommandBuffer> command=mc_frame_cmd(enc);id<MTLBlitCommandEncoder> b=[command blitCommandEncoder];
        [b copyFromTexture:staging sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0,0,0) sourceSize:MTLSizeMake(iw,ih,1) toTexture:world destinationSlice:0 destinationLevel:0 destinationOrigin:MTLOriginMake(0,0,0)];[b endEncoding];
        // The world pass is open when the hook runs; it must be open again afterwards.
        id<MTLTexture> targets[]={world};float load[5]={0};mc_render_begin(enc,1,targets,load,depth,0,0,iw,ih);
        int result=mco_upscale(own,enc,world,main,error,sizeof error);require(result==1,[NSString stringWithFormat:@"upscale %dx%d -> %dx%d failed: %s",iw,ih,ow,oh,error]);
        require(enc->render!=nil&&enc->colors[0]==world&&enc->depth==depth,@"The world pass was not reopened after upscaling");
        id<MTLTexture> readback=texture(ctx,format,ow,oh,MTLStorageModeShared,MTLTextureUsageShaderRead);
        command=mc_frame_cmd(enc);b=[command blitCommandEncoder];
        [b copyFromTexture:main sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0,0,0) sourceSize:MTLSizeMake(ow,oh,1) toTexture:readback destinationSlice:0 destinationLevel:0 destinationOrigin:MTLOriginMake(0,0,0)];[b endEncoding];
        command=mc_enc_commit(enc);[command waitUntilCompleted];require(command.status==MTLCommandBufferStatusCompleted,command.error.localizedDescription);
        uint8_t *out=malloc(ow*oh*4);[readback getBytes:out bytesPerRow:ow*4 fromRegion:MTLRegionMake2D(0,0,ow,oh) mipmapLevel:0];
        // Against the source pattern at the matching position: small error on the gradient, the block clearly present.
        double error=0;long samples=0;int blockHits=0,blockPixels=0;
        for(int y=2;y<oh-2;y++)for(int x=2;x<ow-2;x++){
            int sx=(int)((x+.5)*iw/ow),sy=(int)((y+.5)*ih/oh);BOOL block=sx>iw/3&&sx<iw*2/3&&sy>ih/3&&sy<ih*2/3;
            BOOL nearEdge=abs(sx-iw/3)<3||abs(sx-iw*2/3)<3||abs(sy-ih/3)<3||abs(sy-ih*2/3)<3;
            int r=out[(y*ow+x)*4+(bgra?2:0)],g=out[(y*ow+x)*4+1];
            if(block&&!nearEdge){blockPixels++;blockHits+=r>180&&g<100;}
            if(!block&&!nearEdge){error+=abs(r-pattern(sx,sy,iw,ih,0))+abs(g-pattern(sx,sy,iw,ih,1));samples+=2;}
        }
        double mean=error/MAX(samples,1);
        printf("%s %dx%d -> %dx%d %s: gradient mean error %.2f, block %d/%d\n",format==MTLPixelFormatRGBA8Unorm?"RGBA8":bgra?"BGRA8":"sRGB",iw,ih,ow,oh,direct?"direct":"copied",mean,blockHits,blockPixels);
        require(mean<(format==MTLPixelFormatRGBA8Unorm_sRGB?10:6),@"Upscaled gradient differs from the source");
        require(blockHits*100>=blockPixels*98,@"Upscaled hard-edged block lost");
        free(out);checked++;
    }}
    mco_upscale_debug_stats(own,stats);require(stats[0]==(uint64_t)checked,@"Upscale count mismatch");require(stats[1]>0,@"Copied result path never exercised");
    mco_upscale_reset(own);mco_upscale_debug_stats(own,stats);require(stats[0]==0,@"Reset kept the scaler");
    printf("PASS: %d MetalFX render-scale cases, RGBA8/BGRA8/sRGB, 2x/1.5x/odd sizes, direct and copied results, world pass reopened, reset\n",checked);
}return 0;}
