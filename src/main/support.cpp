#include "zelda_support.h"
#include <SDL.h>
#if defined(__ANDROID__)
#include <SDL_system.h>
#include <jni.h>
#include <mutex>
#include <string>
#else
#include "nfd.h"
#endif
#include "RmlUi/Core.h"

#if defined(__ANDROID__)
namespace {
std::mutex android_dialog_mutex;
std::function<void(bool, const std::filesystem::path&)> android_single_dialog_callback;
std::function<void(bool, const std::list<std::filesystem::path>&)> android_multi_dialog_callback;
std::filesystem::path android_program_path;
std::filesystem::path android_app_path;

std::string jstring_to_utf8(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string out(chars);
    env->ReleaseStringUTFChars(value, chars);
    return out;
}

bool invoke_android_activity_method(const char* method_name) {
    auto* env = static_cast<JNIEnv*>(SDL_AndroidGetJNIEnv());
    jobject activity = SDL_AndroidGetActivity();
    if (env == nullptr || activity == nullptr) return false;
    jclass cls = env->GetObjectClass(activity);
    if (cls == nullptr) {
        env->DeleteLocalRef(activity);
        return false;
    }
    jmethodID method = env->GetMethodID(cls, method_name, "()V");
    if (method == nullptr) {
        env->ExceptionClear();
        env->DeleteLocalRef(cls);
        env->DeleteLocalRef(activity);
        return false;
    }
    env->CallVoidMethod(activity, method);
    const bool ok = !env->ExceptionCheck();
    if (!ok) env->ExceptionClear();
    env->DeleteLocalRef(cls);
    env->DeleteLocalRef(activity);
    return ok;
}
}

extern "C" JNIEXPORT void JNICALL
Java_com_eightcee_bm64recomp_BM64SDLActivity_nativeConfigurePaths(
    JNIEnv* env, jclass, jstring program_path, jstring app_path) {
    android_program_path = jstring_to_utf8(env, program_path);
    android_app_path = jstring_to_utf8(env, app_path);
}

extern "C" JNIEXPORT void JNICALL
Java_com_eightcee_bm64recomp_BM64SDLActivity_nativeOnRomSelected(
    JNIEnv* env, jclass, jstring path_string) {
    std::function<void(bool, const std::filesystem::path&)> callback;
    {
        std::lock_guard lock(android_dialog_mutex);
        callback = std::move(android_single_dialog_callback);
        android_single_dialog_callback = {};
    }
    if (!callback) return;
    if (path_string == nullptr) {
        callback(false, {});
        return;
    }
    callback(true, std::filesystem::path{jstring_to_utf8(env, path_string)});
}

extern "C" JNIEXPORT void JNICALL
Java_com_eightcee_bm64recomp_BM64SDLActivity_nativeOnModsSelected(
    JNIEnv* env, jclass, jobjectArray paths) {
    std::function<void(bool, const std::list<std::filesystem::path>&)> callback;
    {
        std::lock_guard lock(android_dialog_mutex);
        callback = std::move(android_multi_dialog_callback);
        android_multi_dialog_callback = {};
    }
    if (!callback) return;

    std::list<std::filesystem::path> out;
    if (paths != nullptr) {
        const jsize count = env->GetArrayLength(paths);
        for (jsize i = 0; i < count; i++) {
            auto value = static_cast<jstring>(env->GetObjectArrayElement(paths, i));
            if (value != nullptr) {
                out.emplace_back(jstring_to_utf8(env, value));
                env->DeleteLocalRef(value);
            }
        }
    }
    callback(!out.empty(), out);
}
#endif

namespace zelda64 {
    void perform_file_dialog_operation(const std::function<void(bool, const std::filesystem::path&)>& callback) {
#if defined(__ANDROID__)
        {
            std::lock_guard lock(android_dialog_mutex);
            android_single_dialog_callback = callback;
        }
        if (!invoke_android_activity_method("openRomFilePicker")) {
            std::lock_guard lock(android_dialog_mutex);
            auto failed = std::move(android_single_dialog_callback);
            android_single_dialog_callback = {};
            if (failed) failed(false, {});
        }
#else
        nfdnchar_t* native_path = nullptr;
        nfdresult_t result = NFD_OpenDialogN(&native_path, nullptr, 0, nullptr);

        bool success = (result == NFD_OKAY);
        std::filesystem::path path;

        if (success) {
            path = std::filesystem::path{native_path};
            NFD_FreePathN(native_path);
        }

        callback(success, path);
#endif
    }

