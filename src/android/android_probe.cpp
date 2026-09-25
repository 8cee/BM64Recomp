#include <SDL.h>
#include <SDL_log.h>
#include <jni.h>
#include <android/log.h>

extern "C" JNIEXPORT void JNICALL
Java_com_eightcee_bm64recomp_BM64SDLActivity_nativeConfigurePaths(
    JNIEnv*, jclass, jstring, jstring) {
}

extern "C" JNIEXPORT void JNICALL
Java_com_eightcee_bm64recomp_BM64SDLActivity_nativeOnRomSelected(
    JNIEnv*, jclass, jstring) {
}

extern "C" JNIEXPORT void JNICALL
Java_com_eightcee_bm64recomp_BM64SDLActivity_nativeOnModsSelected(
    JNIEnv*, jclass, jobjectArray) {
}

extern "C" int SDL_main(int argc, char** argv) {
    (void)argc;
    (void)argv;

    SDL_LogSetAllPriority(SDL_LOG_PRIORITY_INFO);
    __android_log_print(ANDROID_LOG_INFO, "BM64Probe", "BM64 Android SDL probe starting");

    if (SDL_Init(SDL_INIT_VIDEO | SDL_INIT_GAMECONTROLLER | SDL_INIT_AUDIO) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, "BM64Probe", "SDL_Init failed: %s", SDL_GetError());
        return 1;
    }

    SDL_Window* window = SDL_CreateWindow(
        "BM64 Android Probe",
        SDL_WINDOWPOS_CENTERED,
        SDL_WINDOWPOS_CENTERED,
        1280,
        720,
        SDL_WINDOW_SHOWN | SDL_WINDOW_VULKAN
    );

    if (window == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, "BM64Probe", "SDL_CreateWindow failed: %s", SDL_GetError());
        SDL_Quit();
        return 2;
    }

    __android_log_print(ANDROID_LOG_INFO, "BM64Probe", "SDL Vulkan window created successfully");

    bool running = true;
    while (running) {
        SDL_Event event{};
        while (SDL_PollEvent(&event)) {
            if (event.type == SDL_QUIT) {
                running = false;
            }
        }
        SDL_Delay(16);
    }

    SDL_DestroyWindow(window);
    SDL_Quit();
    return 0;
}
