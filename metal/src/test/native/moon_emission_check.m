// Real Java source packets -> production sky glow, depth-gated camera flares and both volumetric ray implementations.
#define main celestial_previous_main
#include "celestial_check.m"
#undef main
extern void mco_sun_celestial(void *,const float *,int);
extern void mco_sun_environment(void *,float,float,int,int);
extern void mco_sun_gbuffer(void *,int,int);
extern void mco_sun_moon_light(void *,int);
extern int mco_camera_post(void *,Enc *,id<MTLTexture>,id<MTLTexture>,const void *,int,char *,int);
extern void mco_camera_reset(void *);
typedef struct {int32_t shape,phase,angle;float values[56];} Fixture;
typedef struct {simd_float4x4 inverse,previous;simd_float4 screen,light,lightColor,p[16];} Camera;
static Fixture fixtures[192];static int effectCases;
static Fixture *fixture(int shape,int phase,int angle){Fixture *f=&fixtures[shape*64+phase*8+angle/45];require(f->shape==shape&&f->phase==phase&&f->angle==angle,@"Fixture order");return f;}
static void param(Camera *c,int index,float value){c->p[index/4][index%4]=value;}
static float *effect(Ctx *ctx,Enc *enc,void *own,id<MTLRenderPipelineState> fill,Fixture *source,int mask,int occlusion,int optimized) {
 char error[16384]={0};simd_float3 center=simd_normalize((simd_float3){-.6,.8,0}),right=simd_normalize(simd_cross((simd_float3){0,0,1},center)),up=simd_cross(center,right);
 Camera c={.screen={W,H,1.f/60,0},.light={.5,.5,1,0},.lightColor={1,1,1,0}};
 c.inverse.columns[0]=(simd_float4){right.x*.14,right.y*.14,right.z*.14,0};c.inverse.columns[1]=(simd_float4){up.x*.14,up.y*.14,up.z*.14,0};c.inverse.columns[2]=(simd_float4){0,0,0,1};c.inverse.columns[3]=(simd_float4){center.x,center.y,center.z,0};c.previous=simd_inverse(c.inverse);
 c.p[15]=(simd_float4){center.x,center.y,center.z,0};param(&c,0,1);
 param(&c,22,(mask&8)?1:0);param(&c,23,2);param(&c,24,1);param(&c,25,.4);param(&c,26,.04);param(&c,29,8);
 param(&c,48,(mask&128)?1:0);param(&c,49,1);param(&c,50,.015);param(&c,51,64);param(&c,52,16);param(&c,53,.25);param(&c,54,.65);param(&c,55,1);param(&c,56,1);param(&c,57,optimized);
 id<MTLTexture> color=image(ctx,W,H,MTLPixelFormatRGBA16Float);
 MTLTextureDescriptor *d=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatDepth32Float width:W height:H mipmapped:NO];d.storageMode=MTLStorageModePrivate;d.usage=MTLTextureUsageRenderTarget|MTLTextureUsageShaderRead;id<MTLTexture> depth=[ctx->device newTextureWithDescriptor:d];
 id<MTLTexture> targets[]={color};float clear[5]={1,0,0,0,1};mc_render_begin(enc,1,targets,clear,depth,1,0,W,H);
 mco_sun_celestial(own,source->values,56);mco_sun_environment(own,1,0,0,1);mco_sun_gbuffer(own,1,0);mco_sun_moon_light(own,1);
 uint8_t packet[688]={0};for(int i=0;i<2;i++){
  float range=i?256:128;Sun sun={.clip={.columns={{1/range,0,0,0},{0,0,-1.f/512,0},{0,1/range,0,0},{0,0,.5,1}}},.sun={center.x,center.y,center.z,1},.config={range,1.f/512,.00015,1},.sections=1,.resolution=512,.on=1};memcpy(packet+(i?256:0),&sun,sizeof sun);
 }*(uint32_t *)(packet+252)=2;
 Vertex vertex[4]={0};Section section={0};Record record={0};id<MTLBuffer> arena=buffer(ctx,vertex,sizeof vertex),sections=buffer(ctx,&section,sizeof section),records=buffer(ctx,&record,sizeof record);id<MTLTexture> atlas=white(ctx);MTLSamplerDescriptor *sd=[MTLSamplerDescriptor new];id<MTLSamplerState> sampler=[ctx->device newSamplerStateWithDescriptor:sd];
 require(mco_sun_frame(own,enc,packet,sizeof packet,arena,sections,records,atlas,sampler,error,sizeof error)>=0,@(error));
 mc_render_begin(enc,1,targets,clear,depth,1,0,W,H);[enc->render setRenderPipelineState:fill];[enc->render setCullMode:MTLCullModeNone];
 MTLDepthStencilDescriptor *dd=[MTLDepthStencilDescriptor new];dd.depthWriteEnabled=YES;dd.depthCompareFunction=MTLCompareFunctionAlways;id<MTLDepthStencilState> ds=[ctx->device newDepthStencilStateWithDescriptor:dd];[enc->render setDepthStencilState:ds];uint32_t pattern=occlusion;[enc->render setFragmentBytes:&pattern length:4 atIndex:0];[enc->render drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
 int result=mco_camera_post(own,enc,color,depth,&c,sizeof c,error,sizeof error);require(result>=0,@(error));
 id<MTLCommandBuffer> command=mc_enc_commit(enc);[command waitUntilCompleted];require(command.status==MTLCommandBufferStatusCompleted,command.error.localizedDescription);[command release];
 _Float16 *raw=malloc(W*H*8);float *out=malloc(W*H*16);[color getBytes:raw bytesPerRow:W*8 fromRegion:MTLRegionMake2D(0,0,W,H) mipmapLevel:0];for(int i=0;i<W*H*4;i++){out[i]=raw[i];require(isfinite(out[i])&&out[i]>=0,@"Invalid effect pixel");}free(raw);
 mco_camera_reset(own);[arena release];[sections release];[records release];[atlas release];[sampler release];[sd release];[dd release];[ds release];[color release];[depth release];effectCases++;return out;
}
static double side(float *p,BOOL right){double sum=0;for(int y=0;y<H;y++)for(int x=right?W/2+64:0;x<(right?W:W/2-64);x++){int i=(y*W+x)*4;sum+=p[i]+p[i+1]+p[i+2];}return sum;}
static double linearEnergy(float *p){double sum=0;for(int i=0;i<W*H;i++)for(int c=0;c<3;c++)sum+=pow(p[i*4+c],2.2);return sum;}
static double linearCenterX(float *p){double sum=0,weight=0;for(int y=0;y<H;y++)for(int x=0;x<W;x++){int i=(y*W+x)*4;double e=pow(p[i],2.2)+pow(p[i+1],2.2)+pow(p[i+2],2.2);sum+=(x-W*.5)*e;weight+=e;}return sum/fmax(weight,1e-10);}
int main(int argc,const char **argv){@autoreleasepool{
 require(argc==6,@"Usage: moon_emission_check TERRAIN SUN CAMERA JAVA_FIXTURES OUTPUT_DIR");
 FILE *in=fopen(argv[4],"rb");require(in!=NULL,@"Java fixtures missing");uint32_t header[2];require(fread(header,4,2,in)==2&&header[0]==0x4d454d31&&header[1]==192,@"Fixture header");require(fread(fixtures,sizeof(Fixture),192,in)==192,@"Fixture body");fclose(in);
 [[NSFileManager defaultManager] createDirectoryAtPath:@(argv[5]) withIntermediateDirectories:YES attributes:nil error:NULL];
 NSString *source=@"";for(int i=1;i<=3;i++)source=[source stringByAppendingString:[NSString stringWithContentsOfFile:@(argv[i]) encoding:NSUTF8StringEncoding error:NULL]];
 source=[source stringByAppendingString:@"\nfragment float4 celestial_probe_fs(float4 p[[position]],constant SunLighting &l[[buffer(0)]],constant SunFrame &sun[[buffer(1)]],constant SunTemporalFrame &frame[[buffer(2)]],constant Fog &fog[[buffer(3)]],constant float2 &u[[buffer(4)]],constant float4 &background[[buffer(5)]],texture2d<float> noise[[texture(0)]]){float2 uv=p.xy/frame.config.xy;return float4(sunCelestialBodies(sunSkyDirection(uv,frame),sunSkyPixelSize(uv,sunSkyDirection(uv,frame),frame),sun,l),1);}\nstruct EmissionFill{half4 color[[color(0)]];float depth[[depth(any)]];};fragment EmissionFill emission_fill_fs(float4 p[[position]],constant uint &mode[[buffer(0)]]){bool blocked=mode==1?p.x>=128:mode==2?p.x<128:false;return {half4(0,0,0,1),blocked?.1:0};}\n"];
 char name[256],error[16384];Ctx *ctx=mc_create(name,sizeof name,error,sizeof error);require(ctx!=NULL,@(error));Enc *enc=mc_enc_new(ctx);void *own=mco_new(ctx,source.UTF8String,0,0,0,error,sizeof error);require(own!=NULL,@(error));
 NSError *issue=nil;MTLCompileOptions *options=[MTLCompileOptions new];options.languageVersion=MTLLanguageVersion3_0;id<MTLLibrary> library=[ctx->device newLibraryWithSource:source options:options error:&issue];require(library!=nil,issue.localizedDescription);
 MTLRenderPipelineDescriptor *pd=[MTLRenderPipelineDescriptor new];pd.vertexFunction=[library newFunctionWithName:@"sun_screen_vs"];
 MTLFunctionConstantValues *fc=[MTLFunctionConstantValues new];bool on=true;[fc setConstantValue:&on type:MTLDataTypeBool atIndex:64];
 pd.fragmentFunction=[library newFunctionWithName:@"celestial_probe_fs" constantValues:fc error:&issue];require(pd.fragmentFunction!=nil,issue.localizedDescription);pd.colorAttachments[0].pixelFormat=MTLPixelFormatRGBA32Float;id<MTLRenderPipelineState> sky=[ctx->device newRenderPipelineStateWithDescriptor:pd error:&issue];require(sky!=nil,issue.localizedDescription);
 pd.fragmentFunction=[library newFunctionWithName:@"emission_fill_fs"];pd.colorAttachments[0].pixelFormat=MTLPixelFormatRGBA16Float;pd.depthAttachmentPixelFormat=MTLPixelFormatDepth32Float;id<MTLRenderPipelineState> fill=[ctx->device newRenderPipelineStateWithDescriptor:pd error:&issue];require(fill!=nil,issue.localizedDescription);
 for(int shape=0;shape<3;shape++)for(int angle=0;angle<=180;angle+=180){
  Lighting l=base();memcpy(l.celestial,fixture(shape,2,angle)->values,224);float *p=probe(ctx,enc,sky,l,YES,0,NO,NO);double left=side(p,NO),right=side(p,YES);require(angle==0?right>left*1.1:left>right*1.1,@"Moon glow still originates from a full circular disk");save(p,[@(argv[5]) stringByAppendingPathComponent:[NSString stringWithFormat:@"glow-%d-%d.png",shape,angle]]);free(p);
  memcpy(l.celestial,fixture(shape,4,angle)->values,224);p=probe(ctx,enc,sky,l,YES,0,NO,NO);require(energy(p)==0,@"New moon still has glow");free(p);
 }
 for(int angle=0;angle<=180;angle+=180){
  float *open=effect(ctx,enc,own,fill,fixture(0,2,angle),8,0,0),*right=effect(ctx,enc,own,fill,fixture(0,2,angle),8,1,0),*left=effect(ctx,enc,own,fill,fixture(0,2,angle),8,2,0);
  double e=linearEnergy(open),blocked=linearEnergy(angle==0?right:left),clear=linearEnergy(angle==0?left:right);printf("flare %d: open %.4f lit-blocked %.4f dark-blocked %.4f\n",angle,e,blocked,clear);
  require(e>1&&blocked<e*.08&&clear>e*.9,@"Flare occlusion uses the unlit half or the full moon center");save(open,[@(argv[5]) stringByAppendingPathComponent:[NSString stringWithFormat:@"flare-%d.png",angle]]);free(open);free(right);free(left);
 }
 for(int optimized=0;optimized<2;optimized++){
  float *full=effect(ctx,enc,own,fill,fixture(0,0,0),128,0,optimized),*half=effect(ctx,enc,own,fill,fixture(0,2,0),128,0,optimized),*opposite=effect(ctx,enc,own,fill,fixture(0,2,180),128,0,optimized),*none=effect(ctx,enc,own,fill,fixture(0,4,0),128|8,0,optimized);
  printf("rays %d: right centroid %.3f left centroid %.3f\n",optimized,linearCenterX(half),linearCenterX(opposite));require(linearCenterX(half)>linearCenterX(opposite)+1,@"Godray scattering source ignores the phase rotation");free(opposite);
  printf("rays %d: full %.4f half %.4f new %.4f\n",optimized,linearEnergy(full),linearEnergy(half),linearEnergy(none));require(linearEnergy(full)>1&&linearEnergy(half)>linearEnergy(full)*.25&&linearEnergy(half)<linearEnergy(full)*.75&&linearEnergy(none)==0,@"Original/Optimized rays ignore the visible phase");save(half,[@(argv[5]) stringByAppendingPathComponent:[NSString stringWithFormat:@"rays-%d.png",optimized]]);free(full);free(half);free(none);
 }
 printf("PASS: %d actual camera cases + %d sky cases from Java source packets: phase-rotated glow on three shapes, exact new-moon extinction, lit-vs-dark occlusion for flares, Original/Optimized volumetric phase energy\n",effectCases,cases);
 return 0;
}}
