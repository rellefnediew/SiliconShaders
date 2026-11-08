// Actual renderer transitions using SunFrames/SunDimension packets emitted by dimensionChecks.
#include "sun_test_common.h"
#include "mcsun_surface.h"
extern void mco_sun_aurora(void *,const float *,int);
extern void mco_sun_gbuffer(void *,int,int);
extern void mco_sun_environment(void *,float,float,int,int);
extern int mco_sun_surface(void *,const void *);
extern int mco_sun_debug_deferred(void *);
extern int mco_sun_debug_gbuffer(void *);
extern int mco_sun_debug_lighting(void *);
extern int mco_sun_finish(void *,Enc *,char *,int);
extern void mco_sun_filter_optimization(void *,int);
int main(int argc,const char **argv){@autoreleasepool{
 require(argc==4,@"Usage: dimension_transition_check TERRAIN SUN FRAME_DIR");
 NSString *source=[[NSString stringWithContentsOfFile:@(argv[1]) encoding:NSUTF8StringEncoding error:NULL] stringByAppendingString:[NSString stringWithContentsOfFile:@(argv[2]) encoding:NSUTF8StringEncoding error:NULL]];
 char error[16384]={0},name[256]={0};Ctx *ctx=mc_create(name,sizeof name,error,sizeof error);require(ctx!=NULL,@(error));
 void *own=mco_new(ctx,source.UTF8String,0,0,0,error,sizeof error);require(own!=NULL,@(error));Enc *enc=mc_enc_new(ctx);
 Vertex vertices[4]={0};for(int c=0;c<4;c++){Vertex *v=&vertices[c];v->p[0]=(c==1||c==2)?4:-4;v->p[2]=c>=2?4:-4;memset(v->color,255,4);v->uv[0]=v->uv[1]=.5;}
 Section section={.count=1};Record record={0};uint32_t lists[]={0},args[]={384,1,0,0,0};
 id<MTLBuffer> arena=buffer(ctx,vertices,sizeof vertices),sections=buffer(ctx,&section,sizeof section),recs=buffer(ctx,&record,sizeof record),list=buffer(ctx,lists,sizeof lists),draw=buffer(ctx,args,sizeof args);
 simd_float4x4 view={.columns={{.2,0,0,0},{0,0,.1,0},{0,.2,0,0},{0,0,1.2,1}}},identity=matrix_identity_float4x4;
 uint8_t globals[48]={0},terrain[80]={0};int cam[]={0,8,0};memcpy(globals,cam,12);memcpy(terrain,&identity,64);int size[]={4,4};memcpy(terrain+64,size,8);
 float fog[]={.15,.07,.2,1,1000,2000,1000,2000,1000,1000};
 id<MTLBuffer> p=buffer(ctx,&view,64),g=buffer(ctx,globals,sizeof globals),t=buffer(ctx,terrain,sizeof terrain),f=buffer(ctx,fog,sizeof fog);
 int64_t uniforms[]={(int64_t)p,0,(int64_t)g,0,(int64_t)t,0,(int64_t)f,0};
 id<MTLTexture> atlas=white(ctx),light=white(ctx);MTLSamplerDescriptor *sd=[MTLSamplerDescriptor new];sd.minFilter=sd.magFilter=MTLSamplerMinMagFilterNearest;id<MTLSamplerState> sampler=[ctx->device newSamplerStateWithDescriptor:sd];
 uint8_t samples[7][4],skies[7][4];
 // Repeat the sequence with the separate temporal resolve and the optimized fused resolve.
 for(int fused=0;fused<2;fused++)for(int phase=0;phase<7;phase++){
  NSData *frame=[NSData dataWithContentsOfFile:[@(argv[3]) stringByAppendingPathComponent:[NSString stringWithFormat:@"%d.frame",phase]]],*aurora=[NSData dataWithContentsOfFile:[@(argv[3]) stringByAppendingPathComponent:[NSString stringWithFormat:@"%d.aurora",phase]]];
  require(frame.length==688&&aurora.length==112,@"Missing production packets");
  mco_sun_aurora(own,aurora.bytes,28);mco_sun_gbuffer(own,1,0);mco_sun_environment(own,1,0,0,1);mco_sun_filter_optimization(own,fused);
  OwnSunSurfaceView surface={.clip=view,.camera={0,8,0},.world=phase,.enabled=phase<6&&*(const uint32_t *)((const uint8_t *)frame.bytes+136)>0,.flags=OWN_SUN_DEFERRED_LIGHTING};require(mco_sun_surface(own,&surface)==0,@"Surface transition rejected");
  int width=phase%2?96:128;
  MTLTextureDescriptor *td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:width height:width mipmapped:NO];td.storageMode=MTLStorageModeShared;td.usage=MTLTextureUsageRenderTarget;id<MTLTexture> color=[ctx->device newTextureWithDescriptor:td];
  td.pixelFormat=MTLPixelFormatDepth32Float;td.storageMode=MTLStorageModePrivate;td.usage=MTLTextureUsageRenderTarget|MTLTextureUsageShaderRead;id<MTLTexture> depth=[ctx->device newTextureWithDescriptor:td];
  id<MTLTexture> target[]={color};float clear[]={1,.02,.03,.04,1};mc_render_begin(enc,1,target,clear,depth,1,0,width,width);
  require(mco_sun_frame(own,enc,frame.bytes,688,arena,sections,recs,atlas,sampler,error,sizeof error)==(phase<6?1:0),@(error));
  require(mco_sun_debug_deferred(own)==(phase<6),@"Dimension lighting went to forward/vanilla fallback");
  require(mco_sun_debug_gbuffer(own)==(phase<6),@"Dimension G-buffer missing (zero shadow resolution)");
  uint8_t df[80]={0};*(int *)(df+4)=1;
  require(mco_draw(own,enc,0,df,80,arena,sections,recs,list,draw,0,uniforms,atlas,sampler,light,sampler,nil,error,sizeof error)>0,@(error));
  require(mco_sun_finish(own,enc,error,sizeof error)==(phase<6?1:0),@(error));
  require(mco_sun_debug_lighting(own)==(phase<6),@"Native lighting was not applied");
  id<MTLCommandBuffer> command=mc_enc_commit(enc);[command waitUntilCompleted];require(command.status==MTLCommandBufferStatusCompleted,command.error.localizedDescription);
  [color getBytes:samples[phase] bytesPerRow:4 fromRegion:MTLRegionMake2D(width/2,width/2,1,1) mipmapLevel:0];
  [color getBytes:skies[phase] bytesPerRow:4 fromRegion:MTLRegionMake2D(1,1,1,1) mipmapLevel:0];
  printf("resolve=%d phase=%d center=%d,%d,%d sky=%d,%d,%d\n",fused,phase,samples[phase][0],samples[phase][1],samples[phase][2],skies[phase][0],skies[phase][1],skies[phase][2]);
  [color release];[depth release];
 }
 require(samples[1][0]>samples[1][2]&&samples[2][0]>=samples[2][2]&&samples[2][1]>samples[1][1],@"Nether/End light not present on actual terrain");
 for(int c=0;c<3;c++){require(abs(samples[1][c]-samples[4][c])<=1,@"Nether retained End history");require(abs(samples[0][c]-samples[5][c])<=1,@"Overworld retained dimension light");}
 require(samples[6][0]>240&&samples[6][1]>240,@"Disabling shader failed to restore vanilla terrain");
 require(skies[2][2]>skies[2][1]&&skies[2][2]>10,@"Actual End background pass missing");
 printf("PASS: 14 real world frames: Overworld -> Nether -> shadowless End -> shadowed End -> Nether -> Overworld -> shader off; resize, G-buffer, native light and sky, both resolve paths\n");
}return 0;}
