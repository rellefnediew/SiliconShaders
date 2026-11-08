// Standalone Metal execution check for the compiled final pass. Never opens Minecraft or changes its renderer.
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#include <math.h>

static void fail(NSString *message) {
    fprintf(stderr, "%s\n", message.UTF8String);
    exit(1);
}

static unsigned char patternChannel(int x, int y, int channel, const unsigned char rgba[4], BOOL checkerboard) {
    const unsigned char alternate[] = {170,90,35,255};
    x = MAX(0, MIN(63, x));
    y = MAX(0, MIN(63, y));
    return checkerboard && ((x/8 + y/8) % 2) ? alternate[channel] : rgba[channel];
}

static id<MTLTexture> inputTexture(id<MTLDevice> device, NSUInteger width, NSUInteger height, const unsigned char rgba[4], BOOL checkerboard) {
    MTLTextureDescriptor *desc = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:width height:height mipmapped:NO];
    desc.storageMode = MTLStorageModeShared;
    desc.usage = MTLTextureUsageShaderRead;
    id<MTLTexture> texture = [device newTextureWithDescriptor:desc];
    if (!texture) fail(@"Cannot allocate input texture");
    NSMutableData *data = [NSMutableData dataWithLength:width * height * 4];
    unsigned char *bytes = data.mutableBytes;
    for (NSUInteger i = 0; i < width * height; i++) for (int c = 0; c < 4; c++)
        bytes[i*4+c] = patternChannel((int)(i % width), (int)(i/width), c, rgba, checkerboard);
    [texture replaceRegion:MTLRegionMake2D(0, 0, width, height) mipmapLevel:0 withBytes:bytes bytesPerRow:width * 4];
    return texture;
}

static void bindUniforms(id<MTLDevice> device, id<MTLRenderCommandEncoder> encoder, NSDictionary *stage, BOOL vertex, NSMutableArray *keepAlive, float width, float height) {
    for (NSDictionary *desc in stage[@"buffers"]) {
        NSUInteger size = [desc[@"size"] unsignedIntegerValue], slot = [desc[@"slot"] unsignedIntegerValue];
        NSUInteger alignedSize = MAX((size + 15) & ~(NSUInteger)15, 16);
        id<MTLBuffer> buffer = [device newBufferWithLength:alignedSize options:MTLResourceStorageModeShared];
        if (!buffer) fail(@"Cannot allocate uniform buffer");
        memset(buffer.contents, 0, buffer.length);
        NSDictionary *values = @{@"viewWidth": @(width), @"viewHeight": @(height), @"aspectRatio": @(width/height), @"screenBrightness": @1.0};
        NSDictionary *offsets = desc[@"offsets"];
        for (NSString *name in values) {
            if (!offsets[name]) continue;
            NSUInteger offset = [offsets[name] unsignedIntegerValue];
            if (offset + sizeof(float) > size) fail(@"Invalid reflected uniform offset");
            float value = [values[name] floatValue];
            memcpy((unsigned char *)buffer.contents + offset, &value, sizeof(value));
        }
        if (vertex) [encoder setVertexBuffer:buffer offset:0 atIndex:slot];
        else [encoder setFragmentBuffer:buffer offset:0 atIndex:slot];
        [keepAlive addObject:buffer];
    }
}

