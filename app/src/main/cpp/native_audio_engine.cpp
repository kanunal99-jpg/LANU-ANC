#include <aaudio/AAudio.h>
#include <atomic>
#include <cstring>

namespace {
constexpr int32_t kSampleRate = 48000;
constexpr int32_t kChannels = 1;
constexpr uint32_t kRingFrames = 16384;

struct Ring {
    int16_t data[kRingFrames]{};
    std::atomic<uint32_t> read{0};
    std::atomic<uint32_t> write{0};
    uint32_t available() const { return write.load(std::memory_order_acquire) - read.load(std::memory_order_relaxed); }
    uint32_t freeSpace() const { return kRingFrames - available(); }
    uint32_t push(const int16_t* src, uint32_t n) {
        const uint32_t count = n < freeSpace() ? n : freeSpace();
        const uint32_t w = write.load(std::memory_order_relaxed);
        for (uint32_t i = 0; i < count; ++i) data[(w + i) % kRingFrames] = src[i];
        write.store(w + count, std::memory_order_release);
        return count;
    }
    uint32_t pop(int16_t* dst, uint32_t n) {
        const uint32_t count = n < available() ? n : available();
        const uint32_t r = read.load(std::memory_order_relaxed);
        for (uint32_t i = 0; i < count; ++i) dst[i] = data[(r + i) % kRingFrames];
        read.store(r + count, std::memory_order_release);
        return count;
    }
};

struct Engine { AAudioStream* input{}; AAudioStream* output{}; Ring ring; std::atomic<bool> running{false}; };
Engine g;

AAudioStream_dataCallbackResult inputCb(AAudioStream*, void* user, void* data, int32_t frames) {
    auto* e = static_cast<Engine*>(user);
    if (!e->running.load(std::memory_order_relaxed)) return AAUDIO_CALLBACK_RESULT_STOP;
    e->ring.push(static_cast<const int16_t*>(data), static_cast<uint32_t>(frames));
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

AAudioStream_dataCallbackResult outputCb(AAudioStream*, void* user, void* data, int32_t frames) {
    auto* e = static_cast<Engine*>(user);
    auto* out = static_cast<int16_t*>(data);
    const uint32_t requested = static_cast<uint32_t>(frames);
    const uint32_t copied = e->ring.pop(out, requested);
    if (copied < requested) std::memset(out + copied, 0, (requested - copied) * sizeof(int16_t));
    return e->running.load(std::memory_order_relaxed) ? AAUDIO_CALLBACK_RESULT_CONTINUE : AAUDIO_CALLBACK_RESULT_STOP;
}

void closeStream(AAudioStream*& s) {
    if (!s) return;
    AAudioStream_requestStop(s);
    AAudioStream_close(s);
    s = nullptr;
}

bool openStreams() {
    AAudioStreamBuilder* in = nullptr;
    AAudioStreamBuilder* out = nullptr;
    if (AAudio_createStreamBuilder(&in) != AAUDIO_OK || AAudio_createStreamBuilder(&out) != AAUDIO_OK) {
        if (in) AAudioStreamBuilder_delete(in);
        if (out) AAudioStreamBuilder_delete(out);
        return false;
    }
    AAudioStreamBuilder_setDirection(in, AAUDIO_DIRECTION_INPUT);
    AAudioStreamBuilder_setSampleRate(in, kSampleRate);
    AAudioStreamBuilder_setChannelCount(in, kChannels);
    AAudioStreamBuilder_setFormat(in, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setPerformanceMode(in, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setInputPreset(in, AAUDIO_INPUT_PRESET_VOICE_PERFORMANCE);
    AAudioStreamBuilder_setDataCallback(in, inputCb, &g);

    AAudioStreamBuilder_setDirection(out, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setSampleRate(out, kSampleRate);
    AAudioStreamBuilder_setChannelCount(out, kChannels);
    AAudioStreamBuilder_setFormat(out, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setPerformanceMode(out, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setDataCallback(out, outputCb, &g);

    const auto inResult = AAudioStreamBuilder_openStream(in, &g.input);
    const auto outResult = AAudioStreamBuilder_openStream(out, &g.output);
    AAudioStreamBuilder_delete(in);
    AAudioStreamBuilder_delete(out);
    if (inResult != AAUDIO_OK || outResult != AAUDIO_OK) { closeStream(g.input); closeStream(g.output); return false; }
    return true;
}
}

extern "C" bool lanuNativeStart() {
    if (g.running.load(std::memory_order_acquire)) return true;
    if (!openStreams()) return false;
    g.running.store(true, std::memory_order_release);
    if (AAudioStream_requestStart(g.input) != AAUDIO_OK || AAudioStream_requestStart(g.output) != AAUDIO_OK) {
        g.running.store(false, std::memory_order_release);
        closeStream(g.input); closeStream(g.output);
        return false;
    }
    return true;
}

extern "C" void lanuNativeStop() {
    g.running.store(false, std::memory_order_release);
    closeStream(g.input); closeStream(g.output);
}

extern "C" bool lanuNativeIsRunning() { return g.running.load(std::memory_order_acquire); }
