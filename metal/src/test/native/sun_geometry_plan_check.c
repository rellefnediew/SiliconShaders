// CPU-only arithmetic/packing checks: no Metal device, command buffer or game.
#include "mcsun_geometry.h"
#include <assert.h>
#include <stdio.h>

int main(void) {
    const uint64_t maxRecords=UINT32_MAX/(64u*6u);
    assert(ownSunExactBytes(0,UINT64_MAX)==0);
    assert(ownSunExactBytes(1,256)==256);
    assert(ownSunExactBytes(1,255)==0);
    assert(ownSunExactBytes(maxRecords,UINT64_MAX)==maxRecords*256u);
    assert(maxRecords*64u*6u<=UINT32_MAX);
    assert(ownSunExactBytes(maxRecords+1,UINT64_MAX)==0);
    assert(ownSunExactBytes(UINT64_MAX,UINT64_MAX)==0);
    const uint32_t records[]={0,1,65535,65536,(uint32_t)maxRecords-1};
    for(unsigned r=0;r<sizeof records/sizeof records[0];r++)for(uint32_t count=1;count<=64;count++) {
        uint32_t vertices=count*6u;
        assert(vertices<=384);
        for(uint32_t v=0;v<vertices;v++) {
            uint32_t entry=(records[r]<<6)|(v/6);
            assert((entry>>6)==records[r]);assert((entry&63u)==v/6);
            uint32_t corner=(0x032210u>>((v%6)*4))&3u;
            const uint32_t expected[]={0,1,2,2,3,0};assert(corner==expected[v%6]);
        }
    }
    puts("PASS: exact capacity, uint32 draw limits, record/quad packing and triangle corners (CPU only)");
    return 0;
}
