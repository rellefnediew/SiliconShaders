#pragma once
#include <stdint.h>

// A record contains 1..64 quads. The compact table encodes record:26 | quad:6;
// one triangle-list draw emits six vertices per table entry. Reserve the worst
// case so the existing one-pass cull needs neither a size readback nor a scan.
static inline uint64_t ownSunExactBytes(uint64_t records,uint64_t maxBufferLength) {
    if(!records || records>UINT32_MAX/(64u*6u))return 0;
    uint64_t bytes=records*64u*sizeof(uint32_t);
    return bytes<=maxBufferLength?bytes:0;
}
