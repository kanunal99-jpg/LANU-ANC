# LANU Audio Engine — Implementation Plan

## Phase 0 — Repository and build foundation
- Android application module
- Kotlin + Jetpack Compose UI
- Native C++ module via CMake
- Minimum Android version selected for Oboe compatibility
- Release/debug build variants
- CI build verification

## Phase 1 — Device analyzer
Expose:
- input/output devices
- current route
- microphone availability
- channel count
- sample rate
- supported Android AudioEffects
- Bluetooth/BLE/USB/wired route state
- permission state

No processing yet beyond a safe audio probe.

## Phase 2 — Native low-latency engine
Implement:
- AudioEngine lifecycle
- input stream
- output stream
- callback-safe ring buffers
- no blocking work in callback
- underrun/overrun counters
- start/stop/reconfigure state machine
- safe teardown

Preferred native backend: Oboe/AAudio where available.

## Phase 3 — DSP baseline
Implement safe, deterministic DSP:
- DC blocking/high-pass where appropriate
- level metering
- limiter/clip protection
- optional basic spectral/noise gate only after measurement

## Phase 4 — WebRTC APM integration
Add independently controllable:
- Acoustic Echo Cancellation
- Noise Suppression
- Automatic Gain Control
- Voice activity detection where useful

The integration must be tested for sample-rate/frame-size requirements before enabling it in production.

## Phase 5 — Local AI noise suppression
Add an optional local model adapter. RNNoise-class processing is a candidate, but the implementation must validate licensing, ABI, CPU cost and audio quality before shipping.

AI processing must be adaptive and must not blindly stack multiple aggressive suppressors.

## Phase 6 — Adaptive controller
Inputs:
- noise estimate
- speech probability
- SNR
- clipping
- input level
- processor health
- device/route capabilities

Outputs:
- selected processing profile
- NS strength
- AI enable/disable
- safe fallback

## Phase 7 — Android service and routing
- runtime RECORD_AUDIO permission
- microphone foreground service when required
- transparent user start/stop
- notification for active background microphone processing
- route changes handled without crashing
- Bluetooth/wired/USB lifecycle handling

## Phase 8 — Audio Lab UI
Show measured values only:
- input level
- estimated noise level
- SNR/relative improvement
- processing mode
- route
- sample rate
- channels
- underruns
- measured latency where methodology supports it
- CPU/memory diagnostics

## Phase 9 — Test matrix
Minimum scenarios:
1. Silent room
2. Fan
3. Air conditioner
4. Vehicle/engine
5. Traffic
6. Crowd
7. Nearby speech
8. Keyboard
9. Wind
10. Music

Each scenario compares raw and processed speech quality and documents artifacts.

## Phase 10 — Release gate
Required before APK release:
- clean release build
- installation on physical Android device
- permission flow verified
- audio start/stop verified
- route changes verified
- Bluetooth/wired route verified where available
- background lifecycle verified
- process death recovery verified
- no callback crashes
- long-duration stress test
- battery/CPU observation
- no fabricated performance claims

## Priority
P0: build + native engine + microphone + safe start/stop
P1: device analyzer + WebRTC APM
P2: Audio Lab + measurements
P3: local AI
P4: adaptive optimization and extended device compatibility
