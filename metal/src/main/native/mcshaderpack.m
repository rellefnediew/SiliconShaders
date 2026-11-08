// Opt-in final-image shaderpack pass. Uses siliconshaders's existing Metal command buffer and keeps its terrain path intact.
#import <Foundation/Foundation.h>
#import <ImageIO/ImageIO.h>
#import "mcmetal.h"

@interface MCShaderPack : NSObject {
@public
    Ctx *ctx;
    NSDictionary *stages;
    id<MTLFunction> vertex, fragment;
    id<MTLRenderPipelineState> pipeline;
    MTLPixelFormat format;
    id<MTLBuffer> quad;
    id<MTLSamplerState> colorSampler, noiseSampler;
    id<MTLTexture> scratch, noise;
}
@end

@implementation MCShaderPack
- (void)dealloc {
    [stages release]; [vertex release]; [fragment release]; [pipeline release]; [quad release];
    [colorSampler release]; [noiseSampler release]; [scratch release]; [noise release];
    [super dealloc];
}
@end

static void packError(char *error, int capacity, NSString *text) {
    if (error && capacity > 0) snprintf(error, capacity, "%s", (text ?: @"Shader operation failed").UTF8String);
}

static id<MTLTexture> packNoise(Ctx *ctx, NSString *base64, NSString **problem) {
    NSData *png = [[[NSData alloc] initWithBase64EncodedString:base64 options:0] autorelease];
    if (!png || png.length == 0 || png.length > 4*1024*1024) { *problem = @"Missing or oversized pack noise image"; return nil; }
    CGImageSourceRef source = CGImageSourceCreateWithData((CFDataRef)png, NULL);
    if (!source) { *problem = @"Cannot read pack noise image"; return nil; }
    NSDictionary *info = (NSDictionary *)CGImageSourceCopyPropertiesAtIndex(source, 0, NULL);
    NSUInteger width = [info[(NSString *)kCGImagePropertyPixelWidth] unsignedIntegerValue];
    NSUInteger height = [info[(NSString *)kCGImagePropertyPixelHeight] unsignedIntegerValue];
    [info release];
    if (width == 0 || height == 0 || width > 2048 || height > 2048) { CFRelease(source); *problem = @"Noise image dimensions unsupported"; return nil; }
    CGImageRef image = CGImageSourceCreateImageAtIndex(source, 0, NULL);
    CFRelease(source);
    if (!image) { *problem = @"Cannot decode pack noise image"; return nil; }
    NSMutableData *pixels = [NSMutableData dataWithLength:width*height*4];
    CGColorSpaceRef space = CGColorSpaceCreateDeviceRGB();
    CGContextRef bitmap = CGBitmapContextCreate(pixels.mutableBytes,width,height,8,width*4,space,kCGImageAlphaPremultipliedLast|kCGBitmapByteOrder32Big);
    CGColorSpaceRelease(space);
    if (!bitmap) { CGImageRelease(image); *problem = @"Cannot decode noise pixels"; return nil; }
    CGContextDrawImage(bitmap,CGRectMake(0,0,width,height),image);
    CGContextRelease(bitmap); CGImageRelease(image);
    MTLTextureDescriptor *desc = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:width height:height mipmapped:NO];
    desc.usage = MTLTextureUsageShaderRead; desc.storageMode = MTLStorageModeShared;
    id<MTLTexture> texture = [ctx->device newTextureWithDescriptor:desc];
    if (texture) [texture replaceRegion:MTLRegionMake2D(0,0,width,height) mipmapLevel:0 withBytes:pixels.bytes bytesPerRow:width*4];
    else *problem = @"Cannot allocate noise texture";
    return texture;
}

static BOOL packPipeline(MCShaderPack *pack, MTLPixelFormat format, NSString **problem) {
    if (pack->pipeline && pack->format == format) return YES;
    MTLRenderPipelineDescriptor *desc = [[[MTLRenderPipelineDescriptor alloc] init] autorelease];
    desc.label = @"siliconshaders shaderpack final pass";
    desc.vertexFunction = pack->vertex; desc.fragmentFunction = pack->fragment;
    desc.colorAttachments[0].pixelFormat = format;
    MTLVertexDescriptor *layout = [MTLVertexDescriptor vertexDescriptor];
    for (int i=0;i<2;i++) {
        layout.attributes[i].format=MTLVertexFormatFloat4; layout.attributes[i].offset=i*16; layout.attributes[i].bufferIndex=30;
    }
    layout.layouts[30].stride=32; desc.vertexDescriptor=layout;
    NSError *error = nil;
    id<MTLRenderPipelineState> pipeline = [pack->ctx->device newRenderPipelineStateWithDescriptor:desc error:&error];
    if (!pipeline) { *problem=error.localizedDescription; return NO; }
    [pack->pipeline release]; pack->pipeline=pipeline; pack->format=format;
    return YES;
}

