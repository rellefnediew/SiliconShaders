// Reused entity geometry and colored glass must cast actual directional shadows, not screen-space blobs.
#include "sun_test_common.h"
#include "mcsun_surface.h"
extern int mco_sun_surface(void *,const void *);
extern int mco_sun_debug_deferred(void *);
extern int mco_sun_culling(void *,const void *);
extern int mco_sun_casters(void *,const void *,int);
typedef struct { uint64_t vertices,indices,texture,sampler;simd_float4x4 model;simd_uint4 layout;simd_int4 draw;simd_float4 config,lo,hi; } Caster;
_Static_assert(sizeof(Caster)==176,"Caster ABI");
extern int mco_sun_finish(void *,Enc *,char *,int);

int main(int argc,const char **argv) {
    @autoreleasepool {
        require(argc>=3 && argc<=7,@"Usage: sun_check TERRAIN.metal SUN.metal [compact] [smooth|temporal]");
        BOOL compact=NO,smooth=NO,temporal=YES,cascade=YES,farOnly=NO,middleOnly=NO,culling=YES;int levels=2;
        for(int i=3;i<argc;i++) { if(!strcmp(argv[i],"compact"))compact=YES;else if(!strcmp(argv[i],"smooth"))smooth=YES;else if(!strcmp(argv[i],"temporal"))temporal=YES;else if(!strcmp(argv[i],"cascade"))cascade=YES;else if(!strcmp(argv[i],"cascade-far")){cascade=YES;farOnly=YES;}else if(!strcmp(argv[i],"levels3")){cascade=YES;levels=3;}else if(!strcmp(argv[i],"levels4")){cascade=YES;levels=4;}else if(!strcmp(argv[i],"cascade-middle")){cascade=YES;middleOnly=YES;}else if(!strcmp(argv[i],"culling"))culling=YES;else require(NO,@"Unknown test mode"); }
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
        id<MTLTexture> atlas=white(ctx),light=white(ctx);
        MTLSamplerDescriptor *sd=[[[MTLSamplerDescriptor alloc] init] autorelease];sd.minFilter=sd.magFilter=MTLSamplerMinMagFilterNearest;
        id<MTLSamplerState> sampler=[ctx->device newSamplerStateWithDescriptor:sd];
        Sun parameters={.clip={.columns={{.16,0,-.03,0},{-.12,0,-.04,0},{0,.2,0,0},{-.96,0,.18,1}}},
            .cam={0,8,0,0},.sun={.6,.8,0,1},.config={5,1.0/512,0.00015,1},.sections=1,.resolution=512,.on=1};
        records[1].meta=2u<<9;memcpy(recs.contents,records,sizeof records);
        uint16_t actorIndices[]={0,1,2,2,3,0};
        id<MTLBuffer> actorVertices=buffer(ctx,vertices+4,4*sizeof(Vertex)),actorIndex=buffer(ctx,actorIndices,sizeof actorIndices);
        int output[10][3]={0};
        for(int iteration=0;iteration<(testProfileOn()?80:10);iteration++) {
            int test=iteration%10;
            testProfileBegin(enc);
            BOOL glass=test>=3&&test<=6||test==8||(culling&&test==9);
            uint8_t rgba[64];
            for(int pixel=0;pixel<16;pixel++) {
                rgba[pixel*4]=test==4||test==6?0:255;rgba[pixel*4+1]=glass?0:255;rgba[pixel*4+2]=test==3||test==6||test==8?0:255;
                rgba[pixel*4+3]=test==2||test==5?0:glass&&test!=6?128:255;
            }
            [atlas replaceRegion:MTLRegionMake2D(0,0,4,4) mipmapLevel:0 withBytes:rgba bytesPerRow:16];
            // Terrain albedo must stay white; only the caster reads the colored actor texture.
            id<MTLTexture> terrainAtlas=white(ctx);
            Caster caster={.vertices=(uint64_t)actorVertices,.indices=(uint64_t)actorIndex,.texture=(uint64_t)atlas,.sampler=(uint64_t)sampler,
                .model=matrix_identity_float4x4,.layout={sizeof(Vertex),0,16,12},.draw={glass?4:6,0,0,0},.config={glass?1:0,glass?1:0,test==7?2:64,0},
                .lo={-1,-7,1,0},.hi={1,-5,2,0}};
            caster.model.columns[3].y=test==8?-12:-8;
            struct { uint32_t count;float nearRadius;uint32_t pad[2];simd_float4 planes[16]; } cv={.count=culling?1:0,.planes={{0,0,1,test==9?-10:0}}};
            require(mco_sun_culling(own,&cv)==0,@"Caster culling packet failed");
            require(mco_sun_casters(own,&caster,test==0||(test==9&&!culling)?0:1)>=0,@"Caster upload failed");
            MTLTextureDescriptor *td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:128 height:128 mipmapped:NO];
            td.storageMode=MTLStorageModeShared;td.usage=MTLTextureUsageRenderTarget;id<MTLTexture> color=[ctx->device newTextureWithDescriptor:td];
            td.pixelFormat=MTLPixelFormatDepth32Float;td.storageMode=MTLStorageModePrivate;id<MTLTexture> depth=[ctx->device newTextureWithDescriptor:td];
            id<MTLTexture> targets[]={color};float clear[]={0,0,0,1};mc_render_begin(enc,1,targets,clear,depth,1,0,128,128);
            uint8_t packet[688]={0};memcpy(packet,&parameters,144);
            if(temporal) {
                memcpy(packet+144,&projection,64);double cameraPosition[]={0,8,0};memcpy(packet+208,cameraPosition,24);
                *(uint64_t *)(packet+232)=test+1;*(float *)(packet+240)=1.0f/120;*(uint32_t *)(packet+244)=test;
            }
            if(cascade) {
                for(int i=1;i<levels;i++) {
                    Sun sample=parameters;float ratio=powf(64.0f/24,(float)i/(levels-1));
                    for(int c=0;c<4;c++){sample.clip.columns[c].x/=ratio;sample.clip.columns[c].y/=ratio;}
                    sample.config.x*=ratio;
                    if(levels>2){sample.resolution=parameters.resolution?MAX(256,parameters.resolution>>i):0;sample.config.y=1.0/MAX(1,sample.resolution);}
                    memcpy(packet+256+(i-1)*144,&sample,144);
                }
                *(uint32_t *)(packet+248)=temporal;*(uint32_t *)(packet+252)=levels;
                if(farOnly||middleOnly)for(int i=0;i<levels;i++)if(i!=(middleOnly?1:levels-1))((Sun *)(packet+(i?256+(i-1)*144:0)))->clip.columns[3].x+=3;

            }
            OwnSunSurfaceView surface={.clip=projection,.camera={0,8,0},.enabled=1};
            require(mco_sun_surface(own,&surface)==0,@"Receiver metadata rejected");
            require(mco_sun_frame(own,enc,packet,cascade?(levels==2?400:688):temporal?256:144,arena,sections,recs,terrainAtlas,sampler,error,sizeof error)>=0,[NSString stringWithUTF8String:error]);
            require(mco_sun_debug_deferred(own),@"Receiver culling did not activate for actor/glass scene");
            uint8_t drawFrame[80]={0};*(int32_t *)(drawFrame+4)=1;
            require(mco_draw(own,enc,0,drawFrame,sizeof drawFrame,arena,sections,recs,list,draw,0,uniforms,terrainAtlas,sampler,light,sampler,nil,error,sizeof error)>0,[NSString stringWithUTF8String:error]);
            if(temporal)require(mco_sun_finish(own,enc,error,sizeof error)>=0,[NSString stringWithUTF8String:error]);
            id<MTLCommandBuffer> command=testProfileCommit(enc,ctx);[command waitUntilCompleted];require(command.status!=MTLCommandBufferStatusError,command.error.localizedDescription);[command release];
            uint8_t pixels[128*128*4];[color getBytes:pixels bytesPerRow:128*4 fromRegion:MTLRegionMake2D(0,0,128,128) mipmapLevel:0];
            for(int c=0;c<3;c++)output[test][c]=pixels[(83*128+45)*4+c];
            printf("caster=%d rgb=%d,%d,%d\n",test,output[test][0],output[test][1],output[test][2]);
            [color release];[depth release];[terrainAtlas release];
        }
        require(output[0][0]>230&&output[1][0]<150,@"Reused indexed entity mesh did not cast a directional shadow");
        require(output[2][0]>230,@"Entity alpha holes cast a solid shadow");
        require(output[3][0]>230&&output[3][1]>150&&output[3][1]<210&&output[3][2]<210,@"Red glass did not transmit red light");
        require(output[4][2]>230&&output[4][0]<210&&output[4][1]<210,@"Blue glass did not transmit blue light");
        require(output[5][0]>230&&output[6][0]<150,@"Clear/opaque glass transmission incorrect");
        require(output[7][0]>230,@"Distant entity ignored shadow distance culling");
        require(output[8][0]>230&&output[8][1]>230,@"Glass behind receiver tinted the foreground");
        require(output[9][0]>230&&output[9][1]>230,@"Removing casters left stale glass/entity shadows");
        printf("PASS current-frame receivers: indexed entity shadows, alpha holes, distance culling, red/blue glass transmission, clear/opaque glass, receiver depth and removal\n");
    }
    return 0;
}
