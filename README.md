# LANU ANC / LANU Audio Engine

## Product definition
LANU is an Android real-time audio enhancement application. Its primary function is software-based noise suppression and voice enhancement that can operate even when the phone and connected headset do not contain hardware ANC.

**Important:** LANU does not claim to turn a non-ANC headset into a hardware ANC headset. Hardware ANC remains dependent on compatible headset hardware. LANU's core value is real-time microphone/audio processing.

## Supported target scenarios
- Phone microphone + local processing
- Wired headset microphone
- USB audio where supported
- Bluetooth headset microphone where Android exposes a usable input route
- BLE Audio where the device/headset support it
- LANU-owned VoIP/communication audio pipeline

## Processing stack
1. Android audio capability detection
2. Low-latency capture/output using Oboe/AAudio where practical
3. Native C++ real-time audio engine
4. WebRTC Audio Processing Module for AEC/NS/AGC where appropriate
5. Local AI noise suppression as an optional adaptive stage
6. Native DSP filters and safety limiter
7. Device-specific routing and fallback
8. Measurement and diagnostics

## Non-goals
- No claim of universal hardware ANC control
- No DRM/call interception
- No assumption that third-party apps can replace the audio path of every GSM phone call
- No cloud requirement for basic noise suppression
- No fabricated performance percentages

## Performance philosophy
All claims about noise reduction, speech quality, latency, CPU, memory and battery must be measured on real devices. The UI must not present invented dB or percentage improvements.

## Initial acceptance criteria
- Builds as a real Android APK
- Requests microphone permission transparently
- Uses a foreground microphone service only after user action when background processing is needed
- Detects available audio devices and processing capabilities
- Starts/stops audio processing safely
- Has a passthrough/safe fallback when an advanced processor fails
- Does not crash on unsupported audio configurations
- Provides diagnostics for sample rate, channels, route and latency
- Provides raw-vs-processed recording/measurement only where Android permissions and routing allow it
- Includes unit/instrumentation tests for the audio state machine and failure paths

## Engineering rule
Do not label software noise suppression as hardware ANC. Product copy, code comments, tests and documentation must preserve this distinction.

## Build gate
A release is not considered produced until GitHub Actions completes the Android debug build, unit tests, SHA-256 generation and APK artifact upload successfully.

## Verification branch
The final APK build is being verified from the current main revision before any downloadable release is declared.