void *mc_pack_new(Ctx *ctx, const char *json, int pixelFormat, char *error, int capacity) {
    @autoreleasepool {
        MCShaderPack *pack = [[MCShaderPack alloc] init];
        pack->ctx=ctx;
        @try {
            NSError *issue=nil;
            NSData *data=[NSData dataWithBytes:json length:strlen(json)];
            NSDictionary *root=[NSJSONSerialization JSONObjectWithData:data options:0 error:&issue];
            if (![root isKindOfClass:NSDictionary.class]) { packError(error,capacity,issue.localizedDescription); [pack release]; return NULL; }
            pack->stages=[@{ @"vertex":root[@"vertex"], @"fragment":root[@"fragment"] } retain];
            for (NSString *name in @[@"vertex",@"fragment"]) {
                NSDictionary *stage=pack->stages[name];
                MTLCompileOptions *options=[[[MTLCompileOptions alloc] init] autorelease];
                options.languageVersion=MTLLanguageVersion3_0;
                id<MTLLibrary> library=[ctx->device newLibraryWithSource:stage[@"metal"] options:options error:&issue];
                if (!library) { packError(error,capacity,issue.localizedDescription); [pack release]; return NULL; }
                id<MTLFunction> fn=[library newFunctionWithName:stage[@"entryPoint"]]; [library release];
                if (!fn) { packError(error,capacity,@"Metal shader entry point missing"); [pack release]; return NULL; }
                if ([name isEqualToString:@"vertex"]) pack->vertex=fn; else pack->fragment=fn;
            }
            NSString *problem=nil;
            if (!packPipeline(pack,(MTLPixelFormat)pixelFormat,&problem)) { packError(error,capacity,problem); [pack release]; return NULL; }
            const float quad[]={-1,-1,0,1,0,0,0,1, 1,-1,0,1,1,0,0,1, -1,1,0,1,0,1,0,1, 1,1,0,1,1,1,0,1};
            pack->quad=[ctx->device newBufferWithBytes:quad length:sizeof(quad) options:MTLResourceStorageModeShared];
            MTLSamplerDescriptor *sampler=[[[MTLSamplerDescriptor alloc] init] autorelease];
            sampler.minFilter=MTLSamplerMinMagFilterLinear; sampler.magFilter=MTLSamplerMinMagFilterLinear;
            sampler.sAddressMode=MTLSamplerAddressModeClampToEdge; sampler.tAddressMode=MTLSamplerAddressModeClampToEdge;
            pack->colorSampler=[ctx->device newSamplerStateWithDescriptor:sampler];
            sampler.sAddressMode=MTLSamplerAddressModeRepeat; sampler.tAddressMode=MTLSamplerAddressModeRepeat;
            pack->noiseSampler=[ctx->device newSamplerStateWithDescriptor:sampler];
            if (root[@"noisePng"] && [root[@"noisePng"] length]) {
                pack->noise=packNoise(ctx,root[@"noisePng"],&problem);
                if (!pack->noise) { packError(error,capacity,problem); [pack release]; return NULL; }
            }
            if (!pack->quad || !pack->colorSampler || !pack->noiseSampler) { packError(error,capacity,@"Cannot allocate shader resources"); [pack release]; return NULL; }
            return pack;
        } @catch(NSException *exception) {
            packError(error,capacity,exception.reason); [pack release]; return NULL;
        }
    }
}

static BOOL packUniforms(MCShaderPack *pack, id<MTLRenderCommandEncoder> encoder, NSDictionary *stage, BOOL vertex, int width, int height, int water, float time, NSString **problem) {
    for (NSDictionary *desc in stage[@"buffers"]) {
        NSUInteger size=[desc[@"size"] unsignedIntegerValue],slot=[desc[@"slot"] unsignedIntegerValue];
        if (size==0 || size>4096 || slot>=30) { *problem=@"Unsupported uniform-buffer layout"; return NO; }
        NSMutableData *data=[NSMutableData dataWithLength:(size+15)&~(NSUInteger)15];
        NSDictionary *offsets=desc[@"offsets"];
        NSDictionary *values=@{@"viewWidth":@(width),@"viewHeight":@(height),@"aspectRatio":@((float)width/height),@"frameTimeCounter":@(time),@"screenBrightness":@1.0};
        for (NSString *name in values) {
            if (!offsets[name]) continue;
            NSUInteger offset=[offsets[name] unsignedIntegerValue];
            if (offset+4>size) { *problem=@"Invalid uniform offset"; return NO; }
            float value=[values[name] floatValue]; memcpy((char *)data.mutableBytes+offset,&value,4);
        }
        if (offsets[@"isEyeInWater"]) {
            NSUInteger offset=[offsets[@"isEyeInWater"] unsignedIntegerValue];
            if (offset+4>size) { *problem=@"Invalid water-state uniform offset"; return NO; }
            memcpy((char *)data.mutableBytes+offset,&water,4);
        }
        // Metal copies setBytes into command-buffer storage, so a later frame cannot overwrite in-flight uniforms.
        if (vertex) [encoder setVertexBytes:data.bytes length:data.length atIndex:slot];
        else [encoder setFragmentBytes:data.bytes length:data.length atIndex:slot];
    }
    return YES;
}

