#include <aaudio/AAudio.h>
#include <jni.h>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstring>

namespace {
constexpr int32_t kSampleRate = 48000;
constexpr int32_t kInputChannels = 2;
constexpr int32_t kOutputChannels = 1;
constexpr int32_t kMaxTaps = 64;
constexpr int32_t kMaxDelaySamples = 4096;
constexpr uint32_t kRingFrames = 16384;
constexpr float kOutputLimit = 0.8912509f;
constexpr float kEpsilon = 1e-6f;
constexpr float kLearningRate = 0.01f;

struct Ring {
    int16_t data[kRingFrames * kInputChannels]{};
    std::atomic<uint32_t> read{0};
    std::atomic<uint32_t> write{0};
    uint32_t available() const { return write.load(std::memory_order_acquire) - read.load(std::memory_order_relaxed); }
    uint32_t freeSpace() const { return kRingFrames - available(); }
    uint32_t push(const int16_t* src, uint32_t frames) {
        const uint32_t count = frames < freeSpace() ? frames : freeSpace();
        const uint32_t w = write.load(std::memory_order_relaxed);
        for (uint32_t i = 0; i < count; ++i) {
            const uint32_t slot = (w + i) % kRingFrames;
            data[slot * kInputChannels] = src[i * kInputChannels];
            data[slot * kInputChannels + 1] = src[i * kInputChannels + 1];
        }
        write.store(w + count, std::memory_order_release);
        return count;
    }
    uint32_t pop(int16_t* dst, uint32_t frames) {
        const uint32_t count = frames < available() ? frames : available();
        const uint32_t r = read.load(std::memory_order_relaxed);
        for (uint32_t i = 0; i < count; ++i) {
            const uint32_t slot = (r + i) % kRingFrames;
            dst[i * kInputChannels] = data[slot * kInputChannels];
            dst[i * kInputChannels + 1] = data[slot * kInputChannels + 1];
        }
        read.store(r + count, std::memory_order_release);
        return count;
    }
    void clear() { read.store(0, std::memory_order_relaxed); write.store(0, std::memory_order_relaxed); }
};

struct AncConfig {
    bool configured = false;
    int32_t expectedInputDevice = 0;
    int32_t expectedOutputDevice = 0;
    int32_t sampleRate = kSampleRate;
    int32_t inputChannels = kInputChannels;
    int32_t referenceChannel = 0;
    int32_t errorChannel = 1;
    int32_t latencySamples = 0;
    float confidence = 0.0f;
    float secondaryPath[kMaxTaps]{};
    int32_t secondaryTaps = 0;
};

struct Engine {
    AAudioStream* input{};
    AAudioStream* output{};
    Ring ring;
    AncConfig config;
    std::atomic<bool> running{false};
    std::atomic<bool> faulted{false};
    std::atomic<int32_t> faultCode{0};
    float weights[kMaxTaps]{};
    float referenceHistory[kMaxTaps]{};
    float filteredReference[kMaxTaps]{};
    float delayHistory[kMaxDelaySamples + 1]{};
    int32_t delayCursor = 0;
    int32_t cursor = 0;
    float maxAbsCoefficient = 0.0f;
};
Engine g;

void resetAdaptiveState() {
    std::memset(g.weights, 0, sizeof(g.weights));
    std::memset(g.referenceHistory, 0, sizeof(g.referenceHistory));
    std::memset(g.filteredReference, 0, sizeof(g.filteredReference));
    std::memset(g.delayHistory, 0, sizeof(g.delayHistory));
    g.delayCursor = 0;
    g.cursor = 0;
    g.maxAbsCoefficient = 0.0f;
}

void fault(int32_t code) {
    g.faultCode.store(code, std::memory_order_release);
    g.faulted.store(true, std::memory_order_release);
    g.running.store(false, std::memory_order_release);
}

float alignReference(float sample) {
    const int32_t delay = g.config.latencySamples;
    g.delayHistory[g.delayCursor] = sample;
    const int32_t size = delay + 1;
    int32_t read = g.delayCursor - delay;
    if (read < 0) read += size;
    const float out = g.delayHistory[read];
    ++g.delayCursor;
    if (g.delayCursor >= size) g.delayCursor = 0;
    return out;
}

float predictAndAdapt(float reference, float error) {
    if (!std::isfinite(reference) || !std::isfinite(error)) {
        fault(4);
        return 0.0f;
    }
    const float aligned = alignReference(reference);
    g.referenceHistory[g.cursor] = aligned;

    float prediction = 0.0f;
    int32_t index = g.cursor;
    for (int32_t i = 0; i < kMaxTaps; ++i) {
        prediction += g.weights[i] * g.referenceHistory[index];
        if (--index < 0) index = kMaxTaps - 1;
    }
    if (!std::isfinite(prediction)) {
        fault(4);
        return 0.0f;
    }

    float filtered = 0.0f;
    index = g.cursor;
    for (int32_t i = 0; i < g.config.secondaryTaps; ++i) {
        filtered += g.config.secondaryPath[i] * g.referenceHistory[index];
        if (--index < 0) index = kMaxTaps - 1;
    }
    if (!std::isfinite(filtered)) {
        fault(4);
        return 0.0f;
    }
    g.filteredReference[g.cursor] = filtered;

    float energy = kEpsilon;
    index = g.cursor;
    for (int32_t i = 0; i < kMaxTaps; ++i) {
        const float x = g.filteredReference[index];
        energy += x * x;
        if (--index < 0) index = kMaxTaps - 1;
    }

    const float step = kLearningRate * error / energy;
    if (!std::isfinite(step)) {
        fault(4);
        return 0.0f;
    }

    index = g.cursor;
    g.maxAbsCoefficient = 0.0f;
    for (int32_t i = 0; i < kMaxTaps; ++i) {
        float next = g.weights[i] + step * g.filteredReference[index];
        if (!std::isfinite(next)) {
            fault(5);
            return 0.0f;
        }
        if (next > 1.0f) next = 1.0f;
        if (next < -1.0f) next = -1.0f;
        g.weights[i] = next;
        g.maxAbsCoefficient = std::fmax(g.maxAbsCoefficient, std::fabs(next));
        if (--index < 0) index = kMaxTaps - 1;
    }
    if (g.maxAbsCoefficient > 1.0f || !std::isfinite(g.maxAbsCoefficient)) {
        fault(5);
        return 0.0f;
    }

    ++g.cursor;
    if (g.cursor == kMaxTaps) g.cursor = 0;
    return std::fmax(-kOutputLimit, std::fmin(kOutputLimit, -prediction));
}

aaudio_data_callback_result_t inputCb(AAudioStream*, void* user, void* data, int32_t frames) {
    auto* e = static_cast<Engine*>(user);
    if (!e->running.load(std::memory_order_relaxed)) return AAUDIO_CALLBACK_RESULT_STOP;
    if (frames <= 0) return AAUDIO_CALLBACK_RESULT_CONTINUE;
    const uint32_t pushed = e->ring.push(static_cast<const int16_t*>(data), static_cast<uint32_t>(frames));
    if (pushed != static_cast<uint32_t>(frames)) {
        fault(2);
        return AAUDIO_CALLBACK_RESULT_STOP;
    }
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

aaudio_data_callback_result_t outputCb(AAudioStream*, void* user, void* data, int32_t frames) {
    auto* e = static_cast<Engine*>(user);
    auto* out = static_cast<int16_t*>(data);
    if (frames <= 0) return AAUDIO_CALLBACK_RESULT_CONTINUE;

    const auto started = std::chrono::steady_clock::now();
    if (!e->running.load(std::memory_order_relaxed) || e->faulted.load(std::memory_order_acquire)) {
        std::memset(out, 0, static_cast<size_t>(frames) * sizeof(int16_t));
        return AAUDIO_CALLBACK_RESULT_STOP;
    }
    if (frames > 2048) {
        fault(6);
        std::memset(out, 0, static_cast<size_t>(frames) * sizeof(int16_t));
        return AAUDIO_CALLBACK_RESULT_STOP;
    }

    // Keep the input frame storage separate from the mono output buffer.
    // The previous implementation could write 2*frames samples into the output buffer.
    int16_t inputFrames[4096]{};
    const uint32_t copied = e->ring.pop(inputFrames, static_cast<uint32_t>(frames));
    if (copied < static_cast<uint32_t>(frames)) {
        std::memset(out, 0, static_cast<size_t>(frames) * sizeof(int16_t));
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }

    for (int32_t i = 0; i < frames; ++i) {
        const float reference = inputFrames[i * kInputChannels + e->config.referenceChannel] / 32768.0f;
        const float error = inputFrames[i * kInputChannels + e->config.errorChannel] / 32768.0f;
        const float antiNoise = predictAndAdapt(reference, error);
        if (e->faulted.load(std::memory_order_acquire)) {
            std::memset(out + i, 0, static_cast<size_t>(frames - i) * sizeof(int16_t));
            return AAUDIO_CALLBACK_RESULT_STOP;
        }
        out[i] = static_cast<int16_t>(antiNoise * 32767.0f);
    }

    const auto elapsedUs = std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now() - started).count();
    const int64_t budgetUs = (static_cast<int64_t>(frames) * 1000000LL) / kSampleRate;
    if (budgetUs > 0 && elapsedUs > (budgetUs * 3) / 4) {
        fault(6);
        std::memset(out, 0, static_cast<size_t>(frames) * sizeof(int16_t));
        return AAUDIO_CALLBACK_RESULT_STOP;
    }
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

void closeStream(AAudioStream*& s) {
    if (!s) return;
    AAudioStream_requestStop(s);
    AAudioStream_close(s);
    s = nullptr;
}

bool openStreams(int32_t inputDeviceId, int32_t outputDeviceId) {
    AAudioStreamBuilder* in = nullptr;
    AAudioStreamBuilder* out = nullptr;
    if (AAudio_createStreamBuilder(&in) != AAUDIO_OK || AAudio_createStreamBuilder(&out) != AAUDIO_OK) {
        if (in) AAudioStreamBuilder_delete(in);
        if (out) AAudioStreamBuilder_delete(out);
        fault(7);
        return false;
    }

    AAudioStreamBuilder_setDirection(in, AAUDIO_DIRECTION_INPUT);
    AAudioStreamBuilder_setSampleRate(in, kSampleRate);
    AAudioStreamBuilder_setChannelCount(in, kInputChannels);
    AAudioStreamBuilder_setFormat(in, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setPerformanceMode(in, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setDeviceId(in, inputDeviceId);
    AAudioStreamBuilder_setDataCallback(in, inputCb, &g);

    AAudioStreamBuilder_setDirection(out, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setSampleRate(out, kSampleRate);
    AAudioStreamBuilder_setChannelCount(out, kOutputChannels);
    AAudioStreamBuilder_setFormat(out, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setPerformanceMode(out, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setDeviceId(out, outputDeviceId);
    AAudioStreamBuilder_setDataCallback(out, outputCb, &g);

    const auto inResult = AAudioStreamBuilder_openStream(in, &g.input);
    const auto outResult = AAudioStreamBuilder_openStream(out, &g.output);
    AAudioStreamBuilder_delete(in);
    AAudioStreamBuilder_delete(out);

    if (inResult != AAUDIO_OK || outResult != AAUDIO_OK) {
        closeStream(g.input);
        closeStream(g.output);
        fault(7);
        return false;
    }

    if (AAudioStream_getDeviceId(g.input) != inputDeviceId ||
        AAudioStream_getDeviceId(g.output) != outputDeviceId ||
        AAudioStream_getChannelCount(g.input) != kInputChannels ||
        AAudioStream_getChannelCount(g.output) != kOutputChannels ||
        AAudioStream_getSampleRate(g.input) != kSampleRate ||
        AAudioStream_getSampleRate(g.output) != kSampleRate) {
        closeStream(g.input);
        closeStream(g.output);
        fault(7);
        return false;
    }
    return true;
}
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_lanu_anc_NativeAudioEngine_lanuNativeConfigureAnc(JNIEnv* env, jobject, jfloatArray taps, jint latency, jfloat confidence, jint inputDeviceId, jint outputDeviceId, jint sampleRateHz, jint inputChannels, jint referenceChannel, jint errorChannel) {
    if (g.running.load(std::memory_order_acquire) || !taps || sampleRateHz != kSampleRate || inputChannels != kInputChannels || referenceChannel != 0 || errorChannel != 1 || latency < 0 || latency > kMaxDelaySamples || !std::isfinite(confidence) || confidence < 0.65f || confidence > 1.0f || inputDeviceId <= 0 || outputDeviceId <= 0) return JNI_FALSE;
    const jsize size = env->GetArrayLength(taps);
    if (size <= 0 || size > kMaxTaps) return JNI_FALSE;
    const jfloat* src = env->GetFloatArrayElements(taps, nullptr);
    if (!src) return JNI_FALSE;
    for (jsize i = 0; i < size; ++i) {
        if (!std::isfinite(src[i])) {
            env->ReleaseFloatArrayElements(taps, const_cast<jfloat*>(src), JNI_ABORT);
            return JNI_FALSE;
        }
        g.config.secondaryPath[i] = src[i];
    }
    env->ReleaseFloatArrayElements(taps, const_cast<jfloat*>(src), JNI_ABORT);
    for (jsize i = size; i < kMaxTaps; ++i) g.config.secondaryPath[i] = 0.0f;
    g.config.secondaryTaps = size;
    g.config.latencySamples = latency;
    g.config.confidence = confidence;
    g.config.expectedInputDevice = inputDeviceId;
    g.config.expectedOutputDevice = outputDeviceId;
    g.config.sampleRate = sampleRateHz;
    g.config.inputChannels = inputChannels;
    g.config.referenceChannel = referenceChannel;
    g.config.errorChannel = errorChannel;
    g.config.configured = true;
    g.faulted.store(false, std::memory_order_release);
    g.faultCode.store(0, std::memory_order_release);
    resetAdaptiveState();
    return JNI_TRUE;
}
extern "C" JNIEXPORT void JNICALL Java_com_lanu_anc_NativeAudioEngine_lanuNativeClearAncConfiguration(JNIEnv*, jobject) { if (g.running.load(std::memory_order_acquire)) return; g.config = AncConfig{}; g.faulted.store(false, std::memory_order_release); g.faultCode.store(0, std::memory_order_release); resetAdaptiveState(); }
extern "C" JNIEXPORT jboolean JNICALL Java_com_lanu_anc_NativeAudioEngine_lanuNativeAncConfigured(JNIEnv*, jobject) { return g.config.configured ? JNI_TRUE : JNI_FALSE; }
extern "C" JNIEXPORT jboolean JNICALL Java_com_lanu_anc_NativeAudioEngine_lanuNativeAncFaulted(JNIEnv*, jobject) { return g.faulted.load(std::memory_order_acquire) ? JNI_TRUE : JNI_FALSE; }
extern "C" JNIEXPORT jint JNICALL Java_com_lanu_anc_NativeAudioEngine_lanuNativeAncFaultCode(JNIEnv*, jobject) { return g.faultCode.load(std::memory_order_acquire); }
extern "C" JNIEXPORT jboolean JNICALL Java_com_lanu_anc_NativeAudioEngine_lanuNativeStart(JNIEnv*, jobject, jint inputDeviceId, jint outputDeviceId) {
    if (g.running.load(std::memory_order_acquire)) return JNI_TRUE;
    if (!g.config.configured || inputDeviceId != g.config.expectedInputDevice || outputDeviceId != g.config.expectedOutputDevice) { fault(7); return JNI_FALSE; }
    g.ring.clear();
    resetAdaptiveState();
    g.faulted.store(false, std::memory_order_release);
    g.faultCode.store(0, std::memory_order_release);
    if (!openStreams(inputDeviceId, outputDeviceId)) return JNI_FALSE;
    g.running.store(true, std::memory_order_release);
    if (AAudioStream_requestStart(g.input) != AAUDIO_OK || AAudioStream_requestStart(g.output) != AAUDIO_OK) {
        g.running.store(false, std::memory_order_release);
        closeStream(g.input);
        closeStream(g.output);
        fault(7);
        return JNI_FALSE;
    }
    return JNI_TRUE;
}
extern "C" JNIEXPORT void JNICALL Java_com_lanu_anc_NativeAudioEngine_lanuNativeStop(JNIEnv*, jobject) { g.running.store(false, std::memory_order_release); closeStream(g.input); closeStream(g.output); }
extern "C" JNIEXPORT jboolean JNICALL Java_com_lanu_anc_NativeAudioEngine_lanuNativeIsRunning(JNIEnv*, jobject) { return g.running.load(std::memory_order_acquire) ? JNI_TRUE : JNI_FALSE; }
extern "C" JNIEXPORT jint JNICALL Java_com_lanu_anc_NativeAudioEngine_lanuNativeSampleRate(JNIEnv*, jobject) { return g.output ? AAudioStream_getSampleRate(g.output) : 0; }
extern "C" JNIEXPORT jint JNICALL Java_com_lanu_anc_NativeAudioEngine_lanuNativeFramesPerBurst(JNIEnv*, jobject) { return g.output ? AAudioStream_getFramesPerBurst(g.output) : 0; }
extern "C" JNIEXPORT jint JNICALL Java_com_lanu_anc_NativeAudioEngine_lanuNativeBufferSizeInFrames(JNIEnv*, jobject) { return g.output ? AAudioStream_getBufferSizeInFrames(g.output) : 0; }
extern "C" JNIEXPORT jint JNICALL Java_com_lanu_anc_NativeAudioEngine_lanuNativeXRunCount(JNIEnv*, jobject) { return g.output ? AAudioStream_getXRunCount(g.output) : 0; }
extern "C" JNIEXPORT jint JNICALL Java_com_lanu_anc_NativeAudioEngine_lanuNativeInputDeviceId(JNIEnv*, jobject) { return g.input ? AAudioStream_getDeviceId(g.input) : 0; }
extern "C" JNIEXPORT jint JNICALL Java_com_lanu_anc_NativeAudioEngine_lanuNativeOutputDeviceId(JNIEnv*, jobject) { return g.output ? AAudioStream_getDeviceId(g.output) : 0; }
extern "C" JNIEXPORT jint JNICALL Java_com_lanu_anc_NativeAudioEngine_lanuNativeInputChannelCount(JNIEnv*, jobject) { return g.input ? AAudioStream_getChannelCount(g.input) : 0; }
extern "C" JNIEXPORT jint JNICALL Java_com_lanu_anc_NativeAudioEngine_lanuNativeOutputChannelCount(JNIEnv*, jobject) { return g.output ? AAudioStream_getChannelCount(g.output) : 0; }
