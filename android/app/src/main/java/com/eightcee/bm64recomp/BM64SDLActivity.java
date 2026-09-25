package com.eightcee.bm64recomp;

import org.libsdl.app.SDLActivity;

public class BM64SDLActivity extends SDLActivity {
    @Override
    protected String[] getLibraries() {
        return new String[] { "SDL2", "main" };
    }
}
