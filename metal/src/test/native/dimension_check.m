// Production Metal functions, compiled and rendered on the GPU. No Minecraft window.
#include "sun_test_common.h"
typedef struct {uint32_t enabled,srgb;float exposure,saturation,contrast,moon,pad[2];simd_float4 env,p[19],hand[3],materials[4],aurora[3],grade[4],celestial[14],sky[4];} Lighting;
_Static_assert(sizeof(Lighting)==864,"Dimension lighting ABI");
static Ctx *ctx;static id<MTLRenderPipelineState> pipeline;static id<MTLCommandQueue> queue;static id<MTLTexture> target;
static Lighting base(int dimension){
 Lighting l={.enabled=1,.exposure=1,.saturation=1,.contrast=1,.env={1,0,0,1}};
 l.p[5]=(simd_float4){1,.6,.38,1};l.p[6]=(simd_float4){.02,.45,1,0};l.p[16]=(simd_float4){.003,.02,1,3};
 l.sky[0].w=dimension;l.sky[1]=(simd_float4){dimension<0?.4:.075,dimension<0?.14:.08,dimension<0?.06:.105,128};l.sky[2]=(simd_float4){1,0,0,0};l.aurora[2].w=64;if(dimension>0){l.sky[0].x=.0707f;l.sky[0].y=.0323f;l.sky[0].z=.1899f;l.sky[3]=(simd_float4){128,.2576f,.0707f,.0094f};l.aurora[0].y=1;l.aurora[0].w=1;l.p[14]=(simd_float4){1,1,0,0};l.aurora[1]=(simd_float4){68,1,1,.3f};l.aurora[2].x=1;l.aurora[2].y=1;l.aurora[2].z=1;}
 return l;
}
static simd_float4 probe(Lighting l,Sun sun,simd_float4 u){
 id<MTLCommandBuffer> c=[queue commandBuffer];MTLRenderPassDescriptor *rp=[MTLRenderPassDescriptor renderPassDescriptor];rp.colorAttachments[0].texture=target;rp.colorAttachments[0].loadAction=MTLLoadActionClear;rp.colorAttachments[0].storeAction=MTLStoreActionStore;
 id<MTLRenderCommandEncoder> r=[c renderCommandEncoderWithDescriptor:rp];[r setRenderPipelineState:pipeline];[r setFragmentBytes:&l length:sizeof l atIndex:0];[r setFragmentBytes:&sun length:sizeof sun atIndex:1];[r setFragmentBytes:&u length:sizeof u atIndex:2];float fog[10]={.4,.1,.02,1,0,u.z,0,1000,1000,1000};[r setFragmentBytes:fog length:sizeof fog atIndex:3];[r drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];[r endEncoding];[c commit];[c waitUntilCompleted];require(c.status==MTLCommandBufferStatusCompleted,c.error.localizedDescription);
 simd_float4 px;[target getBytes:&px bytesPerRow:16 fromRegion:MTLRegionMake2D(0,0,1,1) mipmapLevel:0];for(int i=0;i<4;i++)require(isfinite(px[i]),@"Nonfinite dimension output");return px;
}
// The fog's own clouds step about 0.009 along these walks; drawing the planet's limb on terrain stepped 0.048.
#define AIR_MAX_STEP .02f
static float luma(simd_float4 c){return c.x*.2126f+c.y*.7152f+c.z*.0722f;}
int main(int argc,const char **argv){@autoreleasepool{
 require(argc==3,@"Usage: dimension_check TERRAIN SUN");
 NSString *source=[[NSString stringWithContentsOfFile:@(argv[1]) encoding:NSUTF8StringEncoding error:NULL] stringByAppendingString:[NSString stringWithContentsOfFile:@(argv[2]) encoding:NSUTF8StringEncoding error:NULL]];
 source=[source stringByAppendingString:@"\nfragment float4 dimension_probe_fs(constant SunLighting &l[[buffer(0)]],constant SunFrame &sun[[buffer(1)]],constant float4 &u[[buffer(2)]],constant Fog &f[[buffer(3)]]){if(u.x>5.5){float3 v=float3(cos(u.w)*cos(u.y),sin(u.w),cos(u.w)*sin(u.y));SunFog air=sunFog(0,v*120.0,1,f,sun,l);return float4(air.radiance,air.transmittance);}if(u.x>4.5){SunEndAir air=sunEndFog(normalize(u.yzw),length(u.yzw),sunEndLight(sun,l),l);return float4(1.0-air.transmittance,air.scatter);}if(u.x>3.5)return float4(sunEndBackdrop(normalize(u.yzw),normalize(u.yzw),.001,sunEndLight(sun,l),l),1);if(u.x>2.5){SunEndLayer planet=sunEndPlanet(normalize(u.yzw),.001,sunEndLight(sun,l),l.sky[2].z);return float4(planet.radiance,planet.alpha);}if(u.x<.5)return float4(sunTerrainLight(float3(1),u.y,0,float3(0,1,0),float3(1),float3(0,0,4),sun,l),1);if(u.x<1.5){float3 p=float3(u.y,0,0);SunFog air=sunFog(.2,p,1,f,sun,l);return float4(air.radiance,air.transmittance);}return float4(sunEndSky(normalize(u.yzw),.001,sun,l),1);}\n"];
 char name[256],err[16384];ctx=mc_create(name,sizeof name,err,sizeof err);require(ctx!=NULL,@(err));queue=[ctx->device newCommandQueue];
 NSError *issue=nil;MTLCompileOptions *o=[MTLCompileOptions new];o.languageVersion=MTLLanguageVersion3_0;id<MTLLibrary> lib=[ctx->device newLibraryWithSource:source options:o error:&issue];require(lib!=nil,issue.localizedDescription);
 MTLRenderPipelineDescriptor *pd=[MTLRenderPipelineDescriptor new];pd.vertexFunction=[lib newFunctionWithName:@"sun_screen_vs"];MTLFunctionConstantValues *fc=[MTLFunctionConstantValues new];bool off=false;[fc setConstantValue:&off type:MTLDataTypeBool atIndex:60];[fc setConstantValue:&off type:MTLDataTypeBool atIndex:61];[fc setConstantValue:&off type:MTLDataTypeBool atIndex:62];pd.fragmentFunction=[lib newFunctionWithName:@"dimension_probe_fs" constantValues:fc error:&issue];require(pd.fragmentFunction!=nil,issue.localizedDescription);pd.colorAttachments[0].pixelFormat=MTLPixelFormatRGBA32Float;pipeline=[ctx->device newRenderPipelineStateWithDescriptor:pd error:&issue];require(pipeline!=nil,issue.localizedDescription);
 MTLTextureDescriptor *d=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA32Float width:1 height:1 mipmapped:NO];d.storageMode=MTLStorageModeShared;d.usage=MTLTextureUsageRenderTarget;target=[ctx->device newTextureWithDescriptor:d];
 Sun day={.sun={0,1,0,1}},night={.sun={0,-1,0,0}};
 Lighting nether=base(-1),end=base(1);simd_float4 n=probe(nether,day,(simd_float4){0}),nn=probe(nether,night,(simd_float4){0});
 require(simd_distance(n,nn)<1e-6&&luma(n)>.025,@"Nether disabled / follows hidden sun");
 simd_float4 torch=probe(nether,night,(simd_float4){0,1,0,0});require(luma(torch)>luma(n)*2,@"Nether block light missing");
 Lighting soul=nether;soul.sky[1]=(simd_float4){.1,.24,.24,128};simd_float4 so=probe(soul,night,(simd_float4){0});require(so.z>n.z&&so.x<n.x,@"Nether biome atmosphere missing");
 simd_float4 e=probe(end,day,(simd_float4){0}),en=probe(end,night,(simd_float4){0});require(e.x>e.z&&luma(e)>luma(en)*1.3&&luma(en)>.05,@"End direct/ambient light missing at zero skylight");
 for(int dim=-1;dim<=1;dim+=2){Lighting l=base(dim);simd_float4 near=probe(l,day,(simd_float4){1,32,1000,0}),far=probe(l,day,(simd_float4){1,120,1000,0});require(far.w<near.w&&far.w<.6,@"Dimension distance fog missing");
  l.env.z=1;simd_float4 fluid=probe(l,day,(simd_float4){1,120,1000,0});require(fabsf(fluid.w-.8f)<1e-5&&fabsf(fluid.x-powf(.4,2.2))<1e-5,@"Dimension atmosphere overwrites fluid fog");
  l.env.z=0;simd_float4 blind=probe(l,day,(simd_float4){1,120,6,0});require(fabsf(blind.w-.8f)<1e-5,@"Dimension atmosphere overwrites status fog");
 }
 simd_float4 directions[]={{2,0,1,0},{2,0,-1,0},{2,1,0,0},{2,-1,0,0},{2,0,0,1},{2,0,0,-1}};
 Lighting clearSky=base(1);clearSky.sky[2].x=0;for(int i=0;i<6;i++){simd_float4 sky=probe(clearSky,day,directions[i]);require(sky.z>sky.y&&luma(sky)>.001,@"End sky missing at pole/horizon");}
 // The black hole stands on the light vector: a black center, a bright rim and disc, and nothing where it is switched off.
 Sun slanted={.sun={.9135f,.4067f,0,.8f}},noLight={.sun={0,0,0,0}};
 #define AT(angle) (simd_float4){2,sinf(1.152f+(angle)),cosf(1.152f+(angle)),0}
 Lighting holeOnly=base(1),neither=base(1),planetOnly=base(1),both=base(1);neither.sky[2].x=0;planetOnly.sky[2]=(simd_float4){0,1,0,0};both.sky[2]=(simd_float4){1,1,0,0};
 require(luma(probe(holeOnly,slanted,AT(0)))<.001f,@"Black hole has no shadow");
 require(luma(probe(both,slanted,AT(0)))<.001f,@"Planet shows through the black hole");
 float rim=luma(probe(holeOnly,slanted,AT(2.66f/32))),disc=luma(probe(holeOnly,slanted,AT(3.8f/32)));
 require(rim>.3f&&disc>.1f,@"Black hole has no rim or disc");
 require(luma(probe(neither,slanted,AT(3.8f/32)))<disc*.1f,@"Black hole drawn while switched off");
 require(luma(probe(holeOnly,noLight,AT(0)))<.001f&&isfinite(luma(probe(holeOnly,noLight,AT(.2f)))),@"Black hole lost without a light vector");
 // Its size, the disc's brightness and the stars follow their settings.
 Lighting biggerHole=holeOnly;biggerHole.aurora[0].w=2;
 // Above the center: below it the disc passes in front of the shadow.
 require(luma(probe(biggerHole,slanted,AT(-3.5f/32)))<.001f&&luma(probe(holeOnly,slanted,AT(-3.5f/32)))>.05f,@"Black hole size setting ignored");
 Lighting dimDisc=holeOnly,brightDisc=holeOnly;dimDisc.p[14].x=0;brightDisc.p[14].x=2;
 require(luma(probe(brightDisc,slanted,AT(3.8f/32)))>disc*1.5f&&luma(probe(dimDisc,slanted,AT(3.8f/32)))<disc*.1f,@"Disc brightness setting ignored");
 {Lighting noStars=neither;noStars.p[14].y=0;float withStars=0,without=0;for(int y=0;y<90;y++)for(int x=0;x<90;x++){simd_float4 u={2,-.8f+x*.0006f,.6f,y*.0006f};withStars=fmaxf(withStars,luma(probe(neither,slanted,u)));without=fmaxf(without,luma(probe(noStars,slanted,u)));}require(withStars>without*1.5f,@"Star brightness setting ignored");}
 // Far from the hole only a trace of its scattered light remains.
 simd_float4 far={2,-.9135f,-.4067f,0};
 require(simd_distance(probe(holeOnly,slanted,far),probe(neither,slanted,far))<.001f,@"Black hole changes the far sky");
 // The planet is a separate switch, in either state of the hole, and its rings fill the sky below their plane.
 simd_float4 towardPlanet={2,.4074f,.3224f,-.8547f};
 for(int hole=0;hole<2;hole++){Lighting off=hole?holeOnly:neither,on=hole?both:planetOnly;
  require(luma(probe(on,slanted,towardPlanet))>luma(probe(off,slanted,towardPlanet))*5,@"Planet switch has no effect");}
 float ringsOn=0,ringsOff=0;
 for(int i=0;i<32;i++){float a=i*6.2831853f/32;simd_float3 down=simd_normalize((simd_float3){cosf(a),-.35f,sinf(a)});simd_float4 u={2,down.x,down.y,down.z};ringsOn+=luma(probe(planetOnly,slanted,u));ringsOff+=luma(probe(neither,slanted,u));}
 require(ringsOn>ringsOff*5,@"Planet rings missing");
 // Independent alpha-over oracle for pure ring pixels. Half opacity must carry half the energy,
 // not one quarter; the returned premultiplied layer must compose onto both dark and star-lit backgrounds once.
 Lighting opaque=planetOnly,half=planetOnly,invisible=planetOnly;opaque.sky[2].z=0;half.sky[2].z=.5;invisible.sky[2].z=1;
 int ringPixels=0;simd_float3 center={.7726f,.6114f,-1.6263f};
 for(int i=0;i<64;i++){
  float a=i*6.2831853f/64;simd_float3 d=simd_normalize((simd_float3){cosf(a),-.35f,sinf(a)});
  float along=simd_dot(center,d),miss=sqrtf(fmaxf(simd_dot(center,center)-along*along,0));
  if(along>0&&miss<1.05f)continue;
  simd_float4 u={3,d.x,d.y,d.z};simd_float4 full=probe(opaque,slanted,u);
  if(full.w<.05f)continue;ringPixels++;
  simd_float4 middle=probe(half,slanted,u),empty=probe(invisible,slanted,u);
  require(simd_distance(middle,full*.5f)<2e-5,@"Ring transparency does not scale alpha and emitted energy linearly");
  require(simd_length(empty)<1e-6,@"100% transparent rings still cover the sky");
  u.x=4;simd_float4 background=probe(neither,slanted,u),composed=probe(half,slanted,u);
  simd_float3 expected=background.xyz*(1-middle.w)+middle.xyz;
  require(simd_distance(composed.xyz,expected)<2e-5,@"Ring alpha applied twice when compositing onto sky");
 }
 require(ringPixels>3,@"Insufficient pure ring coverage for alpha regression");
 simd_float4 planetSurface=towardPlanet;planetSurface.x=3;
 require(probe(invisible,slanted,planetSurface).w>.99f,@"Ring transparency also hides the planet");
 Lighting otherHole=holeOnly;otherHole.sky[2].z=1;
 require(simd_distance(probe(otherHole,slanted,AT(.12f)),probe(holeOnly,slanted,AT(.12f)))<1e-6,@"Ring transparency changes the black hole");
 require(simd_distance(probe(invisible,slanted,(simd_float4){0}),probe(opaque,slanted,(simd_float4){0}))<1e-6,@"Ring transparency changes world lighting");
 // End fog: a cloudy layer along the view ray. Averages over the compass, since any one direction may meet a gap in the clouds.
 Lighting fogged=neither;fogged.sky[2].w=1;fogged.sky[3].x=128;fogged.aurora[2].w=64;
 float (^fogAt)(Lighting,float)=^float(Lighting from,float reach){float sum=0;for(int i=0;i<24;i++){float a=i*6.2831853f/24;sum+=probe(from,slanted,(simd_float4){5,cosf(a)*reach,.02f*reach,sinf(a)*reach}).x;}return sum/24;};
 float fogNear=fogAt(fogged,8),fogMid=fogAt(fogged,256),fogSky=fogAt(fogged,1100);
 require(fogNear==0&&fogMid>.02f&&fogSky>.12f,@"End fog obscures near surfaces or fails to build in the distance");
 float previousFog=0;
 for(int reach=0;reach<=1100;reach+=50){float amount=fogAt(fogged,MAX(reach,1));require(amount>=previousFog-.02f&&amount<=1,@"End fog does not build with distance");previousFog=amount;}
 Lighting highFog=fogged;highFog.aurora[2].w=500;require(fogAt(highFog,256)<fogMid*.3f,@"End fog has no height falloff");
 Lighting shortFog=fogged,longFog=fogged;shortFog.sky[3].x=48;longFog.sky[3].x=512;
 require(fogAt(shortFog,128)>fogAt(longFog,128)*2,@"End fog distance slider ineffective");
 Lighting strongFog=fogged,offFog=fogged;strongFog.sky[2].w=2;offFog.sky[2].w=0;
 require(fogAt(strongFog,256)>fogMid*1.5f&&fogAt(offFog,1100)==0,@"End fog strength/zero endpoint ineffective");
 // Clouds, not an even veil and not the old web of filaments: varied across the sky, gently changing, no horizon mirror.
 float fogLeast=1,fogMost=0,fogSteepest=0,fogLast=0,fogMirror=0;
 for(int i=0;i<=720;i++){float a=i*6.2831853f/720;float now=probe(fogged,slanted,(simd_float4){5,cosf(a)*1100,44,sinf(a)*1100}).x;fogLeast=fminf(fogLeast,now);fogMost=fmaxf(fogMost,now);if(i>0)fogSteepest=fmaxf(fogSteepest,fabsf(now-fogLast));fogLast=now;if(i%16==0)fogMirror+=fabsf(now-probe(fogged,slanted,(simd_float4){5,cosf(a)*1100,-44,sinf(a)*1100}).x);}
 printf("End fog: %.3f..%.3f across the compass, steepest step %.4f per half degree, mirror difference %.3f\n",fogLeast,fogMost,fogSteepest,fogMirror);
 require(fogMost-fogLeast>.08f,@"End fog is an even veil");require(fogSteepest<.04f,@"End fog has thin filaments");require(fogMirror>.05f,@"End fog mirrors across the horizon");
 // Its look: each control does what it says.
 simd_float4 (^fogSum)(Lighting)=^simd_float4(Lighting from){simd_float4 sum={0,0,0,0};for(int i=0;i<32;i++){float a=i*6.2831853f/32;sum+=probe(from,slanted,(simd_float4){5,cosf(a)*1100,44,sinf(a)*1100});}return sum/32;};
 simd_float4 fogBase=fogSum(fogged);
 Lighting red=fogged;red.sky[0].x=.6f;red.sky[0].y=.02f;red.sky[0].z=.02f;simd_float4 redSum=fogSum(red);require(redSum.y>redSum.w*2&&fogBase.w>fogBase.y,@"End fog color ignored");
 Lighting doubled=fogged;doubled.aurora[2].y=2;simd_float4 doubledSum=fogSum(doubled);require(fabsf(doubledSum.x-fogBase.x)<1e-5&&fabsf(doubledSum.w-fogBase.w*2)<fogBase.w*.01f,@"End fog brightness changes density or does not scale light");
 Lighting raisedEye=fogged;raisedEye.aurora[2].w=300;Lighting raisedBoth=raisedEye;raisedBoth.aurora[1].x=300;require(fogAt(raisedBoth,256)>fogAt(raisedEye,256)*3,@"End fog height ignored");
 Lighting thin=raisedEye,thick=raisedEye;thin.aurora[1].y=.25f;thick.aurora[1].y=3;require(fogAt(thick,256)>fogAt(thin,256)*2,@"End fog thickness ignored");
 Lighting large=fogged;large.aurora[1].z=4;Lighting smooth=fogged;smooth.aurora[1].w=0;simd_float4 u0={5,700,20,300};require(simd_distance(probe(large,slanted,u0),probe(fogged,slanted,u0))>1e-3f,@"End fog cloud size ignored");
 {float changed=0;for(int i=0;i<32;i++){float a=i*6.2831853f/32;simd_float4 near={5,cosf(a)*400,8,sinf(a)*400};changed+=fabsf(probe(smooth,slanted,near).x-probe(fogged,slanted,near).x);}require(changed>.01f,@"End fog wisps ignored");}
 Lighting later=fogged,frozen=fogged,frozenLater=fogged;later.p[18].x=4000;frozen.aurora[2].x=0;frozenLater.aurora[2].x=0;frozenLater.p[18].x=4000;
 require(simd_distance(fogSum(later),fogBase)>1e-4f&&simd_distance(fogSum(frozen),fogSum(frozenLater))<1e-6f,@"End fog speed ignored");
 Lighting noEmber=fogged;noEmber.aurora[2].z=0;require(fogSum(noEmber).y<fogBase.y&&fabsf(fogSum(noEmber).x-fogBase.x)<1e-5f,@"End fog ember ignored");
 Lighting holeFog=fogged,holeFogOff=fogged;holeFog.sky[2].x=1;holeFogOff.sky[2].x=1;holeFogOff.aurora[0].y=0;
 {float warm=0,plain=0;for(int i=0;i<16;i++){float a=i*.05f-.4f;simd_float4 u={5,cosf(a)*1100,44,sinf(a)*1100};warm+=probe(holeFog,slanted,u).y;plain+=probe(holeFogOff,slanted,u).y;}require(warm>plain*1.2f,@"Black hole light on the fog ignored");}
 // In the sky: visible on the horizon, leaving the high sky and the black hole's shadow dark.
 Lighting foggedHole=holeOnly;foggedHole.sky[2].w=1;foggedHole.sky[3].x=128;foggedHole.aurora[2].w=64;
 float fogHorizon=0,clearHorizon=0;for(int i=0;i<24;i++){float a=i*6.2831853f/24;simd_float4 u={2,cosf(a),.03f,sinf(a)};fogHorizon+=luma(probe(fogged,slanted,u));clearHorizon+=luma(probe(offFog,slanted,u));}
 require(fogHorizon>clearHorizon*1.5f,@"End sky lacks the fog on its horizon");
 require(luma(probe(foggedHole,slanted,AT(0)))<.03f,@"Fog washes out the black hole's shadow");
 // The fog stays mostly in the empty sky: a dense layer around the hole hardly touches its shadow or the planet.
 Lighting denseHole=foggedHole;denseHole.sky[2].w=2;denseHole.aurora[1].x=120;denseHole.aurora[1].y=3;denseHole.aurora[2].y=3;
 require(luma(probe(denseHole,slanted,AT(0)))<.08f,@"Dense fog washes out the black hole's shadow");
 Lighting densePlanet=denseHole;densePlanet.sky[2].x=0;densePlanet.sky[2].y=1;Lighting clearPlanet=densePlanet;clearPlanet.sky[2].w=0;
 require(simd_distance(probe(densePlanet,slanted,towardPlanet),probe(clearPlanet,slanted,towardPlanet))<luma(probe(clearPlanet,slanted,towardPlanet))*.5f,@"Dense fog washes out the planet");
 // Terrain takes the same fog: a surface far along a ray is fogged, a near one stays exactly as it was.
 simd_float4 surfaceFar={1,256,1000,0},surfaceNear={1,8,1000,0};
 require(probe(fogged,slanted,surfaceFar).w<=probe(offFog,slanted,surfaceFar).w&&fabsf(probe(fogged,slanted,surfaceNear).w-probe(offFog,slanted,surfaceNear).w)<1e-6,@"End fog on terrain");
 // Fog on terrain has no shapes from the sky: nothing a body draws may show on what stands in front of it. Walks the fog's
 // color over a surface at 120 blocks across the planet's limb, the rings' horizon, the disc's streak and the hole itself.
 Lighting everything=both;everything.sky[2].w=1;everything.sky[3].x=128;everything.aurora[2].w=64;everything.grade[0]=(simd_float4){1,1,1,1};everything.grade[2]=(simd_float4){1,1,1,0};everything.grade[3]=(simd_float4){1,1,1,1};
 float airJump=0;
 for(int k=0;k<8;k++){float previous[4]={0,0,0,0};for(int i=0;i<720;i++){float az=i*6.2831853f/720,el=-.6f+k*.24f;simd_float4 c=probe(everything,slanted,(simd_float4){6,az,1000,el});if(i>0)for(int n=0;n<3;n++)airJump=fmaxf(airJump,fabsf(c[n]-previous[n]));for(int n=0;n<3;n++)previous[n]=c[n];}}
 for(int k=0;k<12;k++){float previous[4]={0,0,0,0};for(int i=0;i<360;i++){float az=k*6.2831853f/12-1.3f,el=-.9f+i*.005f;simd_float4 c=probe(everything,slanted,(simd_float4){6,az,1000,el});if(i>0)for(int n=0;n<3;n++)airJump=fmaxf(airJump,fabsf(c[n]-previous[n]));for(int n=0;n<3;n++)previous[n]=c[n];}}
 {float previous[4]={0,0,0,0};for(int i=0;i<400;i++){float a=-.5f+i*.0025f;simd_float3 v=simd_normalize((simd_float3){.9135f*cosf(a)-0,.4067f,.9135f*sinf(a)});simd_float4 c=probe(everything,slanted,(simd_float4){6,atan2f(v.z,v.x),1000,asinf(v.y)});if(i>0)for(int n=0;n<3;n++)airJump=fmaxf(airJump,fabsf(c[n]-previous[n]));for(int n=0;n<3;n++)previous[n]=c[n];}}
 printf("End air on terrain: steepest color step %.5f\n",airJump);
 require(airJump<AIR_MAX_STEP,@"Sky shapes show through terrain");
 Lighting protected=fogged;protected.env.z=1;require(fabsf(probe(protected,slanted,(simd_float4){1,256,1000,0}).w-.8f)<1e-6,@"End fog overwrites fluid fog");
 protected.env.z=0;require(fabsf(probe(protected,slanted,(simd_float4){1,256,6,0}).w-.8f)<1e-6,@"End fog overwrites status fog");
 require(simd_distance(probe(fogged,slanted,(simd_float4){0}),probe(offFog,slanted,(simd_float4){0}))<1e-6,@"End fog changes direct world light");
 // Every direction around both bodies stays finite (probe), including the traced window's edge.
 for(int i=0;i<64;i++){probe(both,slanted,AT(i*.012f));probe(both,slanted,(simd_float4){2,.9135f*cosf(i*.01f),.4067f,sinf(i*.01f)});}
 #undef AT
 printf("PASS: GPU Nether light without sun, block light, biome atmosphere; End direct/ambient at zero skylight, fog distance, fluid/status preservation, finite End sky poles/horizon, End black hole on the light vector, separate black hole and planet switches, cloudy End fog along the view ray, no sky shapes on terrain, ring transparency endpoints/linear energy/alpha-over/planet isolation; End fog distance/strength/height/reciprocity/sky veil/fluid-status guards\n");
}return 0;}
