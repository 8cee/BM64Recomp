#include <array>
#include <atomic>
#include <cmath>

#if defined(__ANDROID__)
#include <jni.h>
#include "SDL.h"
#endif

#include "librecomp/helpers.hpp"
#include "recomp_input.h"
#include "ultramodern/ultramodern.hpp"

// Arrays that hold the mappings for every input for keyboard and controller respectively.
using input_mapping = std::array<recomp::InputField, recomp::bindings_per_input>;
using input_mapping_array = std::array<input_mapping, static_cast<size_t>(recomp::GameInput::COUNT)>;
static input_mapping_array keyboard_input_mappings{};
static input_mapping_array controller_input_mappings{};

#if defined(__ANDROID__)
namespace {
constexpr int TOUCH_PAD_COUNT = 15;
constexpr uint16_t TOUCH_N64_MASKS[TOUCH_PAD_COUNT] = {
    0x8000, // A
    0x4000, // B
    0x2000, // Z
    0x0020, // L
    0x0010, // R
    0x1000, // START
    0x0008, // C_UP
    0x0004, // C_DOWN
    0x0002, // C_LEFT
    0x0001, // C_RIGHT
    0x0800, // DPAD_UP
    0x0400, // DPAD_DOWN
    0x0200, // DPAD_LEFT
    0x0100, // DPAD_RIGHT
    0x0000  // MENU (Recomp UI only)
};

std::atomic<uint16_t> touch_buttons{0};
std::atomic<float> touch_stick_x{0.0f};
std::atomic<float> touch_stick_y{0.0f};

void push_touch_button_event(int id, bool pressed) {
    int sdl_button = -1;
    switch (id) {
        case 0: sdl_button = SDL_CONTROLLER_BUTTON_A; break;
        case 1: sdl_button = SDL_CONTROLLER_BUTTON_X; break;
        case 10: sdl_button = SDL_CONTROLLER_BUTTON_DPAD_UP; break;
        case 11: sdl_button = SDL_CONTROLLER_BUTTON_DPAD_DOWN; break;
        case 12: sdl_button = SDL_CONTROLLER_BUTTON_DPAD_LEFT; break;
        case 13: sdl_button = SDL_CONTROLLER_BUTTON_DPAD_RIGHT; break;
        case 14: sdl_button = SDL_CONTROLLER_BUTTON_BACK; break;
        default: break;
    }
    if (sdl_button < 0 || !SDL_WasInit(SDL_INIT_GAMECONTROLLER)) return;
    SDL_Event ev{};
    ev.type = pressed ? SDL_CONTROLLERBUTTONDOWN : SDL_CONTROLLERBUTTONUP;
    ev.cbutton.which = 0x7650;
    ev.cbutton.button = static_cast<Uint8>(sdl_button);
    ev.cbutton.state = pressed ? SDL_PRESSED : SDL_RELEASED;
    SDL_PushEvent(&ev);
}

void push_touch_axis_events(float x, float y) {
    if (!SDL_WasInit(SDL_INIT_GAMECONTROLLER)) return;
    SDL_Event ex{};
    ex.type = SDL_CONTROLLERAXISMOTION;
    ex.caxis.which = 0x7650;
    ex.caxis.axis = SDL_CONTROLLER_AXIS_LEFTX;
    ex.caxis.value = static_cast<Sint16>(std::clamp(x, -1.0f, 1.0f) * 32767.0f);
    SDL_PushEvent(&ex);

    SDL_Event ey{};
    ey.type = SDL_CONTROLLERAXISMOTION;
    ey.caxis.which = 0x7650;
    ey.caxis.axis = SDL_CONTROLLER_AXIS_LEFTY;
    // SDL menu navigation expects up to be negative Y.
    ey.caxis.value = static_cast<Sint16>(std::clamp(-y, -1.0f, 1.0f) * 32767.0f);
    SDL_PushEvent(&ey);
}
}

