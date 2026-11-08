package siliconshaders.metal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

/**
 * Loads, on background threads, the classes Minecraft is about to load one by one on its main thread during boot and
 * world load, in the order it loads them, so reading, Mixin-transforming and defining them overlaps the main thread's
 * own work instead of stalling it. Loading only (no static initializers run), so nothing about the game changes: the
 * same classes end up loaded, only sooner and off the main thread. The list (resources/siliconshaders/metal/preload.txt.gz)
 * comes from a -Xlog:class+load run of the shipping stack (tools/preloadlist.py); names that don't exist in some
 * other mod set are skipped. -Dsiliconshaders.preload=false turns it off, -Dsiliconshaders.preload.threads=N sets the thread count.
 */
public final class Preload implements PreLaunchEntrypoint {
	static {
		// A fresh installation starts with the Standard profile; before any settings store or flag is read.
		siliconshaders.metal.settings.DefaultProfile.applyOnFirstStart(net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir());
		siliconshaders.metal.Profile.apply(); // before any flag is read
	}

	private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("siliconshaders.preload", "true"));
	private static final int THREADS = Integer.getInteger("siliconshaders.preload.threads", 2);

	@Override
	public void onPreLaunch() {
		PlatformCheck.run();
		MacGameMode.prepare();
		if (!ENABLED) return;
		List<String> names = new ArrayList<>();
		try (InputStream in = Preload.class.getResourceAsStream("/siliconshaders/metal/preload.txt.gz")) {
			if (in == null) return;
			BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(in), StandardCharsets.UTF_8));
			for (String line; (line = r.readLine()) != null; ) if (!line.isEmpty()) names.add(line);
		} catch (IOException e) {
			return;
		}
		ClassLoader loader = Preload.class.getClassLoader();
		AtomicInteger next = new AtomicInteger();
		for (int t = 0; t < THREADS; t++) {
			Thread thread = new Thread(() -> {
				for (int i; (i = next.getAndIncrement()) < names.size(); ) {
					try {
						Class.forName(names.get(i), false, loader);
					} catch (Throwable absent) {
						// Not in this mod set or environment: the game wouldn't load it either.
					}
				}
			}, "siliconshaders class preload #" + t);
			thread.setDaemon(true);
			thread.start();
		}
	}
}
