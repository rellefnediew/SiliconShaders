// Synthetic two-axis lightmap: native sun must replace baked sky occlusion while retaining block light.
#include "sun_test_common.h"
extern void mco_sun_debug_stats(void *,uint64_t *);
extern int mco_sun_finish(void *,Enc *,char *,int);

int main(int argc,const char **argv) {
    @autoreleasepool {
        require(argc==3 || argc==4,@"Usage: sun_check TERRAIN.metal SUN.metal [compact] [smooth|temporal]");
        BOOL compact=NO,smooth=NO,temporal=NO;
        for(int i=3;i<argc;i++) { if(!strcmp(argv[i],"compact"))compact=YES;else if(!strcmp(argv[i],"smooth"))smooth=YES;else if(!strcmp(argv[i],"temporal"))temporal=YES;else require(NO,@"Unknown test mode"); }
        NSError *issue=nil;
        NSString *source=[NSString stringWithContentsOfFile:[NSString stringWithUTF8String:argv[1]] encoding:NSUTF8StringEncoding error:&issue];
        NSString *sunSource=[NSString stringWithContentsOfFile:[NSString stringWithUTF8String:argv[2]] encoding:NSUTF8StringEncoding error:&issue];
        require(source&&sunSource,@"Shader sources missing");source=[source stringByAppendingString:sunSource];
        if(compact) source=[@"#define OWN_QREC 1\n#define OWN_EXACT_POS 1\n" stringByAppendingString:source];
        char error[16384]={0},name[256]={0};Ctx *ctx=mc_create(name,sizeof name,error,sizeof error);require(ctx!=NULL,[NSString stringWithUTF8String:error]);
        void *own=mco_new(ctx,source.UTF8String,compact,0,0,error,sizeof error);require(own!=NULL,[NSString stringWithUTF8String:error]);
        Enc *enc=mc_enc_new(ctx);
        Vertex vertices[8]={0};
        for(int q=0;q<2;q++) for(int c=0;c<4;c++) {
            Vertex *v=&vertices[q*4+c];float radius=q?0.4:4;
            v->p[0]=(c==1||c==2)?radius:-radius;v->p[1]=q?2:0;v->p[2]=((c>=2)?radius:-radius)+(q?1.5:0);
            memset(v->color,255,4);v->light[0]=v->light[1]=240;v->uv[0]=v->uv[1]=0.5;
        }
        Section section={.count=2};Record records[2]={{0},{.start=1}};
        uint32_t lists[2]={0,1},args[5]={384,2,0,0,0};
        uint32_t packed[20]={0};
        for(int q=0;q<2;q++) {
            uint32_t *w=packed+q*10;
            int lo=(int)lroundf(((q?-0.4f:-4.0f)+8)*2048),hi=(int)lroundf(((q?0.4f:4.0f)+8)*2048),y=(q?10:8)*2048;
            int zlo=(int)lroundf(((q?1.1f:-4.0f)+8)*2048),zhi=(int)lroundf(((q?1.9f:4.0f)+8)*2048);
            w[0]=(1u<<3)|(5u<<6)|(4u<<9);w[1]=lo|(hi<<16);w[2]=y|(y<<16);w[3]=zlo|(zhi<<16);
            w[4]=w[5]=0x80008000;w[6]=w[7]=0xffffffff;w[8]=w[9]=0xf0f0f0f0;
        }
        id<MTLBuffer> arena=buffer(ctx,compact?(const void *)packed:(const void *)vertices,compact?sizeof packed:sizeof vertices),sections=buffer(ctx,&section,sizeof section),recs=buffer(ctx,records,sizeof records);
        id<MTLBuffer> list=buffer(ctx,lists,sizeof lists),draw=buffer(ctx,args,sizeof args);
        simd_float4x4 projection={.columns={{0.2,0,0,0},{0,0,0.1,0},{0,0.2,0,0},{0,0,1.2,1}}};
        uint8_t globals[48]={0},terrain[80]={0};int camera[3]={0,8,0};memcpy(globals,camera,12);
        simd_float4x4 identity=matrix_identity_float4x4;memcpy(terrain,&identity,64);int textureSize[2]={4,4};memcpy(terrain+64,textureSize,8);
        float fog[10]={0,0,0,0,1000,2000,1000,2000,1000,1000};
        id<MTLBuffer> p=buffer(ctx,&projection,64),g=buffer(ctx,globals,sizeof globals),t=buffer(ctx,terrain,sizeof terrain),f=buffer(ctx,fog,sizeof fog);
        int64_t uniforms[8]={(int64_t)p,0,(int64_t)g,0,(int64_t)t,0,(int64_t)f,0};
        id<MTLTexture> atlas=white(ctx);
        MTLTextureDescriptor *lightDesc=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:16 height:16 mipmapped:NO];
        lightDesc.storageMode=MTLStorageModeShared;lightDesc.usage=MTLTextureUsageShaderRead;
        id<MTLTexture> light=[ctx->device newTextureWithDescriptor:lightDesc];
        uint8_t lightPixels[16*16*4];
        for(int y=0;y<16;y++)for(int x=0;x<16;x++) {
            int i=(y*16+x)*4;lightPixels[i]=80+x*8;lightPixels[i+1]=40+y*12;lightPixels[i+2]=180;lightPixels[i+3]=255;
        }
        [light replaceRegion:MTLRegionMake2D(0,0,16,16) mipmapLevel:0 withBytes:lightPixels bytesPerRow:64];
        MTLSamplerDescriptor *sd=[[[MTLSamplerDescriptor alloc] init] autorelease];sd.minFilter=sd.magFilter=MTLSamplerMinMagFilterNearest;
        id<MTLSamplerState> sampler=[ctx->device newSamplerStateWithDescriptor:sd];
        Sun parameters={.clip={.columns={{.16,0,-.03,0},{-.12,0,-.04,0},{0,.2,0,0},{-.96,0,.18,1}}},
            .cam={0,8,0,0},.sun={.6,.8,0,1},.config={5,1.0/512,0.00015,1},.sections=1,.resolution=512,.on=1};
        int samples[10][2]={0};
        // 0..3 sunlight only; 4..7 real shadow; 8..9 vanilla control.
        for(int test=0;test<10;test++) @autoreleasepool {
            BOOL lowSky=test%2==1,blockLight=(test%4)>=2;
            for(int v=0;v<8;v++) { vertices[v].light[0]=blockLight?240:0;vertices[v].light[1]=lowSky?64:240; }
            uint32_t packedLight=(blockLight?240:0)|((lowSky?64:240)<<8);
            for(int q=0;q<2;q++)packed[q*10+8]=packed[q*10+9]=packedLight|(packedLight<<16);
            memcpy(arena.contents,compact?(const void *)packed:(const void *)vertices,compact?sizeof packed:sizeof vertices);
            parameters.on=test<8;parameters.resolution=test>=4&&test<8?512:0;parameters.config.w=parameters.resolution>0?1:0;
            MTLTextureDescriptor *td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:128 height:128 mipmapped:NO];
            td.storageMode=MTLStorageModeShared;td.usage=MTLTextureUsageRenderTarget;id<MTLTexture> color=[ctx->device newTextureWithDescriptor:td];
            td.pixelFormat=MTLPixelFormatDepth32Float;td.storageMode=MTLStorageModePrivate;id<MTLTexture> depth=[ctx->device newTextureWithDescriptor:td];
            id<MTLTexture> targets[]={color};float clear[]={0,0,0,1};mc_render_begin(enc,1,targets,clear,depth,1,0,128,128);
            require(mco_sun_frame(own,enc,&parameters,sizeof parameters,arena,sections,recs,atlas,sampler,error,sizeof error)>=0,[NSString stringWithUTF8String:error]);
            uint8_t drawFrame[80]={0};*(int32_t *)(drawFrame+4)=1;
            require(mco_draw(own,enc,0,drawFrame,sizeof drawFrame,arena,sections,recs,list,draw,0,uniforms,atlas,sampler,light,sampler,nil,error,sizeof error)>0,[NSString stringWithUTF8String:error]);
            id<MTLCommandBuffer> command=mc_enc_commit(enc);[command waitUntilCompleted];require(command.status!=MTLCommandBufferStatusError,command.error.localizedDescription);[command release];
            uint8_t pixels[128*128*4];[color getBytes:pixels bytesPerRow:128*4 fromRegion:MTLRegionMake2D(0,0,128,128) mipmapLevel:0];
            samples[test][0]=pixels[(83*128+45)*4];samples[test][1]=pixels[(83*128+45)*4+1];
            printf("lighting=%d block=%d sky=%d rgb=%d,%d,%d\n",test,blockLight,lowSky?64:240,samples[test][0],samples[test][1],pixels[(83*128+45)*4+2]);
            if(test<8 && lowSky)for(int c=0;c<2;c++)require(abs(samples[test][c]-samples[test-1][c])<=1,@"Baked skylight still changes native sunlight/shadow");
            [color release];[depth release];
        }
        require(samples[2][0]>samples[0][0]+70,@"Block light lost its separate coordinate");
        require(samples[4][1]<samples[0][1]-70,@"Removing vanilla skylight also removed real cast shadows");
        require(samples[8][1]>samples[9][1]+100,@"Disabled shader did not restore vanilla skylight");
        printf("PASS: no baked skylight shadow; real cast shadows, block light and disabled vanilla control (%s)\n",compact?"compact":"raw");
    }
    return 0;
}
