# Candy Recorder

A geek-grade Android audio recorder written entirely in Java on top of the platform media stack.
No FFmpeg, no native code, no UI toolkit beyond the Android framework; the only library is
`androidx.annotation`.

## Two engines

| | Engine A | Engine B |
|---|---|---|
| API | `MediaRecorder` | `AudioRecord` + `MediaCodec` / `MediaMuxer` |
| Containers | M4A, MP4, 3GP, OGG (Opus, API 29+), AAC-ADTS, MPEG-2 TS, AMR, AWB | WAV, raw PCM, FLAC, M4A (AAC-LC / HE-AAC / AAC-ELD), OGG (Opus, API 29+) |
| Sample format | encoder managed | 8-bit, 16-bit, 24-bit packed (API 31), 32-bit int (API 31), 32-bit float (API 23) |
| Metering | peak envelope (`getMaxAmplitude`) | full PCM: oscilloscope, log-frequency FFT spectrum, peak / RMS, clip, drift |
| Silence skip | `pause()` / `resume()` (API 24+) | PCM chunks dropped before the sink |
| Effects | follows the audio-source preset | `NoiseSuppressor`, `AcousticEchoCanceler`, `AutomaticGainControl` per session |

## Parameters (Setup screen)

- Audio source: DEFAULT, MIC, CAMCORDER, VOICE_RECOGNITION, VOICE_COMMUNICATION, UNPROCESSED,
  VOICE_PERFORMANCE, internal playback capture (MediaProjection, API 29+), and the privileged
  REMOTE_SUBMIX / VOICE_CALL / UPLINK / DOWNLINK sources for system or rooted builds
  (`CAPTURE_AUDIO_OUTPUT` is declared in the manifest).
- Channels, sample rate (8 kHz to 192 kHz), bitrate, bitrate mode (CBR / VBR / CQ), FLAC level,
  AudioRecord buffer multiplier, privacy-sensitive capture.
- Raw / pure audio mode: UNPROCESSED source when supported plus every effect forced off.
- Directional pickup: `MicrophoneDirection` (towards / away / external) and field dimension zoom.
- Input device: automatic, built-in mic, wired headset, Bluetooth headset (SCO link opened with
  `AudioManager.startBluetoothSco()` before capture), USB / Type-C audio (`setPreferredDevice`).
- Silence auto-skip with threshold and hold time.
- Storage: app external folder (`Android/data/<pkg>/files/Recordings`, no permission) or the public
  `Music/CandyRecorder` folder (legacy storage access).
- Dashboard: FFT size and meter refresh rate.

## Dashboard

Oscilloscope, 56-band log spectrum with peak hold, stereo peak / RMS meters with hold ticks and clip
indicator, and a live telemetry panel: HAL vs client format, routed device, active microphones,
buffer latency, read-call timing, clock drift, measured bitrate, gate state, bytes written and
every extra reported by the engine.

## History and player

- Lists both folders with duration, size and format badge.
- Play with 0.5x to 3x speed (pitch preserved on API 23+). Raw `.pcm` files are streamed through
  `AudioTrack` using the layout encoded in the file name.
- Share through the app's own read-only `ContentProvider`, rename, delete.
- Audio parameters screen: RIFF / FLAC STREAMINFO headers parsed by hand, every `MediaFormat` key
  from `MediaExtractor`, `MediaMetadataRetriever` fields, derived bitrate and compression ratio,
  plus the sidecar `.meta` file that stores the exact capture settings of each recording.

## Foreground service

`RecordingService` runs the engine with a partial wake lock and a notification carrying
pause / resume / stop actions (`foregroundServiceType="microphone|mediaProjection"`).

## Building

Open the project in Android Studio or run `gradlew assembleDebug` with a JDK 17
(the Gradle 8.0 wrapper does not run on JDK 21). `compileSdk 34`, `minSdk 21`, `targetSdk 29`.