int mc_pack_draw(Enc *enc, MCShaderPack *pack, id<MTLTexture> target, int width, int height, int water, float time, char *error, int capacity) {
    @autoreleasepool {
        id<MTLRenderCommandEncoder> renderer=nil;
        if (!pack || !target || width<=0 || height<=0 || pack->ctx!=enc->ctx) { packError(error,capacity,@"Invalid shader render target"); return 0; }
        @try {
            NSString *problem=nil;
            if (!packPipeline(pack,target.pixelFormat,&problem)) { packError(error,capacity,problem); return 0; }
            if (!pack->scratch || pack->scratch.width!=width || pack->scratch.height!=height || pack->scratch.pixelFormat!=target.pixelFormat) {
                MTLTextureDescriptor *desc=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:target.pixelFormat width:width height:height mipmapped:NO];
                desc.usage=MTLTextureUsageShaderRead; desc.storageMode=MTLStorageModePrivate;
                id<MTLTexture> next=[enc->ctx->device newTextureWithDescriptor:desc];
                if (!next) { packError(error,capacity,@"Cannot allocate shader input image"); return 0; }
                [pack->scratch release]; pack->scratch=next;
            }
            // Distinct source/destination textures are essential: no framebuffer sampling feedback loop.
            [mc_blit(enc) copyFromTexture:target sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0,0,0) sourceSize:MTLSizeMake(width,height,1) toTexture:pack->scratch destinationSlice:0 destinationLevel:0 destinationOrigin:MTLOriginMake(0,0,0)];
            id<MTLCommandBuffer> command=mc_frame_cmd(enc);
            MTLRenderPassDescriptor *desc=[MTLRenderPassDescriptor renderPassDescriptor];
            desc.colorAttachments[0].texture=target; desc.colorAttachments[0].loadAction=MTLLoadActionLoad; desc.colorAttachments[0].storeAction=MTLStoreActionStore;
            renderer=[command renderCommandEncoderWithDescriptor:desc];
            if (!renderer) { packError(error,capacity,@"Cannot begin shader render pass"); return 0; }
            renderer.label=@"siliconshaders shaderpack final (before HUD)";
            [renderer setRenderPipelineState:pack->pipeline]; [renderer setVertexBuffer:pack->quad offset:0 atIndex:30];
            BOOL valid=YES;
            for (NSString *name in @[@"vertex",@"fragment"]) {
                BOOL vertex=[name isEqualToString:@"vertex"]; NSDictionary *stage=pack->stages[name];
                if (!packUniforms(pack,renderer,stage,vertex,width,height,water,time,&problem)) { valid=NO; break; }
                for (NSDictionary *texture in stage[@"textures"]) {
                    NSString *resource=texture[@"name"]; NSUInteger slot=[texture[@"slot"] unsignedIntegerValue];
                    BOOL isNoise=[resource isEqualToString:@"noisetex"];
                    if (slot>=31 || (!isNoise && ![resource isEqualToString:@"colortex3"]) || (isNoise && !pack->noise)) { problem=@"Unsupported shader texture"; valid=NO; break; }
                    id<MTLTexture> image=isNoise?pack->noise:pack->scratch; id<MTLSamplerState> sampler=isNoise?pack->noiseSampler:pack->colorSampler;
                    if (vertex) { [renderer setVertexTexture:image atIndex:slot]; [renderer setVertexSamplerState:sampler atIndex:slot]; }
                    else { [renderer setFragmentTexture:image atIndex:slot]; [renderer setFragmentSamplerState:sampler atIndex:slot]; }
                }
                if (!valid) break;
            }
            if (valid) [renderer drawPrimitives:MTLPrimitiveTypeTriangleStrip vertexStart:0 vertexCount:4];
            [renderer endEncoding]; renderer=nil;
            if (!valid) packError(error,capacity,problem);
            return valid?1:0;
        } @catch(NSException *exception) { if(renderer) [renderer endEncoding]; packError(error,capacity,exception.reason); return 0; }
    }
}

void mc_pack_release(MCShaderPack *pack) { [pack release]; }
