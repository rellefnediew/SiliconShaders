package siliconshaders.metal;

import org.lwjgl.sdl.SDLHints;
import org.lwjgl.sdl.SDLInit;

/** Connect the bundled game's SDL identity to its Launch Services identity before SDL starts. */
public final class MacGameMode {
    private MacGameMode() {}

    public static void prepare() {
        String appId=System.getProperty("siliconshaders.macos.appId","");
        if(appId.isBlank()||!System.getProperty("os.name","").startsWith("Mac"))return;
        // Minecraft later supplies its generic SDL ID. The hint takes precedence, retaining our actual host identity.
        boolean identity=SDLHints.SDL_SetHint(SDLHints.SDL_HINT_APP_ID,appId);
        boolean foreground=SDLHints.SDL_SetHint(SDLHints.SDL_HINT_MAC_BACKGROUND_APP,"0");
        boolean spaces=SDLHints.SDL_SetHint(SDLHints.SDL_HINT_VIDEO_MAC_FULLSCREEN_SPACES,"1");
        boolean game=SDLInit.SDL_SetAppMetadataProperty(SDLInit.SDL_PROP_APP_METADATA_TYPE_STRING,"game");
        siliconshaders.metal.Log.infof("[SiliconShaders macOS] bundled game %s; SDL identity=%s, foreground=%s, native fullscreen=%s, game metadata=%s%n",appId,identity,foreground,spaces,game);
    }
}