extern "C" JNIEXPORT void JNICALL
Java_com_eightcee_bm64recomp_VirtualPadView_nativeButton(JNIEnv*, jobject, jint id, jboolean pressed) {
    if (id < 0 || id >= TOUCH_PAD_COUNT) return;
    const uint16_t mask = TOUCH_N64_MASKS[id];
    uint16_t cur = touch_buttons.load(std::memory_order_relaxed);
    for (;;) {
        uint16_t next = pressed == JNI_TRUE ? static_cast<uint16_t>(cur | mask)
                                            : static_cast<uint16_t>(cur & ~mask);
        if (touch_buttons.compare_exchange_weak(cur, next, std::memory_order_relaxed)) break;
    }
    push_touch_button_event(id, pressed == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_eightcee_bm64recomp_VirtualPadView_nativeAxis(JNIEnv*, jobject, jfloat x, jfloat y) {
    float fx = std::clamp(static_cast<float>(x), -1.0f, 1.0f);
    float fy = std::clamp(static_cast<float>(y), -1.0f, 1.0f);
    const float mag2 = fx * fx + fy * fy;
    if (mag2 > 1.0f) {
        const float inv = 1.0f / std::sqrt(mag2);
        fx *= inv;
        fy *= inv;
    }
    touch_stick_x.store(fx, std::memory_order_relaxed);
    touch_stick_y.store(fy, std::memory_order_relaxed);
    push_touch_axis_events(fx, fy);
}
#endif

// Make the button value array, which maps a button index to its bit field.
#define DEFINE_INPUT(name, value, readable) uint16_t(value##u),
static const std::array n64_button_values = {
    DEFINE_N64_BUTTON_INPUTS()
};
#undef DEFINE_INPUT

// Make the input name array.
#define DEFINE_INPUT(name, value, readable) readable,
static const std::vector<std::string> input_names = {
    DEFINE_ALL_INPUTS()
};
#undef DEFINE_INPUT

// Make the input enum name array.
#define DEFINE_INPUT(name, value, readable) #name,
static const std::vector<std::string> input_enum_names = {
    DEFINE_ALL_INPUTS()
};
#undef DEFINE_INPUT

size_t recomp::get_num_inputs() {
    return (size_t)GameInput::COUNT;
}

const std::string& recomp::get_input_name(GameInput input) {
    return input_names.at(static_cast<size_t>(input));
}

const std::string& recomp::get_input_enum_name(GameInput input) {
    return input_enum_names.at(static_cast<size_t>(input));
}

recomp::GameInput recomp::get_input_from_enum_name(const std::string_view enum_name) {
    auto find_it = std::find(input_enum_names.begin(), input_enum_names.end(), enum_name);
    if (find_it == input_enum_names.end()) {
        return recomp::GameInput::COUNT;
    }

    return static_cast<recomp::GameInput>(find_it - input_enum_names.begin());
}

// Due to an RmlUi limitation this can't be const. Ideally it would return a const reference or even just a straight up copy.
recomp::InputField& recomp::get_input_binding(GameInput input, size_t binding_index, recomp::InputDevice device) {
    input_mapping_array& device_mappings = (device == recomp::InputDevice::Controller) ?  controller_input_mappings : keyboard_input_mappings;
    input_mapping& cur_input_mapping = device_mappings.at(static_cast<size_t>(input));

    if (binding_index < cur_input_mapping.size()) {
        return cur_input_mapping[binding_index];
    }
    else {
        static recomp::InputField dummy_field = {};
        return dummy_field;
    }
}

void recomp::set_input_binding(recomp::GameInput input, size_t binding_index, recomp::InputDevice device, recomp::InputField value) {
    input_mapping_array& device_mappings = (device == recomp::InputDevice::Controller) ?  controller_input_mappings : keyboard_input_mappings;
    input_mapping& cur_input_mapping = device_mappings.at(static_cast<size_t>(input));

    if (binding_index < cur_input_mapping.size()) {
        cur_input_mapping[binding_index] = value;
    }
}

bool recomp::get_n64_input(int controller_num, uint16_t* buttons_out, float* x_out, float* y_out) {
    uint16_t cur_buttons = 0;
    float cur_x = 0.0f;
    float cur_y = 0.0f;
    
    if (controller_num != 0) {
        return false;
    }

    if (!recomp::game_input_disabled()) {
        for (size_t i = 0; i < n64_button_values.size(); i++) {
            size_t input_index = (size_t)GameInput::N64_BUTTON_START + i;
            cur_buttons |= recomp::get_input_digital(keyboard_input_mappings[input_index]) ? n64_button_values[i] : 0;
            cur_buttons |= recomp::get_input_digital(controller_input_mappings[input_index]) ? n64_button_values[i] : 0;
        }

        float joystick_deadzone = recomp::get_joystick_deadzone() / 100.0f;

        float joystick_x = recomp::get_input_analog(controller_input_mappings[(size_t)GameInput::X_AXIS_POS])
                        - recomp::get_input_analog(controller_input_mappings[(size_t)GameInput::X_AXIS_NEG]);

        float joystick_y = recomp::get_input_analog(controller_input_mappings[(size_t)GameInput::Y_AXIS_POS])
                        - recomp::get_input_analog(controller_input_mappings[(size_t)GameInput::Y_AXIS_NEG]);

        recomp::apply_joystick_deadzone(joystick_x, joystick_y, &joystick_x, &joystick_y);

        cur_x = recomp::get_input_analog(keyboard_input_mappings[(size_t)GameInput::X_AXIS_POS])
                - recomp::get_input_analog(keyboard_input_mappings[(size_t)GameInput::X_AXIS_NEG]) + joystick_x;

        cur_y = recomp::get_input_analog(keyboard_input_mappings[(size_t)GameInput::Y_AXIS_POS])
                - recomp::get_input_analog(keyboard_input_mappings[(size_t)GameInput::Y_AXIS_NEG]) + joystick_y;
    }

#if defined(__ANDROID__)
    if (!recomp::game_input_disabled()) {
        cur_buttons = static_cast<uint16_t>(cur_buttons | touch_buttons.load(std::memory_order_relaxed));
    }
#endif

    *buttons_out = cur_buttons;
    float out_x = std::clamp(cur_x * 0.65f, -1.0f, 1.0f);
    float out_y = std::clamp(cur_y * 0.65f, -1.0f, 1.0f);

#if defined(__ANDROID__)
    if (!recomp::game_input_disabled()) {
        const float tx = touch_stick_x.load(std::memory_order_relaxed);
        const float ty = touch_stick_y.load(std::memory_order_relaxed);
        const float touch_mag2 = tx * tx + ty * ty;
        const float current_mag2 = out_x * out_x + out_y * out_y;
        if (touch_mag2 > current_mag2) {
            out_x = tx;
            out_y = ty;
        }
    }
#endif

    *x_out = out_x;
    *y_out = out_y;

    return true;
}
