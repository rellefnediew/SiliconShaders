// Actual Minecraft stages translated and patched by entityChecks, rendered under Metal validation.
#include "sun_test_common.h"
extern void mco_sun_entity_bind(void *,Enc *,int,id<MTLBuffer>,int64_t);
typedef struct {uint32_t enabled,srgb;float exposure,saturation,contrast,moon,pad[2];simd_float4 env,p[19],hand[3],materials[4],aurora[3],grade[4],celestial[14],sky[4];} Lighting;
_Static_assert(sizeof(Lighting)==864,"Lighting ABI");
typedef struct {float p[3],color[4],uv[2];int overlay[2],light[2];float normal[3],glint[2];} EntityVertex;
// SunFrame, SunBlendFrame and SunGlassFrame as the objects' fragment stage reads them (buffer 25).
typedef struct {Sun sun;uint8_t blend[320];float glass[24];} EntityFrame;
_Static_assert(sizeof(EntityFrame)==560,"Entity frame ABI");
// One-cascade shadow maps cleared to "nothing in front" (1) and "occluded everywhere" (0), plus white glass color.
static id<MTLTexture> litMap,darkMap,glassWhite;
static id<MTLTexture> shadowMap(Ctx *ctx,id<MTLCommandQueue> queue,float depth){
 MTLTextureDescriptor *td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatDepth32Float width:64 height:64 mipmapped:NO];td.storageMode=MTLStorageModePrivate;td.usage=MTLTextureUsageRenderTarget|MTLTextureUsageShaderRead;
 id<MTLTexture> map=[ctx->device newTextureWithDescriptor:td];MTLRenderPassDescriptor *rp=[MTLRenderPassDescriptor renderPassDescriptor];
 rp.depthAttachment.texture=map;rp.depthAttachment.loadAction=MTLLoadActionClear;rp.depthAttachment.storeAction=MTLStoreActionStore;rp.depthAttachment.clearDepth=depth;
 id<MTLCommandBuffer> c=[queue commandBuffer];[[c renderCommandEncoderWithDescriptor:rp] endEncoding];[c commit];[c waitUntilCompleted];return map;
}
static NSString *readShader(NSString *path){NSError *e=nil;NSString *s=[NSString stringWithContentsOfFile:path encoding:NSUTF8StringEncoding error:&e];require(s!=nil,e.localizedDescription);return s;}
static NSArray *matches(NSString *source,NSString *pattern){return [[NSRegularExpression regularExpressionWithPattern:pattern options:0 error:nil] matchesInString:source options:0 range:NSMakeRange(0,source.length)];}
static id<MTLRenderPipelineState> pipeline(Ctx *ctx,NSString *vs,NSString *fs,MTLPixelFormat format){
 NSError *e=nil;MTLCompileOptions *options=[MTLCompileOptions new];options.languageVersion=MTLLanguageVersion3_0;
 id<MTLLibrary> vl=[ctx->device newLibraryWithSource:vs options:options error:&e];require(vl!=nil,e.localizedDescription);
 id<MTLLibrary> fl=[ctx->device newLibraryWithSource:fs options:options error:&e];require(fl!=nil,e.localizedDescription);
 MTLRenderPipelineDescriptor *pd=[MTLRenderPipelineDescriptor new];pd.vertexFunction=[vl newFunctionWithName:@"main0"];pd.fragmentFunction=[fl newFunctionWithName:@"main0"];pd.colorAttachments[0].pixelFormat=format;
 MTLVertexDescriptor *vd=[MTLVertexDescriptor vertexDescriptor];
 NSDictionary *offset=@{@"Position":@0,@"Color":@12,@"UV0":@28,@"UV1":@36,@"UV2":@44,@"Normal":@52,@"UV3":@64};
 for(NSTextCheckingResult *m in matches(vs,@"(Position|Color|UV0|UV1|UV2|UV3|Normal) \\[\\[attribute\\((\\d+)\\)\\]\\]")){
  NSString *name=[vs substringWithRange:[m rangeAtIndex:1]];int i=[[vs substringWithRange:[m rangeAtIndex:2]] intValue];
  vd.attributes[i].bufferIndex=27;vd.attributes[i].offset=[offset[name] unsignedIntegerValue];
  vd.attributes[i].format=[name isEqualToString:@"Color"]?MTLVertexFormatFloat4:[name isEqualToString:@"Position"]||[name isEqualToString:@"Normal"]?MTLVertexFormatFloat3:([name isEqualToString:@"UV0"]||[name isEqualToString:@"UV3"])?MTLVertexFormatFloat2:MTLVertexFormatInt2;
 }
 vd.layouts[27].stride=sizeof(EntityVertex);pd.vertexDescriptor=vd;
 id<MTLRenderPipelineState> p=[ctx->device newRenderPipelineStateWithDescriptor:pd error:&e];require(p!=nil,e.localizedDescription);[pd.vertexFunction release];[pd.fragmentFunction release];[pd release];[vl release];[fl release];[options release];return p;
}
static void inputs(id<MTLRenderCommandEncoder> r,NSString *source,BOOL vertex,id<MTLTexture> texture,id<MTLTexture> overlay,id<MTLSamplerState> sampler,float tint,float overlayWeight){
 uint8_t bytes[256]={0};
 for(NSTextCheckingResult *m in matches(source,@"constant (DynamicTransforms|Projection|Lighting|Fog|Globals)& \\w+ \\[\\[buffer\\((\\d+)\\)\\]\\]")){
  memset(bytes,0,sizeof bytes);NSString *name=[source substringWithRange:[m rangeAtIndex:1]];int slot=[[source substringWithRange:[m rangeAtIndex:2]] intValue];
  if([name isEqualToString:@"Projection"]){simd_float4x4 p={.columns={{1,0,0,0},{0,1,0,0},{0,0,0,-1},{0,0,.05,0}}};memcpy(bytes,&p,64);}
  if([name isEqualToString:@"DynamicTransforms"]){simd_float4x4 i=matrix_identity_float4x4;memcpy(bytes,&i,64);memcpy(bytes+64,&i,64);float c[]={tint,tint,tint,1};memcpy(bytes+128,c,16);}
  if([name isEqualToString:@"Lighting"]){float d[]={0,1,0,0, 0,1,0,0};memcpy(bytes,d,sizeof d);}
  if([name isEqualToString:@"Fog"]){float f[]={.15,.2,.3,1, 10000,20000,10000,20000,20000,20000};memcpy(bytes,f,sizeof f);}
  if([name isEqualToString:@"Globals"]){float alpha=.2;memcpy(bytes+12,&alpha,4);}
  if(vertex)[r setVertexBytes:bytes length:256 atIndex:slot];else[r setFragmentBytes:bytes length:256 atIndex:slot];
 }
 for(NSTextCheckingResult *m in matches(source,@"texture2d<float> (Sampler0|Sampler1|Sampler2|GlintSampler|DissolveMaskSampler) \\[\\[texture\\((\\d+)\\)\\]\\]")){
  NSString *name=[source substringWithRange:[m rangeAtIndex:1]];int slot=[[source substringWithRange:[m rangeAtIndex:2]] intValue];
  id<MTLTexture> value=[name isEqualToString:@"Sampler1"]?overlay:texture;
  if(vertex){[r setVertexTexture:value atIndex:slot];[r setVertexSamplerState:sampler atIndex:slot];}else{[r setFragmentTexture:value atIndex:slot];[r setFragmentSamplerState:sampler atIndex:slot];}
 }
}
static void render(Ctx *ctx,Enc *enc,id<MTLRenderPipelineState> p,NSString *vs,NSString *fs,Lighting lighting,Sun sun,int mode,uint8_t pixel[4]){
 const int width=64,height=64;MTLTextureDescriptor *td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:width height:height mipmapped:NO];td.storageMode=MTLStorageModeShared;td.usage=MTLTextureUsageRenderTarget;
 id<MTLTexture> target=[ctx->device newTextureWithDescriptor:td];id<MTLTexture> targets[]={target};float clear[]={1,.01,.02,.03,.4};mc_render_begin(enc,1,targets,clear,nil,0,0,width,height);
 id<MTLTexture> tex=white(ctx),overlay=white(ctx);uint8_t rgba[64],ov[64];for(int i=0;i<16;i++){rgba[i*4]=rgba[i*4+1]=rgba[i*4+2]=180;rgba[i*4+3]=mode==4?0:220;ov[i*4]=255;ov[i*4+1]=ov[i*4+2]=0;ov[i*4+3]=mode==3?100:255;}
 [tex replaceRegion:MTLRegionMake2D(0,0,4,4) mipmapLevel:0 withBytes:rgba bytesPerRow:16];[overlay replaceRegion:MTLRegionMake2D(0,0,4,4) mipmapLevel:0 withBytes:ov bytesPerRow:16];
 EntityVertex vertices[6]={0};int corners[]={0,1,2,0,2,3};for(int i=0;i<6;i++){int c=corners[i];EntityVertex *v=&vertices[i];v->p[0]=c==1||c==2?6:-6;v->p[1]=c>=2?6:-6;v->p[2]=-8;v->color[0]=v->color[1]=v->color[2]=mode==2?.4:.8;v->color[3]=1;v->uv[0]=v->uv[1]=.5;v->light[0]=mode==5?240:0;v->light[1]=mode==6?0:240;v->normal[1]=1;}
 id<MTLBuffer> vb=buffer(ctx,vertices,sizeof vertices);MTLSamplerDescriptor *sd=[MTLSamplerDescriptor new];sd.minFilter=sd.magFilter=MTLSamplerMinMagFilterNearest;id<MTLSamplerState> sampler=[ctx->device newSamplerStateWithDescriptor:sd];
 id<MTLRenderCommandEncoder> r=enc->render;uint32_t emptyLight[64]={0};[r setFragmentBytes:emptyLight length:sizeof emptyLight atIndex:28];
 struct {simd_float4x4 model;simd_float3x3 normal;} model={.model=matrix_identity_float4x4,.normal=matrix_identity_float3x3};
 [r setVertexBytes:&model length:sizeof model atIndex:24];[r setRenderPipelineState:p];[r setCullMode:MTLCullModeNone];[r setFrontFacingWinding:MTLWindingClockwise];[r setVertexBuffer:vb offset:0 atIndex:27];inputs(r,vs,YES,tex,overlay,sampler,mode==2?.6:1,1);inputs(r,fs,NO,tex,overlay,sampler,mode==2?.6:1,1);
 if(mode==7)mco_sun_entity_bind(NULL,enc,1,nil,0);else{
  EntityFrame frame={.sun=sun};id<MTLTexture> map=mode==8?darkMap:litMap,maps[12];for(int i=0;i<4;i++){maps[i]=maps[4+i]=map;maps[8+i]=glassWhite;}
  [r setFragmentBytes:&lighting length:sizeof lighting atIndex:24];[r setFragmentBytes:&frame length:sizeof frame atIndex:25];[r setFragmentTextures:maps withRange:NSMakeRange(24,12)];
 }
 [r drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:6];id<MTLCommandBuffer> command=mc_enc_commit(enc);[command waitUntilCompleted];require(command.status==MTLCommandBufferStatusCompleted,command.error.localizedDescription);
 [target getBytes:pixel bytesPerRow:4 fromRegion:MTLRegionMake2D(32,32,1,1) mipmapLevel:0];[target release];[tex release];[overlay release];[vb release];[sampler release];[sd release];
}
int main(int argc,const char **argv){@autoreleasepool{
 require(argc==2,@"Usage: entity_lighting_check ENTITY_STAGES_DIR");NSString *dir=@(argv[1]);NSArray *rows=[NSJSONSerialization JSONObjectWithData:[NSData dataWithContentsOfFile:[dir stringByAppendingPathComponent:@"manifest.json"]] options:0 error:nil];require(rows.count>0,@"Missing generated stages");
 char error[16384]={0},name[256]={0};Ctx *ctx=mc_create(name,sizeof name,error,sizeof error);require(ctx!=NULL,@(error));Enc *enc=mc_enc_new(ctx);int checked=0;
 id<MTLCommandQueue> queue=[ctx->device newCommandQueue];litMap=shadowMap(ctx,queue,1);darkMap=shadowMap(ctx,queue,0);glassWhite=white(ctx);
 for(NSDictionary *row in rows){@autoreleasepool{
  NSString *stem=row[@"stem"],*v=readShader([dir stringByAppendingPathComponent:[stem stringByAppendingString:@".vs.metal"]]),*f=readShader([dir stringByAppendingPathComponent:[stem stringByAppendingString:@".fs.metal"]]);
  NSString *vv=readShader([dir stringByAppendingPathComponent:[stem stringByAppendingString:@"-vanilla.vs.metal"]]),*vf=readShader([dir stringByAppendingPathComponent:[stem stringByAppendingString:@"-vanilla.fs.metal"]]);
  id<MTLRenderPipelineState> lit=pipeline(ctx,v,f,MTLPixelFormatRGBA8Unorm),vanilla=pipeline(ctx,vv,vf,MTLPixelFormatRGBA8Unorm);
  Lighting l={.enabled=1,.exposure=1,.saturation=1,.contrast=1,.env={1,0,0,0}};l.p[0]=(simd_float4){1,.85,.58,2.4};l.p[1]=(simd_float4){1,.55,.28,2.2};l.p[2]=(simd_float4){.5,.66,1,.75};l.p[3]=(simd_float4){.64,.52,.62,.55};l.p[4]=(simd_float4){.46,.52,.85,.18};l.p[5]=(simd_float4){1,.6,.38,1};l.p[6]=(simd_float4){.02,.45,1,1};l.p[7].z=.45;l.p[17]=(simd_float4){.75,.7,1,.18};
  l.grade[0]=(simd_float4){1,1,1,1};l.grade[2]=(simd_float4){1,1,1,0};l.grade[3]=(simd_float4){1,1,1,1};
  Sun sun={.sun={0,.8,.6,1}};uint8_t original[4],off[4],day[4],night[4],tinted[4],hurt[4],torch[4],cave[4];
  Lighting disabled=l;disabled.enabled=0;render(ctx,enc,vanilla,vv,vf,l,sun,0,original);render(ctx,enc,lit,v,f,disabled,sun,0,off);require(!memcmp(original,off,4),[stem stringByAppendingString:@": disabled differs from vanilla"]);
  render(ctx,enc,lit,v,f,l,sun,7,off);require(!memcmp(original,off,4),@"No renderer did not bind disabled uniforms");
  render(ctx,enc,lit,v,f,l,sun,0,day);Sun ns=sun;ns.sun.w=.06;Lighting nl=l;nl.moon=1;nl.p[0].w=nl.p[1].w=0;render(ctx,enc,lit,v,f,nl,ns,0,night);
  render(ctx,enc,lit,v,f,l,sun,2,tinted);render(ctx,enc,lit,v,f,l,sun,3,hurt);render(ctx,enc,lit,v,f,l,sun,5,torch);render(ctx,enc,lit,v,f,l,sun,6,cave);
  require(day[0]+day[1]+day[2]>night[0]+night[1]+night[2]+20,[stem stringByAppendingString:@": moon did not dim"]);
  require(tinted[0]<day[0]-10,@"Vertex tint/modulator discarded");require(torch[0]>=day[0],@"Block light lost");require(cave[0]+cave[1]+cave[2]<day[0]+day[1]+day[2]-20,@"Skylight ignored in caves");
  require(abs((int)day[3]-original[3])<=1&&night[3]==day[3],@"World lighting changed alpha");
  // Shadows on: the object's position maps into the cascade (x,y /32, constant depth .5); one cascade, no glass.
  Sun shaded=sun;shaded.clip=(simd_float4x4){.columns={{1.f/32,0,0,0},{0,1.f/32,0,0},{0,0,0,0},{0,0,.5,1}}};shaded.config=(simd_float4){64,1.f/64,.0005f,1};
  uint8_t open[4],covered[4];render(ctx,enc,lit,v,f,l,shaded,9,open);render(ctx,enc,lit,v,f,l,shaded,8,covered);
  require(abs((int)open[0]-day[0])<=1&&abs((int)open[1]-day[1])<=1&&abs((int)open[2]-day[2])<=1,[stem stringByAppendingString:@": unoccluded shadow map changed the lit color"]);
  require(covered[0]+covered[1]+covered[2]<day[0]+day[1]+day[2]-20,[stem stringByAppendingString:@": occluding shadow map left direct sun"]);
  require(covered[3]==day[3],@"Shadows changed alpha");
  // Both dimensions use the same shared lighting inside the actual translated object shaders.
  Lighting nether=l;nether.sky[0].w=-1;nether.sky[1]=(simd_float4){.4,.14,.06,128};Sun noSun=sun;noSun.sun.w=0;
  uint8_t n[4],nt[4],e[4];render(ctx,enc,lit,v,f,nether,noSun,0,n);render(ctx,enc,lit,v,f,nether,noSun,5,nt);
  require(n[0]>n[2]&&nt[0]>n[0],@"Nether entity ambient/block light missing");
  Lighting end=l;end.sky[0].w=1;end.sky[1]=(simd_float4){.075,.08,.105,128};Sun endSun=sun;endSun.sun.w=.8;
  render(ctx,enc,lit,v,f,end,endSun,0,e);require(e[0]>e[2]&&e[1]>n[1]&&e[3]==day[3],@"End entity light/alpha missing");
  if([f containsString:@"in.overlayColor"])require(hurt[0]>hurt[1],@"Hurt overlay lost");
  for(NSNumber *fmt in @[@(MTLPixelFormatBGRA8Unorm),@(MTLPixelFormatRGBA8Unorm_sRGB),@(MTLPixelFormatRGBA16Float)]){id<MTLRenderPipelineState> extra=pipeline(ctx,v,f,fmt.unsignedIntegerValue);[extra release];}
  printf("PASS %s day %u,%u,%u shadowed %u,%u,%u night %u,%u,%u alpha %u\n",stem.UTF8String,day[0],day[1],day[2],covered[0],covered[1],covered[2],night[0],night[1],night[2],day[3]);fflush(stdout);[lit release];[vanilla release];checked++;
 }}printf("PASS: %d actual shader variants, vanilla parity, renderer-absent fallback, day/moon/cave/block light, sun shadows received, tint, overlays, alpha, Nether/End light, and four target formats\n",checked);
}return 0;}
