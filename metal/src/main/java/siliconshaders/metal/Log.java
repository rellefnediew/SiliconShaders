package siliconshaders.metal;

import org.slf4j.LoggerFactory;

/**
 * The mod's log, in the game's log with its level and thread. Falls back to the console where no logging is set up (an early
 * launch stage, or a launcher without it). Shutdown hooks print directly: logging may already have stopped by then.
 */
public final class Log {
    private static final boolean READY=ready();
    private Log() {}
    private static boolean ready() {
        try { return !LoggerFactory.getILoggerFactory().getClass().getSimpleName().startsWith("NOP"); }
        catch(LinkageError missing) { return false; }
    }
    private static final class Slf4j { static final org.slf4j.Logger LOGGER=LoggerFactory.getLogger("siliconshaders"); }
    public static void info(Object message) { if(READY)Slf4j.LOGGER.info(String.valueOf(message));else System.out.println(message); }
    public static void warn(Object message) { if(READY)Slf4j.LOGGER.warn(String.valueOf(message));else System.err.println(message); }
    public static void infof(String format,Object... arguments) { info(String.format(format,arguments).stripTrailing()); }
    public static void infof(java.util.Locale locale,String format,Object... arguments) { info(String.format(locale,format,arguments).stripTrailing()); }
    public static void failure(Throwable failure) {
        if(READY)Slf4j.LOGGER.warn(String.valueOf(failure),failure);else failure.printStackTrace();
    }
}