int main(int argc, const char *argv[]) {
    @autoreleasepool {
        if (argc != 3 && argc != 4 && argc != 6) fail(@"Usage: metal_probe MANIFEST.json OUTPUT.ppm [--checkerboard | R G B]");
        BOOL checkerboard = argc == 4 && strcmp(argv[3], "--checkerboard") == 0;
        if (argc == 4 && !checkerboard) fail(@"Unknown probe option");
        NSString *manifestPath = [NSString stringWithUTF8String:argv[1]];
        NSError *error = nil;
        NSData *json = [NSData dataWithContentsOfFile:manifestPath options:0 error:&error];
        if (!json) fail(error.localizedDescription);
        NSDictionary *manifest = [NSJSONSerialization JSONObjectWithData:json options:0 error:&error];
        if (![manifest isKindOfClass:NSDictionary.class]) fail(error.localizedDescription ?: @"Invalid manifest");
        id<MTLDevice> device = MTLCreateSystemDefaultDevice();
        if (!device) fail(@"Metal device unavailable");
        NSMutableArray *keepAlive = [NSMutableArray array];
        NSString *directory = manifestPath.stringByDeletingLastPathComponent;
        id<MTLFunction> functions[2];
        NSArray *stages = @[manifest[@"vertex"], manifest[@"fragment"]];
        for (int s = 0; s < 2; s++) {
            NSDictionary *stage = stages[s];
            NSString *source = [NSString stringWithContentsOfFile:[directory stringByAppendingPathComponent:stage[@"file"]] encoding:NSUTF8StringEncoding error:&error];
            if (!source) fail(error.localizedDescription);
            MTLCompileOptions *options = [MTLCompileOptions new];
            options.languageVersion = MTLLanguageVersion3_0;
            id<MTLLibrary> library = [device newLibraryWithSource:source options:options error:&error];
            if (!library) fail(error.localizedDescription);
            functions[s] = [library newFunctionWithName:stage[@"entryPoint"]];
            if (!functions[s]) fail(@"Metal entry point missing");
        }
        MTLRenderPipelineDescriptor *pipelineDesc = [MTLRenderPipelineDescriptor new];
        pipelineDesc.vertexFunction = functions[0];
        pipelineDesc.fragmentFunction = functions[1];
        pipelineDesc.colorAttachments[0].pixelFormat = MTLPixelFormatRGBA8Unorm;
        MTLVertexDescriptor *vertices = [MTLVertexDescriptor vertexDescriptor];
        for (int attribute = 0; attribute < 2; attribute++) {
            vertices.attributes[attribute].format = MTLVertexFormatFloat4;
            vertices.attributes[attribute].offset = attribute * 4 * sizeof(float);
            vertices.attributes[attribute].bufferIndex = 30;
        }
        vertices.layouts[30].stride = 8 * sizeof(float);
        pipelineDesc.vertexDescriptor = vertices;
        id<MTLRenderPipelineState> pipeline = [device newRenderPipelineStateWithDescriptor:pipelineDesc error:&error];
        if (!pipeline) fail(error.localizedDescription);
        const NSUInteger width = 64, height = 64;
        MTLTextureDescriptor *outputDesc = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:width height:height mipmapped:NO];
        outputDesc.storageMode = MTLStorageModeShared;
        outputDesc.usage = MTLTextureUsageRenderTarget;
        id<MTLTexture> output = [device newTextureWithDescriptor:outputDesc];
        if (!output) fail(@"Cannot allocate render target");
        id<MTLCommandQueue> queue = [device newCommandQueue];
        id<MTLCommandBuffer> command = [queue commandBuffer];
        MTLRenderPassDescriptor *pass = [MTLRenderPassDescriptor renderPassDescriptor];
        pass.colorAttachments[0].texture = output;
        pass.colorAttachments[0].loadAction = MTLLoadActionClear;
        pass.colorAttachments[0].storeAction = MTLStoreActionStore;
        pass.colorAttachments[0].clearColor = MTLClearColorMake(1, 0, 1, 1);
        id<MTLRenderCommandEncoder> encoder = [command renderCommandEncoderWithDescriptor:pass];
        if (!queue || !command || !encoder) fail(@"Cannot begin Metal draw");
        [encoder setRenderPipelineState:pipeline];
        const float quad[] = {
            -1,-1,0,1, 0,0,0,1,   1,-1,0,1, 1,0,0,1,
            -1, 1,0,1, 0,1,0,1,   1, 1,0,1, 1,1,0,1
        };
        id<MTLBuffer> vertexBuffer = [device newBufferWithBytes:quad length:sizeof(quad) options:MTLResourceStorageModeShared];
        [encoder setVertexBuffer:vertexBuffer offset:0 atIndex:30];
        bindUniforms(device, encoder, stages[0], YES, keepAlive, width, height);
        bindUniforms(device, encoder, stages[1], NO, keepAlive, width, height);
        unsigned char rgba[] = {51,102,153,255};
        if (argc == 6) for (int c = 0; c < 3; c++) {
            char *end = NULL;
            long value = strtol(argv[c + 3], &end, 10);
            if (*end || value < 0 || value > 255) fail(@"RGB inputs must be integers from 0 to 255");
            rgba[c] = value;
        }
        const unsigned char noise[] = {64,64,64,255};
        id<MTLTexture> colorTexture = inputTexture(device, width, height, rgba, checkerboard);
        id<MTLTexture> noiseTexture = inputTexture(device, 128, 128, noise, NO);
        MTLSamplerDescriptor *samplerDesc = [MTLSamplerDescriptor new];
        samplerDesc.minFilter = MTLSamplerMinMagFilterLinear;
        samplerDesc.magFilter = MTLSamplerMinMagFilterLinear;
        samplerDesc.sAddressMode = MTLSamplerAddressModeClampToEdge;
        samplerDesc.tAddressMode = MTLSamplerAddressModeClampToEdge;
        id<MTLSamplerState> sampler = [device newSamplerStateWithDescriptor:samplerDesc];
        for (int s = 0; s < 2; s++) for (NSDictionary *desc in stages[s][@"textures"]) {
            NSString *name = desc[@"name"];
            id<MTLTexture> texture;
            if ([name isEqualToString:@"colortex3"]) texture = colorTexture;
            else if ([name isEqualToString:@"noisetex"]) texture = noiseTexture;
            else fail([@"Probe does not provide texture: " stringByAppendingString:name]);
            NSUInteger slot = [desc[@"slot"] unsignedIntegerValue];
            if (s == 0) {
                [encoder setVertexTexture:texture atIndex:slot];
                [encoder setVertexSamplerState:sampler atIndex:slot];
            } else {
                [encoder setFragmentTexture:texture atIndex:slot];
                [encoder setFragmentSamplerState:sampler atIndex:slot];
            }
        }
        [encoder drawPrimitives:MTLPrimitiveTypeTriangleStrip vertexStart:0 vertexCount:4];
        [encoder endEncoding];
        [command commit];
        [command waitUntilCompleted];
        if (command.status == MTLCommandBufferStatusError) fail(command.error.localizedDescription);
        unsigned char pixels[64 * 64 * 4];
        [output getBytes:pixels bytesPerRow:width * 4 fromRegion:MTLRegionMake2D(0,0,width,height) mipmapLevel:0];
        NSUInteger center = ((height/2)*width + width/2)*4;
        int maxError = 0;
        // CPU reference for this pack version's default final pass: sharpen(5), vignette, constant-noise dither.
        // Saved settings used by this experiment do not override these camera options.
        for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) {
            float color[3];
            for (int c = 0; c < 3; c++) {
                float neighbors = patternChannel(x-1,y,c,rgba,checkerboard) + patternChannel(x+1,y,c,rgba,checkerboard)
                    + patternChannel(x,y-1,c,rgba,checkerboard) + patternChannel(x,y+1,c,rgba,checkerboard);
                color[c] = (patternChannel(x,y,c,rgba,checkerboard)*1.25f - neighbors*0.0625f)/255.0f;
            }
            float u = (x+0.5f)/64.0f-0.5f, v = (y+0.5f)/64.0f-0.5f;
            float luminance = color[0]*0.299f + color[1]*0.587f + color[2]*0.114f;
            float vignette = 1.0f - (u*u+v*v)*(1.0f-luminance);
            for (int c = 0; c < 3; c++) {
                float value = color[c]*vignette + (64.0f/255.0f-0.25f)/128.0f;
                int expected = (int)lroundf(MAX(0.0f, MIN(1.0f, value))*255.0f);
                maxError = MAX(maxError, abs((int)pixels[(y*64+x)*4+c]-expected));
            }
            if (pixels[(y*64+x)*4+3] != 255) fail(@"Final-pass alpha is not opaque");
        }
        if (maxError > 2) fail([NSString stringWithFormat:@"GPU output differs from final-pass CPU reference (max channel error %d)", maxError]);
        NSMutableData *ppm = [NSMutableData dataWithData:[[NSString stringWithFormat:@"P6\n%lu %lu\n255\n", (unsigned long)width,(unsigned long)height] dataUsingEncoding:NSASCIIStringEncoding]];
        for (NSUInteger i = 0; i < width * height; i++) [ppm appendBytes:pixels+i*4 length:3];
        if (![ppm writeToFile:[NSString stringWithUTF8String:argv[2]] options:NSDataWritingAtomic error:&error]) fail(error.localizedDescription);
        printf("PASS: native Metal final-pass draw on %s; 64x64; mode=%s; center RGB=(%u,%u,%u); CPU-reference max error=%d/255\n", device.name.UTF8String, checkerboard ? "checkerboard" : "constant", pixels[center],pixels[center+1],pixels[center+2],maxError);
    }
    return 0;
}
