// Exercise the same C entry points and siliconshaders command-buffer lifecycle used by ShaderRuntime.
#import <Foundation/Foundation.h>
#import "mcmetal.h"
#include <math.h>

extern Ctx *mc_create(char *,int,char *,int);
extern Enc *mc_enc_new(Ctx *);
extern id<MTLCommandBuffer> mc_enc_commit(Enc *);
extern void *mc_pack_new(Ctx *,const char *,int,char *,int);
extern int mc_pack_draw(Enc *,void *,id<MTLTexture>,int,int,int,float,char *,int);
extern void mc_pack_release(void *);

static void require(BOOL condition,NSString *message) { if(!condition) { fprintf(stderr,"FAIL: %s\n",message.UTF8String); exit(1); } }

static int checkFrame(Ctx *ctx,Enc *enc,void *pack,int width,int height,MTLPixelFormat format,BOOL diagnostic) {
    MTLTextureDescriptor *desc=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:format width:width height:height mipmapped:NO];
    desc.storageMode=MTLStorageModeShared; desc.usage=MTLTextureUsageShaderRead|MTLTextureUsageRenderTarget;
    id<MTLTexture> target=[ctx->device newTextureWithDescriptor:desc];
    require(target!=nil,@"Cannot allocate runtime test target");
    NSMutableData *pixels=[NSMutableData dataWithLength:width*height*4]; unsigned char *bytes=pixels.mutableBytes;
    const BOOL bgra=format==MTLPixelFormatBGRA8Unorm;
    for(int i=0;i<width*height;i++) { bytes[i*4]=bgra?153:51; bytes[i*4+1]=102; bytes[i*4+2]=bgra?51:153; bytes[i*4+3]=255; }
    [target replaceRegion:MTLRegionMake2D(0,0,width,height) mipmapLevel:0 withBytes:bytes bytesPerRow:width*4];
    char error[4096]={0};
    require(mc_pack_draw(enc,pack,target,width,height,0,0.0f,error,sizeof(error)),[NSString stringWithUTF8String:error]);
    id<MTLCommandBuffer> command=mc_enc_commit(enc);
    [command waitUntilCompleted]; require(command.status!=MTLCommandBufferStatusError,command.error.localizedDescription);
    [command release];
    [target getBytes:bytes bytesPerRow:width*4 fromRegion:MTLRegionMake2D(0,0,width,height) mipmapLevel:0];
    int maxError=0;
    for(int y=0;y<height;y++) for(int x=0;x<width;x++) {
        float u=(x+0.5f)/width-0.5f,v=(y+0.5f)/height-0.5f;
        float lum=(51.0f*.299f+102.0f*.587f+153.0f*.114f)/255.0f;
        float factor=diagnostic?1.0f:1.0f-(u*u+v*v)*(1.0f-lum);
        float gains[]={1.12f,.94f,.80f}; int channels[]={51,102,153};
        for(int c=0;c<3;c++) {
            int expected=(int)lroundf(channels[c]*factor*(diagnostic?gains[c]:1.0f));
            int channel=bgra && c!=1?2-c:c;
            maxError=MAX(maxError,abs((int)bytes[(y*width+x)*4+channel]-expected));
        }
        require(bytes[(y*width+x)*4+3]==255,@"Invalid output alpha");
    }
    require(maxError<=(diagnostic?1:3),[NSString stringWithFormat:@"Runtime pixel mismatch %d",maxError]);
    [target release]; return maxError;
}

int main(int argc,const char *argv[]) {
    @autoreleasepool {
        require(argc==3,@"Usage: runtime_check PROGRAM.json diagnostic|complementary");
        NSString *path=[NSString stringWithUTF8String:argv[1]],*json=[NSString stringWithContentsOfFile:path encoding:NSUTF8StringEncoding error:nil];
        require(json!=nil,@"Program manifest missing");
        BOOL diagnostic=strcmp(argv[2],"diagnostic")==0;
        char name[256]={0},error[4096]={0}; Ctx *ctx=mc_create(name,sizeof(name),error,sizeof(error));
        require(ctx!=NULL,[NSString stringWithUTF8String:error]); Enc *enc=mc_enc_new(ctx);
        void *pack=mc_pack_new(ctx,json.UTF8String,MTLPixelFormatRGBA8Unorm,error,sizeof(error));
        require(pack!=NULL,[NSString stringWithUTF8String:error]);
        int sizes[][2]={{64,64},{96,48},{128,72},{64,64},{64,64}}; int maximum=0;
        for(int f=0;f<2;f++) for(int i=0;i<5;i++) maximum=MAX(maximum,checkFrame(ctx,enc,pack,sizes[i][0],sizes[i][1],f?MTLPixelFormatBGRA8Unorm:MTLPixelFormatRGBA8Unorm,diagnostic));
        NSMutableDictionary *invalid=[NSJSONSerialization JSONObjectWithData:[json dataUsingEncoding:NSUTF8StringEncoding] options:NSJSONReadingMutableContainers error:nil];
        invalid[@"vertex"][@"metal"]=@"invalid metal source";
        NSData *invalidJson=[NSJSONSerialization dataWithJSONObject:invalid options:0 error:nil];
        NSString *invalidText=[[[NSString alloc] initWithData:invalidJson encoding:NSUTF8StringEncoding] autorelease];
        void *bad=mc_pack_new(ctx,invalidText.UTF8String,MTLPixelFormatRGBA8Unorm,error,sizeof(error));
        require(bad==NULL && strlen(error)>0,@"Invalid shader was not rejected");
        checkFrame(ctx,enc,pack,64,64,MTLPixelFormatRGBA8Unorm,diagnostic);
        mc_pack_release(pack);
        printf("PASS: %s runtime on %s; 10 frames; RGBA/BGRA; resize/reuse; invalid shader recovery; max channel error=%d/255\n",diagnostic?"diagnostic":"Complementary final",name,maximum);
    }
    return 0;
}
