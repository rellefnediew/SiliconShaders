// Track a single solid shadow edge with the exact Java projection, without changing the running game.
#include "sun_test_common.h"
extern id<MTLTexture> mco_sun_debug_map(void *);
extern int mco_sun_finish(void *,Enc *,char *,int);

enum { WIDTH=1024, HEIGHT=256 };
static void saveImage(NSString *path,uint8_t *rgba) {
    FILE *file=fopen(path.UTF8String,"wb");require(file!=NULL,@"Cannot save mask image");
    fprintf(file,"P6\n%d %d\n255\n",WIDTH,HEIGHT);
    for(int pixel=0;pixel<WIDTH*HEIGHT;pixel++) fwrite(rgba+pixel*4,1,3,file);
    fclose(file);
}
static float crossing(const float *pixels,int row,int a,int b,float threshold,BOOL falling) {
    for(int x=a;x<b;x++) {
        float v=1.0f-pixels[(row*WIDTH+x)*4];
        float next=1.0f-pixels[(row*WIDTH+x+1)*4];
        if(falling?(v>=threshold&&next<threshold):(v<threshold&&next>=threshold))
            return x+0.5f+(threshold-v)/(next-v);
    }
    return NAN;
}
static void runCase(Ctx *ctx,void *own,Enc *enc,NSString *input,NSString *directory) {
    NSData *frames=[NSData dataWithContentsOfFile:input];require(frames.length>=32,@"Missing solar frames");
    const uint32_t *header=frames.bytes;uint32_t count=header[1],size=header[2],packetBytes=header[6]?header[6]:144;
    require(header[0]==0x31474445 && (packetBytes==144||packetBytes==464||packetBytes==256||(packetBytes==400||packetBytes==688)) && frames.length==32+count*(packetBytes+4),@"Invalid solar frame file");
    NSString *name=input.lastPathComponent.stringByDeletingPathExtension;
    NSString *prefix=[directory stringByAppendingPathComponent:name];
    FILE *csv=fopen([prefix stringByAppendingString:@".csv"].UTF8String,"w");require(csv!=NULL,@"Cannot save edge trace");
    fprintf(csv,"frame,angle,ideal_left_world,ideal_right_world,left_world,right_world,centroid_world,peak_shadow,light_texel_left,light_texel_right\n");
    FILE *profiles=fopen([prefix stringByAppendingString:@"-profiles.bin"].UTF8String,"wb");
    FILE *floatProfiles=fopen([prefix stringByAppendingString:@"-float.bin"].UTF8String,"wb");require(floatProfiles!=NULL,@"Cannot save float profiles");
    FILE *depths=fopen([prefix stringByAppendingString:@"-depth.bin"].UTF8String,"wb");require(profiles&&depths,@"Cannot save profiles");
    uint32_t profileHeader[]={count,WIDTH},depthHeader[]={count,size};fwrite(profileHeader,4,2,profiles);fwrite(profileHeader,4,2,floatProfiles);fwrite(depthHeader,4,2,depths);

    float casterHeight=header[7]?1:2;
    Vertex vertices[8]={0};
    for(int q=0;q<2;q++) for(int c=0;c<4;c++) {
        Vertex *v=&vertices[q*4+c];float radius=q?0.4:4;
        v->p[0]=(c==1||c==2)?radius:-radius;v->p[1]=q?casterHeight:0;v->p[2]=((c>=2)?radius:-radius)+(q?1.5:0);
        memset(v->color,255,4);v->light[0]=v->light[1]=240;v->uv[0]=v->uv[1]=0.5;
    }
    Section section={.x=(int)header[3],.y=(int)header[4]-8,.z=(int)header[5],.count=2};
    Record records[2]={{0},{.start=1}};uint32_t lists[2]={0,1},args[5]={384,header[7]?1:2,0,0,0};
    id<MTLBuffer> arena=buffer(ctx,vertices,sizeof vertices),sections=buffer(ctx,&section,sizeof section),recs=buffer(ctx,records,sizeof records);
    id<MTLBuffer> list=buffer(ctx,lists,sizeof lists),draw=buffer(ctx,args,sizeof args);
    simd_float4x4 projection={.columns={{0.2,0,0,0},{0,0,0.1,0},{0,0.2,0,0},{0,0,1.2,1}}};
    uint8_t globals[48]={0},terrain[80]={0};memcpy(globals,header+3,12);
    simd_float4x4 identity=matrix_identity_float4x4;memcpy(terrain,&identity,64);int textureSize[2]={4,4};memcpy(terrain+64,textureSize,8);
    float fog[10]={0,0,0,0,1000,2000,1000,2000,1000,1000};
    id<MTLBuffer> p=buffer(ctx,&projection,64),g=buffer(ctx,globals,sizeof globals),t=buffer(ctx,terrain,sizeof terrain),f=buffer(ctx,fog,sizeof fog);
    int64_t uniforms[8]={(int64_t)p,0,(int64_t)g,0,(int64_t)t,0,(int64_t)f,0};
    id<MTLTexture> atlas=white(ctx),light=white(ctx);
    MTLSamplerDescriptor *sd=[[[MTLSamplerDescriptor alloc] init] autorelease];sd.minFilter=sd.magFilter=MTLSamplerMinMagFilterNearest;
    id<MTLSamplerState> sampler=[ctx->device newSamplerStateWithDescriptor:sd];
    MTLTextureDescriptor *td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA32Float width:WIDTH height:HEIGHT mipmapped:NO];
    td.storageMode=MTLStorageModeShared;td.usage=MTLTextureUsageRenderTarget;id<MTLTexture> color=[ctx->device newTextureWithDescriptor:td];
    td.pixelFormat=MTLPixelFormatDepth32Float;td.storageMode=MTLStorageModePrivate;id<MTLTexture> depth=[ctx->device newTextureWithDescriptor:td];
    id<MTLBuffer> readback=[ctx->device newBufferWithLength:size*4 options:MTLResourceStorageModeShared];
    float *pixels=malloc(WIDTH*HEIGHT*4*sizeof(float));uint8_t *image=malloc(WIDTH*HEIGHT*4);require(pixels!=NULL,@"Image allocation failed");
    int row=(int)((1.5f*0.1f+0.5f)*HEIGHT),start=(int)((header[7]?-1.5f:0.55f)*WIDTH*0.1f+WIDTH*0.5f),end=(int)(3.9f*WIDTH*0.1f+WIDTH*0.5f);
    char error[4096]={0};float firstLeft=NAN,lastLeft=NAN,maxBack=0;int backwards=0;
    float previousFrozen[WIDTH]={0},largestProfileStep=0,frozenStep=0,frozenLow=1e30f,frozenHigh=-1e30f;
    for(uint32_t frame=0;frame<count;frame++) @autoreleasepool {
        Sun parameters;float angle;
        const uint8_t *at=(const uint8_t *)frames.bytes+32+frame*(packetBytes+4);memcpy(&parameters,at,144);memcpy(&angle,at+packetBytes,4);
        id<MTLTexture> targets[]={color};float clear[]={1,1,1,1,1};mc_render_begin(enc,1,targets,clear,depth,1,0,WIDTH,HEIGHT);
        require(mco_sun_frame(own,enc,at,packetBytes,arena,sections,recs,atlas,sampler,error,sizeof error)>=0,[NSString stringWithUTF8String:error]);
        uint8_t drawFrame[80]={0};*(int32_t *)(drawFrame+4)=1;
        require(mco_draw(own,enc,0,drawFrame,sizeof drawFrame,arena,sections,recs,list,draw,0,uniforms,atlas,sampler,light,sampler,nil,error,sizeof error)>0,[NSString stringWithUTF8String:error]);
        require(mco_sun_finish(own,enc,error,sizeof error)>=0,[NSString stringWithUTF8String:error]);
        id<MTLCommandBuffer> render=mc_enc_commit(enc);
        id<MTLTexture> map=mco_sun_debug_map(own);require(map.width==size,@"Incorrect shadow map size");
        simd_float4x4 mapClip=parameters.clip;if(packetBytes==464)memcpy(&mapClip,at+144,64);
        simd_float4 center=simd_mul(mapClip,(simd_float4){0,casterHeight-8,1.5,1});
        int depthRow=MAX(0,MIN((int)size-1,(int)((center.y*0.5f+0.5f)*size)));
        id<MTLCommandBuffer> copy=[ctx->queue commandBuffer];id<MTLBlitCommandEncoder> blit=[copy blitCommandEncoder];
        [blit copyFromTexture:map sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0,depthRow,0) sourceSize:MTLSizeMake(size,1,1)
            toBuffer:readback destinationOffset:0 destinationBytesPerRow:size*4 destinationBytesPerImage:size*4];
        [blit endEncoding];[copy commit];[copy waitUntilCompleted];
        require(copy.status!=MTLCommandBufferStatusError,copy.error.localizedDescription);
        require(render.status!=MTLCommandBufferStatusError,render.error.localizedDescription);[render release];
        [color getBytes:pixels bytesPerRow:WIDTH*4*sizeof(float) fromRegion:MTLRegionMake2D(0,0,WIDTH,HEIGHT) mipmapLevel:0];
        float peak=0,weight=0,weighted=0;
        for(int x=start;x<end;x++) { float dark=1.0f-pixels[(row*WIDTH+x)*4];peak=MAX(peak,dark);weight+=dark;weighted+=dark*(x+0.5f); }
        require(peak>0.1f,@"Shadow missing from measured row");
        float left=crossing(pixels,row,start,end,peak*0.5f,NO),right=crossing(pixels,row,start,end,peak*0.5f,YES);
        require(isfinite(left)&&isfinite(right),@"Could not find both shadow edges");
        float leftWorld=(left/WIDTH-0.5f)*10,rightWorld=(right/WIDTH-0.5f)*10,centroidWorld=(weighted/weight/WIDTH-0.5f)*10;
        simd_float4 l=simd_mul(parameters.clip,(simd_float4){-0.4,casterHeight-8,1.5,1}),r=simd_mul(parameters.clip,(simd_float4){0.4,casterHeight-8,1.5,1});
        fprintf(csv,"%u,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.6f,%.6f,%.6f\n",frame,angle,casterHeight*tanf(angle)-0.4f,casterHeight*tanf(angle)+0.4f,
            leftWorld,rightWorld,centroidWorld,peak,(l.x*0.5f+0.5f)*size,(r.x*0.5f+0.5f)*size);
        float fp[WIDTH];for(int x=0;x<WIDTH;x++)fp[x]=pixels[(row*WIDTH+x)*4];fwrite(fp,4,WIDTH,floatProfiles);
        if(frame>128)for(int x=start;x<end;x++)largestProfileStep=MAX(largestProfileStep,fabsf(fp[x]-previousFrozen[x]));
        if(frame>=128 && [name containsString:@"frozen"]) {
            if(frame>128)for(int x=start;x<end;x++)frozenStep=MAX(frozenStep,fabsf(fp[x]-previousFrozen[x]));
            frozenLow=MIN(frozenLow,leftWorld);frozenHigh=MAX(frozenHigh,leftWorld);
        }
        memcpy(previousFrozen,fp,sizeof fp);
        uint8_t profile[WIDTH];for(int x=0;x<WIDTH;x++)profile[x]=MAX(0,MIN(255,lroundf(pixels[(row*WIDTH+x)*4]*255)));fwrite(profile,1,WIDTH,profiles);fwrite(readback.contents,4,size,depths);
        if(frame%128==0 || frame==count-1) { for(int i=0;i<WIDTH*HEIGHT*4;i++)image[i]=MAX(0,MIN(255,lroundf(pixels[i]*255)));saveImage([prefix stringByAppendingFormat:@"-%03u.ppm",frame],image); }
        if(frame==0)firstLeft=leftWorld;else if(leftWorld<lastLeft-0.00001f){backwards++;maxBack=MAX(maxBack,lastLeft-leftWorld);}
        lastLeft=leftWorld;
    }
    fclose(csv);fclose(profiles);fclose(floatProfiles);fclose(depths);free(pixels);free(image);
    if(packetBytes!=256&&packetBytes!=400&&packetBytes!=688&&[name containsString:@"frozen"])require(fabs(firstLeft-lastLeft)<0.000001f&&backwards==0,@"Static-light control changed its edge");
    if((packetBytes==256||(packetBytes==400||packetBytes==688))&&[name containsString:@"frozen"])
        require(frozenStep<0.02f && frozenHigh-frozenLow<0.005f,@"Temporal static-light control exceeded noise or edge-drift limit");
    // These named inputs come from SunFarFrames: two levels, range256, near4096/far2048.
    if([name hasPrefix:@"far-"])require(largestProfileStep<([name containsString:@"transition"]?0.025f:0.035f),@"Distant shadow visibility popped beyond the coarse-map regression limit");
    printf("%s: %u frames; backward edge steps %d, largest %.6f blocks\n",name.UTF8String,count,backwards,maxBack);
    [arena release];[sections release];[recs release];[list release];[draw release];[p release];[g release];[t release];[f release];
    [atlas release];[light release];[sampler release];[color release];[depth release];[readback release];
}
int main(int argc,const char **argv) {
    @autoreleasepool {
        require(argc>=5,@"Usage: sun_edge_check TERRAIN.metal SUN.metal OUTDIR FRAMES.bin...");
        NSString *source=[NSString stringWithContentsOfFile:[NSString stringWithUTF8String:argv[1]] encoding:NSUTF8StringEncoding error:nil];
        NSString *sunSource=[NSString stringWithContentsOfFile:[NSString stringWithUTF8String:argv[2]] encoding:NSUTF8StringEncoding error:nil];require(source&&sunSource,@"Shader sources missing");
        // Diagnostic output only: expose the computed visibility instead of mixing it with terrain color.
        NSString *needle=@"color.rgb*=mix(float3(1),(0.45+0.55*visible)*diffuse,sun.sun.w);";
        if(![source containsString:needle])needle=@"color.rgb*=mix(1.0,(0.45+0.55*visible)*diffuse,sun.sun.w*outdoor);";
        require([source containsString:needle],@"Could not locate diagnostic mask output");
        source=[[source stringByReplacingOccurrencesOfString:needle withString:@"return sunResult(float4(float3(visible),1));"] stringByAppendingString:sunSource];
        source=[source stringByReplacingOccurrencesOfString:@"return {color,float4(visible,distance)};" withString:@"return {float4(visible,1),float4(visible,distance)};"];
        char error[16384]={0},name[256]={0};Ctx *ctx=mc_create(name,sizeof name,error,sizeof error);require(ctx!=NULL,[NSString stringWithUTF8String:error]);
        void *own=mco_new(ctx,source.UTF8String,0,0,0,error,sizeof error);require(own!=NULL,[NSString stringWithUTF8String:error]);Enc *enc=mc_enc_new(ctx);
        NSString *directory=[NSString stringWithUTF8String:argv[3]];[[NSFileManager defaultManager] createDirectoryAtPath:directory withIntermediateDirectories:YES attributes:nil error:nil];
        for(int argument=4;argument<argc;argument++)runCase(ctx,own,enc,[NSString stringWithUTF8String:argv[argument]],directory);
        printf("Completed edge diagnostic on %s\n",name);
    }
    return 0;
}
