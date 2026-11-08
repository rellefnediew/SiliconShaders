package siliconshaders.metal;

import org.lwjgl.sdl.SDLHints;
import org.lwjgl.sdl.SDLInit;

/** Startup metadata only: never initializes video, creates a window, or starts Minecraft. */
public final class MacGameModeChecks {
    public static void main(String[] args) {
        System.clearProperty("siliconshaders.macos.appId");
        String previous=SDLInit.SDL_GetAppMetadataProperty(SDLInit.SDL_PROP_APP_METADATA_TYPE_STRING);
        MacGameMode.prepare();
        require(java.util.Objects.equals(previous,SDLInit.SDL_GetAppMetadataProperty(SDLInit.SDL_PROP_APP_METADATA_TYPE_STRING)),"Unbundled launcher was changed");
        System.setProperty("siliconshaders.macos.appId","local.siliconshaders.testhost");
        MacGameMode.prepare();
        require(SDLInit.SDL_SetAppMetadata("Minecraft","26.3","com.mojang.minecraft"),"Minecraft metadata simulation failed");
        require("local.siliconshaders.testhost".equals(SDLInit.SDL_GetAppMetadataProperty(SDLInit.SDL_PROP_APP_METADATA_IDENTIFIER_STRING)),"Minecraft replaced the host bundle identity");
        require("game".equals(SDLInit.SDL_GetAppMetadataProperty(SDLInit.SDL_PROP_APP_METADATA_TYPE_STRING)),"Game classification was lost");
        require(!SDLHints.SDL_GetHintBoolean(SDLHints.SDL_HINT_MAC_BACKGROUND_APP,true),"Foreground activation was not requested");
        require(SDLHints.SDL_GetHintBoolean(SDLHints.SDL_HINT_VIDEO_MAC_FULLSCREEN_SPACES,false),"Native fullscreen Spaces were not enabled");
        System.out.println("PASS: host identity survives Minecraft metadata, game type/foreground/Spaces configured before video initialization");
    }
    private static void require(boolean value,String message) { if(!value)throw new AssertionError(message); }
}
