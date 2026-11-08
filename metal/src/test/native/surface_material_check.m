// Actual Metal material shaders and actual terrain metadata transport. No Minecraft window.
#include "sun_test_common.h"
#include "mcsun_surface.h"
extern int mco_sun_surface(void *,const void *);
extern void mco_sun_materials(void *,const float *,int);
extern void mco_sun_gbuffer(void *,int,int);
extern int mco_sun_finish(void *,Enc *,char *,int);
extern id<MTLTexture> mco_sun_debug_light_data(void *);
extern void mco_sun_half_shadows(void *,int);
extern void mco_sun_memory_optimization(void *,int);
typedef struct {uint32_t enabled,srgb;float exposure,saturation,contrast,moon,pad[2];simd_float4 env,p[19],hand[3],materials[4],aurora[3],grade[4],celestial[14],sky[4];} Lighting;
_Static_assert(sizeof(Lighting)==864,"Lighting ABI");
static void complete(Enc *enc){id<MTLCommandBuffer> cmd=mc_enc_commit(enc);[cmd waitUntilCompleted];require(cmd.status==MTLCommandBufferStatusCompleted,cmd.error.localizedDescription);}
static id<MTLTexture> image(Ctx *ctx,int w,int h,MTLPixelFormat format){MTLTextureDescriptor *d=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:format width:w height:h mipmapped:NO];d.storageMode=format==MTLPixelFormatDepth32Float?MTLStorageModePrivate:MTLStorageModeShared;d.usage=MTLTextureUsageShaderRead|MTLTextureUsageRenderTarget;return [ctx->device newTextureWithDescriptor:d];}
static float *readPixels(id<MTLTexture> image){int count=image.width*image.height*4;BOOL half=image.pixelFormat==MTLPixelFormatRGBA16Float;BOOL bgra=image.pixelFormat==MTLPixelFormatBGRA8Unorm||image.pixelFormat==MTLPixelFormatBGRA8Unorm_sRGB;void *raw=malloc(count*(half?2:1));[image getBytes:raw bytesPerRow:image.width*(half?8:4) fromRegion:MTLRegionMake2D(0,0,image.width,image.height) mipmapLevel:0];float *p=malloc(count*4);for(int i=0;i<count;i++){int at=bgra&&i%4==0?i+2:bgra&&i%4==2?i-2:i;p[i]=half?(float)((_Float16 *)raw)[at]:((uint8_t *)raw)[at]/255.f;require(isfinite(p[i]),@"Material shader produced NaN/Inf");}free(raw);return p;}
static Lighting lighting(void){Lighting l={.enabled=1,.exposure=1,.saturation=1,.contrast=1,.env={1,0,0,0}};l.p[0]=(simd_float4){1,.85,.6,1};l.p[1]=l.p[0];l.p[2]=(simd_float4){.5,.65,1,.3};l.p[3]=l.p[2];l.p[4]=l.p[2];l.p[5]=(simd_float4){1,.6,.38,1};l.p[6]=(simd_float4){.02,.45,1,1};l.materials[0]=(simd_float4){1,1,1,1};l.materials[1]=(simd_float4){1,1,1,1};l.materials[2]=(simd_float4){1,1,1,0};l.grade[0]=(simd_float4){1,1,1,1};l.grade[2]=(simd_float4){1,1,1,0};l.grade[3]=(simd_float4){1,1,1,1};return l;}
static float *probe(Enc *enc,Ctx *ctx,id<MTLRenderPipelineState> pipeline,Lighting l,uint32_t kind,MTLPixelFormat format){
 id<MTLTexture> color=image(ctx,520,96,format);id<MTLTexture> colors[]={color};float clear[]={1,0,0,0,0};mc_render_begin(enc,1,colors,clear,nil,0,0,520,96);
 [enc->render setRenderPipelineState:pipeline];[enc->render setCullMode:MTLCullModeNone];[enc->render setFragmentBytes:&l length:sizeof l atIndex:0];[enc->render setFragmentBytes:&kind length:4 atIndex:1];Sun sun={.sun={.6,.8,0,1},.config={8,1.f/512,.00015,1}};[enc->render setFragmentBytes:&sun length:sizeof sun atIndex:2];[enc->render drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];complete(enc);float *p=readPixels(color);[color release];return p;
}
static double difference(float *a,float *b,int profile){double total=0;for(int y=0;y<96;y++)for(int x=profile*8;x<(profile+1)*8;x++)for(int c=0;c<3;c++)total+=fabs(a[(y*520+x)*4+c]-b[(y*520+x)*4+c]);return total/(96*8*3);}
// Isolated full-screen material ALU cost; deliberately excludes scene geometry, shadows and CPU classification.
static void benchmark(Enc *enc,Ctx *ctx,id<MTLRenderPipelineState> off,id<MTLRenderPipelineState> on){
 id<MTLTexture> target=image(ctx,1920,1080,MTLPixelFormatRGBA8Unorm);double samples[2][32];Lighting l=lighting();uint32_t kind=0;
 Sun sun={.sun={.6,.8,0,1},.config={8,1.f/512,.00015,1}};
 for(int frame=0;frame<80;frame++){@autoreleasepool{int enabled=frame%2;id<MTLTexture> colors[]={target};float clear[]={1,0,0,0,0};mc_render_begin(enc,1,colors,clear,nil,0,0,1920,1080);[enc->render setRenderPipelineState:enabled?on:off];[enc->render setCullMode:MTLCullModeNone];[enc->render setFragmentBytes:&l length:sizeof l atIndex:0];[enc->render setFragmentBytes:&kind length:4 atIndex:1];[enc->render setFragmentBytes:&sun length:sizeof sun atIndex:2];[enc->render drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];id<MTLCommandBuffer> command=mc_enc_commit(enc);[command waitUntilCompleted];require(command.status==MTLCommandBufferStatusCompleted,command.error.localizedDescription);if(frame>=16)samples[enabled][(frame-16)/2]=(command.GPUEndTime-command.GPUStartTime)*1000;}}
 for(int e=0;e<2;e++)for(int i=0;i<32;i++)for(int j=i+1;j<32;j++)if(samples[e][j]<samples[e][i]){double t=samples[e][i];samples[e][i]=samples[e][j];samples[e][j]=t;}
 double a=(samples[0][15]+samples[0][16])*.5,b=(samples[1][15]+samples[1][16])*.5;
 printf("BENCH: %s, 1920x1080 RGBA8 repeated 65-profile full-screen probe, median of 32 alternating frames after 16 warmup: OFF %.4f ms ON %.4f ms delta %.4f ms (GPU command time, not gameplay)\n",ctx->device.name.UTF8String,a,b,b-a);[target release];
}
static void pack(uint32_t *q,const Vertex *v){memset(q,0,40);q[6]=q[7]=0xffffffff;q[8]=q[9]=0xf000f000;for(int axis=0;axis<3;axis++){uint16_t first=(uint16_t)lroundf((v[0].p[axis]+8)*2048),second=first;for(int c=1;c<4;c++){uint16_t code=(uint16_t)lroundf((v[c].p[axis]+8)*2048);if(code!=first)second=code;}q[1+axis]=first|((uint32_t)second<<16);for(int c=0;c<4;c++)if((uint16_t)lroundf((v[c].p[axis]+8)*2048)!=first)q[0]|=1u<<(3*c+axis);}q[4]=q[5]=0x80008000;}
static uint32_t bound(int x,int y,int z){return(x+16)|((y+16)<<8)|((z+16)<<16);}
static void transport(Ctx *ctx,NSString *source){
 char err[16384]={0};
 for(int compact=0;compact<3;compact++){
  BOOL qrec=compact>0,stub=compact==2;NSString *src=qrec?[@"#define OWN_QREC 1\n" stringByAppendingString:source]:source;
  void *own=mco_new(ctx,src.UTF8String,qrec,0,0,err,sizeof err);require(own!=NULL,@(err));Enc *enc=mc_enc_new(ctx);
  Vertex vertices[16]={0};for(int q=0;q<4;q++)for(int c=0;c<4;c++){Vertex *v=vertices+q*4+c;v->p[0]=-4+q*2+((c==1||c==2)?2:0);v->p[2]=c>=2?2:-2;memset(v->color,255,4);v->uv[0]=v->uv[1]=.5;v->light[0]=0;v->light[1]=240;}
  uint8_t bytes[1024]={0};int materialOffset=qrec?512:sizeof vertices;
  if(!qrec)memcpy(bytes,vertices,sizeof vertices);else for(int q=0;q<4;q++)pack((uint32_t *)(bytes+q*40),vertices+q*4);
  if(stub){uint32_t *q=(uint32_t *)bytes;q[0]=1u<<31;q[1]=3; // raw compact copy at quad 3 (192 bytes)
   for(int c=0;c<4;c++){uint16_t *v=(uint16_t *)(bytes+192+c*16);for(int a=0;a<3;a++)v[a]=(uint16_t)lroundf((vertices[c].p[a]+8)*2048);v[3]=0xf000;((uint32_t *)v)[2]=0xffffffff;v[6]=v[7]=32768;}}
  uint8_t ids[]={7,8,24,40};memcpy(bytes+materialOffset,ids,4);
  Section section={.count=2};Record records[2];memset(records,0,sizeof records);for(int q=0;q<2;q++){records[q].start=q*2;records[q].meta=1u|(6u<<6)|(1u<<19)|(qrec?1u<<17:0);records[q].lo=bound(-5,-1,-3);records[q].hi=bound(5,1,3);records[q].p0=materialOffset+q*2;}
  uint32_t lists[]={0,1,2,3},args[]={384,2,0,0,0,2,1,1};id<MTLBuffer> arena=buffer(ctx,bytes,sizeof bytes),sections=buffer(ctx,&section,sizeof section),recs=buffer(ctx,records,sizeof records),list=buffer(ctx,lists,sizeof lists),draw=buffer(ctx,args,sizeof args);
  simd_float4x4 view={.columns={{.2,0,0,0},{0,0,.1,0},{0,.35,0,0},{0,0,1.2,1}}},identity=matrix_identity_float4x4;
  uint8_t globals[48]={0},terrain[80]={0};((int *)globals)[1]=8;memcpy(terrain,&identity,64);((int *)(terrain+64))[0]=4;((int *)(terrain+64))[1]=4;
  float fog[10]={0,0,0,0,1000,2000,1000,2000,1000,1000};id<MTLBuffer> p=buffer(ctx,&view,64),g=buffer(ctx,globals,48),t=buffer(ctx,terrain,80),f=buffer(ctx,fog,sizeof fog);int64_t uniforms[]={(int64_t)p,0,(int64_t)g,0,(int64_t)t,0,(int64_t)f,0};
  id<MTLTexture> atlas=white(ctx),light=white(ctx);MTLSamplerDescriptor *sd=[MTLSamplerDescriptor new];id<MTLSamplerState> sampler=[ctx->device newSamplerStateWithDescriptor:sd];
  Sun sun={.clip={.columns={{.12,0,-.03,0},{-.09,0,-.04,0},{0,.15,0,0},{0,0,.4,1}}},.cam={0,8,0,0},.sun={.6,.8,0,1},.config={8,1.f/512,.00015,1},.sections=1,.capacity=2,.resolution=512,.on=1};
  int kinds[]={0,1<<22,1<<25,1<<20,1<<21,(1<<21)|(1<<23),1<<7,(1<<7)|(1<<20),(1<<7)|(1<<21)|(1<<29)};
  uint32_t ownerBytes[4]={0},indexBytes[24],quadArgs[]={24,1,0,0,0,1,1,1};for(int q=0;q<4;q++){int corners[]={0,1,2,0,2,3};for(int c=0;c<6;c++)indexBytes[q*6+c]=q*4+corners[c];}id<MTLBuffer> owner=buffer(ctx,ownerBytes,sizeof ownerBytes),quadIndices=buffer(ctx,indexBytes,sizeof indexBytes),qa=buffer(ctx,quadArgs,sizeof quadArgs);
  float *reference=NULL;
  for(int test=0;test<18;test++){
   BOOL enabled=test%2;float settings[15]={enabled,1,1,1,1,1,1,1,1,1,1,0};mco_sun_materials(own,settings,15);mco_sun_gbuffer(own,1,0);mco_sun_memory_optimization(own,test%3!=0);mco_sun_half_shadows(own,test>=6);
   id<MTLTexture> color=image(ctx,128,96,MTLPixelFormatRGBA8Unorm),depth=image(ctx,128,96,MTLPixelFormatDepth32Float);id<MTLTexture> colors[]={color};float clear[]={1,.1,.15,.2,.4};mc_render_begin(enc,1,colors,clear,depth,1,0,128,96);
   uint8_t packet[400]={0};memcpy(packet,&sun,144);memcpy(packet+144,&view,64);((double *)(packet+208))[1]=8;*(float *)(packet+240)=1.f/60;*(uint32_t *)(packet+248)=1;Sun far=sun;far.config.x=16;memcpy(packet+256,&far,144);
   OwnSunSurfaceView surface={.clip=view,.camera={0,8,0},.enabled=1,.flags=OWN_SUN_NO_RECEIVER_GRID|OWN_SUN_DEFERRED_LIGHTING};require(mco_sun_surface(own,&surface)==0,@"Surface packet rejected");
   require(mco_sun_frame(own,enc,packet,400,arena,sections,recs,atlas,sampler,err,sizeof err)>0,@(err));uint8_t df[80]={0};*(float *)(df+4)=1;require(mco_draw(own,enc,kinds[test/2],df,80,arena,sections,recs,test>=12?owner:list,test>=12?qa:draw,(test>=8&&test<12)||test>=16?20:0,uniforms,atlas,sampler,light,sampler,test>=12?(test<14||test>=16?quadIndices:list):nil,err,sizeof err)>0,@(err));require(mco_sun_finish(own,enc,err,sizeof err)==1,@(err));complete(enc);
   // Read the actual production RGBA8 material channel, not a shader probe.
   uint8_t data[128*96*4];id<MTLBuffer> read=[ctx->device newBufferWithLength:sizeof data options:MTLResourceStorageModeShared];id<MTLCommandBuffer> copy=[ctx->queue commandBuffer];id<MTLBlitCommandEncoder> blit=[copy blitCommandEncoder];[blit copyFromTexture:mco_sun_debug_light_data(own) sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0,0,0) sourceSize:MTLSizeMake(128,96,1) toBuffer:read destinationOffset:0 destinationBytesPerRow:128*4 destinationBytesPerImage:sizeof data];[blit endEncoding];[copy commit];[copy waitUntilCompleted];require(copy.status==MTLCommandBufferStatusCompleted,copy.error.localizedDescription);memcpy(data,read.contents,sizeof data);[read release];int count[256]={0};for(int i=0;i<128*96;i++)count[data[i*4+2]]++;
   for(int q=0;q<4;q++)require(count[ids[q]]>100,[NSString stringWithFormat:@"Material transport compact=%d kind=%d ID=%d missing (%d)",compact,kinds[test/2],ids[q],count[ids[q]]]);
   float *pixels=readPixels(color);if(test==0)reference=pixels;else{if(!enabled){for(int i=0;i<128*96*4;i++)require(fabs(pixels[i]-reference[i])<.008,@"Material OFF differs across draw/resolve variants");}free(pixels);}
   [color release];[depth release];
  }
  free(reference);printf("PASS: production material transport raw/QREC/stub=%d, normal/reverse/tight/nonindexed/mesh/mesh4/per-quad/qmesh, fetch/copy, half shadows and live switches\n",compact);
 }
}
int main(int argc,const char **argv){@autoreleasepool{
 require(argc==3,@"Usage: surface_material_check TERRAIN SUN");NSString *source=[[NSString stringWithContentsOfFile:@(argv[1]) encoding:NSUTF8StringEncoding error:nil] stringByAppendingString:[NSString stringWithContentsOfFile:@(argv[2]) encoding:NSUTF8StringEncoding error:nil]];
 char err[16384]={0},name[256];Ctx *ctx=mc_create(name,sizeof name,err,sizeof err);require(ctx!=NULL,@(err));Enc *enc=mc_enc_new(ctx);
 NSString *test=@"\nfragment float4 surface_probe_fs(float4 p [[position]],constant SunLighting &l [[buffer(0)]],constant uint &kind [[buffer(1)]],constant SunFrame &sun [[buffer(2)]]){uint id=kind==2?255:(uint(p.x)/8)%SURFACE_PROFILE_COUNT;float y=fmod(p.y,96.0);float3 texel=y<32?float3(.7):y<64?float3(.9,.55,.1):float3(.1,.8,.9);float3 n=normalize(float3(.6,.8,0)),pos=-n*8;if(kind==1){pos=n*8;n=float3(-1,0,0);}float3 lit=sunTerrainLight(pow(texel,2.2),0,1,n,float3(1),pos,sun,l);lit=sunSurfaceMaterial(lit,texel,id,1,n,float3(1),pos,sun,l);return float4(pow(sunToneMap(lit,l),1.0/2.2),.4);}\n";
 NSError *issue=nil;MTLCompileOptions *opts=[MTLCompileOptions new];opts.languageVersion=MTLLanguageVersion3_0;id<MTLLibrary> lib=[ctx->device newLibraryWithSource:[source stringByAppendingString:test] options:opts error:&issue];require(lib!=nil,issue.localizedDescription);
 MTLPixelFormat formats[]={MTLPixelFormatRGBA8Unorm,MTLPixelFormatBGRA8Unorm,MTLPixelFormatRGBA8Unorm_sRGB,MTLPixelFormatBGRA8Unorm_sRGB,MTLPixelFormatRGBA16Float};
 for(int fi=0;fi<5;fi++){
  id<MTLRenderPipelineState> pipelines[2];for(int on=0;on<2;on++){MTLFunctionConstantValues *values=[MTLFunctionConstantValues new];bool flag=on;[values setConstantValue:&flag type:MTLDataTypeBool atIndex:51];MTLRenderPipelineDescriptor *pd=[MTLRenderPipelineDescriptor new];pd.vertexFunction=[lib newFunctionWithName:@"sun_screen_vs"];pd.fragmentFunction=[lib newFunctionWithName:@"surface_probe_fs" constantValues:values error:&issue];pd.colorAttachments[0].pixelFormat=formats[fi];pipelines[on]=[ctx->device newRenderPipelineStateWithDescriptor:pd error:&issue];require(pipelines[on]!=nil,issue.localizedDescription);}
  Lighting l=lighting();l.srgb=fi==2||fi==3;float *off=probe(enc,ctx,pipelines[0],l,0,formats[fi]),*on=probe(enc,ctx,pipelines[1],l,0,formats[fi]);
  require(difference(off,on,6)>.01,@"Iron did not change");require(difference(off,on,24)>.001,@"Leaf highlights missing");require(difference(off,on,39)>.01,@"Lit lamp emission missing");
  for(int i=0;i<520*96;i++)require(off[i*4+3]==on[i*4+3],@"Material changed alpha");
  l.materials[0].x=0;float *masterOff=probe(enc,ctx,pipelines[1],l,0,formats[fi]);for(int i=0;i<520*96*4;i++)require(masterOff[i]==off[i],@"Master OFF does not restore exact original shading");free(masterOff);l=lighting();l.srgb=fi==2||fi==3;
  for(int i=5;i<=10;i++)((float *)l.materials)[i]=0;float *groups=probe(enc,ctx,pipelines[1],l,0,formats[fi]);for(int i=0;i<520*96*4;i++)require(groups[i]==off[i],@"Group OFF does not restore original shading");free(groups);
  l=lighting();l.srgb=fi==2||fi==3;l.materials[0].y=0;float *noGloss=probe(enc,ctx,pipelines[1],l,0,formats[fi]);require(difference(on,noGloss,6)>.01,@"Gloss control ineffective");free(noGloss);
  l=lighting();l.srgb=fi==2||fi==3;l.materials[0].z=2;float *rough=probe(enc,ctx,pipelines[1],l,0,formats[fi]);require(difference(on,rough,6)>.01,@"Roughness control ineffective");free(rough);
  l=lighting();l.srgb=fi==2||fi==3;l.materials[0].w=0;float *noEmission=probe(enc,ctx,pipelines[1],l,0,formats[fi]);require(difference(on,noEmission,39)>.01,@"Emission control ineffective");free(noEmission);
  l=lighting();l.srgb=fi==2||fi==3;float *through=probe(enc,ctx,pipelines[1],l,1,formats[fi]);l.materials[1].x=0;float *noThrough=probe(enc,ctx,pipelines[1],l,1,formats[fi]);require(difference(through,noThrough,24)>.005,@"Plant transmission control ineffective");free(through);free(noThrough);
  l=lighting();l.srgb=fi==2||fi==3;float *invalid=probe(enc,ctx,pipelines[1],l,2,formats[fi]);for(int i=0;i<520*96*4;i++)require(invalid[i]==off[i],@"Unknown profile not neutral");free(invalid);free(off);free(on);
  if(fi==0&&getenv("SILICONSHADERS_MATERIAL_BENCH"))benchmark(enc,ctx,pipelines[0],pipelines[1]);
  printf("PASS: 65 material profiles and live controls format=%lu, exact OFF/groups and alpha\n",(unsigned long)formats[fi]);
 }
 transport(ctx,source);printf("PASS: material shader and production transport checks\n");return 0;
}}