    void perform_file_dialog_operation_multiple(const std::function<void(bool, const std::list<std::filesystem::path>&)>& callback) {
#if defined(__ANDROID__)
        {
            std::lock_guard lock(android_dialog_mutex);
            android_multi_dialog_callback = callback;
        }
        if (!invoke_android_activity_method("openModFilePicker")) {
            std::lock_guard lock(android_dialog_mutex);
            auto failed = std::move(android_multi_dialog_callback);
            android_multi_dialog_callback = {};
            if (failed) failed(false, {});
        }
#else
        const nfdpathset_t* native_paths = nullptr;
        nfdresult_t result = NFD_OpenDialogMultipleN(&native_paths, nullptr, 0, nullptr);

        bool success = (result == NFD_OKAY);
        std::list<std::filesystem::path> paths;
        nfdpathsetsize_t count = 0;

        if (success) {
            NFD_PathSet_GetCount(native_paths, &count);
            for (nfdpathsetsize_t i = 0; i < count; i++) {
                nfdnchar_t* cur_path = nullptr;
                nfdresult_t cur_result = NFD_PathSet_GetPathN(native_paths, i, &cur_path);
                if (cur_result == NFD_OKAY) {
                    paths.emplace_back(std::filesystem::path{cur_path});
                }
            }
            NFD_PathSet_Free(native_paths);
        }

        callback(success, paths);
#endif
    }

    std::filesystem::path get_program_path() {
#if defined(__ANDROID__)
        return android_program_path;
#elif defined(__APPLE__)
        return get_bundle_resource_directory();
#elif defined(__linux__) && defined(RECOMP_FLATPAK)
        return "/app/bin";
#else
        return "";
#endif
    }

#if defined(__ANDROID__)
    std::filesystem::path get_android_app_folder_path() {
        return android_app_path;
    }
#endif

    std::filesystem::path get_asset_path(const char* asset) {
        return get_program_path() / "assets" / asset;
    }

    void open_file_dialog(std::function<void(bool success, const std::filesystem::path& path)> callback) {
#ifdef __APPLE__
        dispatch_on_ui_thread([callback]() { perform_file_dialog_operation(callback); });
#else
        perform_file_dialog_operation(callback);
#endif
    }

    void open_file_dialog_multiple(std::function<void(bool success, const std::list<std::filesystem::path>& paths)> callback) {
#ifdef __APPLE__
        dispatch_on_ui_thread([callback]() { perform_file_dialog_operation_multiple(callback); });
#else
        perform_file_dialog_operation_multiple(callback);
#endif
    }

    void open_mod_server(std::function<void(bool success, const std::list<std::filesystem::path>& paths)> callback) {
#if defined(__ANDROID__)
        {
            std::lock_guard lock(android_dialog_mutex);
            android_multi_dialog_callback = callback;
        }
        if (!invoke_android_activity_method("openModServerBrowser")) {
            std::lock_guard lock(android_dialog_mutex);
            auto failed = std::move(android_multi_dialog_callback);
            android_multi_dialog_callback = {};
            if (failed) failed(false, {});
        }
#else
        callback(false, {});
#endif
    }

    void show_error_message_box(const char *title, const char *message) {
#ifdef __APPLE__
        std::string title_copy(title);
        std::string message_copy(message);
        dispatch_on_ui_thread([title_copy, message_copy] {
            SDL_ShowSimpleMessageBox(SDL_MESSAGEBOX_ERROR, title_copy.c_str(), message_copy.c_str(), nullptr);
        });
#else
        SDL_ShowSimpleMessageBox(SDL_MESSAGEBOX_ERROR, title, message, nullptr);
#endif
    }
}
