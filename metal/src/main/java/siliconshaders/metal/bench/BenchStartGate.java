package siliconshaders.metal.bench;

/** Wait for teardown and resource loading, regardless of which launcher owns the main-menu screen. */
final class BenchStartGate {
    static final long SETTLE_NANOS=1_000_000_000L,TIMEOUT_NANOS=120_000_000_000L;
    enum State { WORLD, SERVER, CONNECTION, RESOURCES, SETTLE, READY }
    private final long requestedAt;
    private long readySince=Long.MIN_VALUE;
    private State state=State.SETTLE;
    BenchStartGate(long now){requestedAt=now;}
    State poll(long now,boolean world,boolean server,boolean connection,boolean resources){
        State blocked=world?State.WORLD:server?State.SERVER:connection?State.CONNECTION:resources?State.RESOURCES:null;
        if(blocked!=null){readySince=Long.MIN_VALUE;return state=blocked;}
        if(readySince==Long.MIN_VALUE)readySince=now;
        return state=now-readySince>=SETTLE_NANOS?State.READY:State.SETTLE;
    }
    State state(){return state;}
    boolean expired(long now){return now-requestedAt>=TIMEOUT_NANOS;}
}
