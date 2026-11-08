#pragma once
#include <stdint.h>
#include <stdlib.h>
#include <math.h>
// The nebula's noise, independently generated and seamless: four channels of periodic fractal value noise, baked once on first use, so the
// sky passes only pay for hardware-filtered reads. R and G: broad shapes. B: mid detail. A: fine detail for rays.
#define MC_SKY_NOISE_SIZE 256
static float mc_sky_lattice(int x,int y,int period,uint32_t seed) {
    uint32_t h=(uint32_t)((x%period+period)%period)*0x9E3779B1u^(uint32_t)((y%period+period)%period)*0x85EBCA77u^seed;
    h^=h>>15;h*=0x2C1B3C6Du;h^=h>>12;h*=0x297A2D39u;h^=h>>15;
    return (float)(h&0xFFFFFF)/16777215.0f;
}
static float mc_sky_value(float x,float y,int period,uint32_t seed) {
    int ix=(int)floorf(x),iy=(int)floorf(y);float fx=x-ix,fy=y-iy;
    fx=fx*fx*fx*(fx*(fx*6-15)+10);fy=fy*fy*fy*(fy*(fy*6-15)+10);
    float a=mc_sky_lattice(ix,iy,period,seed),b=mc_sky_lattice(ix+1,iy,period,seed),c=mc_sky_lattice(ix,iy+1,period,seed),d=mc_sky_lattice(ix+1,iy+1,period,seed);
    return (a+(b-a)*fx)*(1-fy)+(c+(d-c)*fx)*fy;
}
static void mc_sky_noise_fill(uint8_t *rgba) {
    const int size=MC_SKY_NOISE_SIZE;
    static const struct {int cells,octaves;uint32_t seed;} channels[4]={{4,5,0x4d434f50u},{5,5,0x53594b31u},{8,4,0x4e4f4953u},{24,3,0x52415953u}};
    float *field=malloc(sizeof(float)*size*size);
    for(int c=0;c<4;c++) {
        float low=1e9f,high=-1e9f;
        for(int y=0;y<size;y++)for(int x=0;x<size;x++) {
            float sum=0,gain=1;int cells=channels[c].cells;
            for(int o=0;o<channels[c].octaves;o++) {
                sum+=gain*mc_sky_value((x+.5f)*cells/size,(y+.5f)*cells/size,cells,channels[c].seed+(uint32_t)o*0x1000193u);
                gain*=.55f;cells*=2;
            }
            field[y*size+x]=sum;if(sum<low)low=sum;if(sum>high)high=sum;
        }
        for(int i=0;i<size*size;i++)rgba[i*4+c]=(uint8_t)lroundf((field[i]-low)/(high-low)*255);
    }
    free(field);
}
// In-place 2x2 box reduction. Upload each resulting level before reducing it again.
static void mc_sky_noise_downsample(uint8_t *rgba,int side) {
    int next=side/2;
    for(int y=0;y<next;y++)for(int x=0;x<next;x++)for(int c=0;c<4;c++) {
        int at=((y*2)*side+x*2)*4+c;
        unsigned sum=rgba[at]+rgba[at+4]+rgba[at+side*4]+rgba[at+side*4+4];
        rgba[(y*next+x)*4+c]=(uint8_t)((sum+2)/4);
    }
}
// Independently generated repeatable 128x128 scalar noise with packed cell corners, not a redistributed shader-pack image.
static void mc_aurora_noise_fill(uint8_t *rgba) {
    uint32_t state=0x4d434f50u;
    for(int i=0;i<128*128;i++) {
        for(int c=0;c<3;c++){state^=state<<13;state^=state>>17;state^=state<<5;rgba[i*4+c]=(uint8_t)(state>>24);}
        rgba[i*4+3]=255;
    }
    // Pack each scalar-noise cell's four corners. The shader obtains the smooth
    // value and analytic gradient in one read; fine detail uses the repeated B field.
    uint8_t red[128*128];for(int i=0;i<128*128;i++)red[i]=rgba[i*4];
    for(int y=0;y<128;y++)for(int x=0;x<128;x++) {
        int at=(y*128+x)*4,nextX=(x+1)&127,nextY=(y+1)&127;
        rgba[at]=red[y*128+x];rgba[at+1]=red[y*128+nextX];
        rgba[at+2]=red[nextY*128+x];rgba[at+3]=red[nextY*128+nextX];
    }
}

// In-place 2x2 box reduction. Upload each resulting level before reducing it again.
// Only fine-detail lookups use these mips; nonlinear ridge shaping stays at level 0.
static void mc_aurora_noise_downsample(uint8_t *rgba,int side) {
    int next=side/2;
    for(int y=0;y<next;y++)for(int x=0;x<next;x++)for(int c=0;c<4;c++) {
        int at=((y*2)*side+x*2)*4+c;
        unsigned sum=rgba[at]+rgba[at+4]+rgba[at+side*4]+rgba[at+side*4+4];
        rgba[(y*next+x)*4+c]=(uint8_t)((sum+2)/4);
    }
}
