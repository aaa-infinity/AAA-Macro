# ⚡ AAA Macro — Production-Grade No-Root Android Game Automation Engine

AAA Macro is a standalone, high-performance, no-root Android automation application written in 100% Native Kotlin. It combines Android Accessibility Services, MediaProjection Virtual Displays, OpenCV computer vision, and Google ML Kit OCR to execute safe, intelligent, humanized gameplay routines.

---

## 🏛 System Architecture

```
                               ┌─────────────────────────────┐
                               │     MainActivity Setup      │
                               │  (Permissions & Dashboard)  │
                               └──────────────┬──────────────┘
                                              │ Starts
                                              ▼
                               ┌─────────────────────────────┐
                               │  CapturePermissionActivity  │
                               │ (MediaProjection Consent)   │
                               └──────────────┬──────────────┘
                                              │ Routes Token
                                              ▼
┌────────────────────────────────────────────────────────────────────────────┐
│                       FloatingMenuService (Foreground)                     │
│                                                                            │
│  ┌─────────────────────────┐          ┌─────────────────────────────────┐  │
│  │   FloatingOverlayView   │◀────────▶│       MacroStateMachine         │  │
│  │ (Draggable UI Overlay)  │          │   (Finite State Controller)     │  │
│  └─────────────────────────┘          └───────────────┬─────────────────┘  │
│                                                       │                    │
│                        ┌──────────────────────────────┴──────────────┐     │
│                        ▼                                             ▼     │
│         ┌──────────────────────────────┐              ┌──────────────────┐ │
│         │         VisionEngine         │              │  HumanGesture    │ │
│         │  - MediaProjection Capture   │              │   Dispatcher     │ │
│         │  - OpenCV Template Matching  │              │  (Gaussian Tap   │ │
│         │  - ML Kit Text Recognition   │              │   & Bézier Swipe)│ │
│         │  - Resolution Scaler         │              └────────┬─────────┘ │
│         └──────────────────────────────┘                       │           │
└────────────────────────────────────────────────────────────────┼───────────┘
                                                                 │
                                                                 ▼
                                                ┌─────────────────────────────┐
                                                │  MacroAccessibilityService  │
                                                │ (dispatchGesture No-Root)   │
                                                └─────────────────────────────┘
```

---

## 🚀 Key Modules & Components

1. **Anti-Detection Gesture Engine (`HumanGestureDispatcher.kt`)**:
   - Gaussian distribution $(\mu = 0, \sigma = 2.5)$ coordinate perturbation.
   - Micro-jitter within designated boundary radii.
   - Stochastic tap hold durations ($40\text{ms} - 85\text{ms}$).
   - Non-linear Cubic Bézier curve trajectory interpolation for swipes.
   - Randomized non-blocking coroutine delays.

2. **Computer Vision & OCR (`VisionEngine.kt`)**:
   - Real-time `MediaProjection` frame grabber with pixel stride / row stride alignment.
   - `Imgproc.matchTemplate` using `TM_CCOEFF_NORMED` with confidence thresholding.
   - Explicit lifecycle management of native `Mat` and `Bitmap` memory buffers to prevent OOM.
   - Google ML Kit On-Device Text Recognition for Gold and Elixir numeric quantity parsing.

3. **Resolution Normalization (`ResolutionScaler.kt`)**:
   - Reference templates authored at 1920x1080 landscape.
   - Automatically adapts templates, bounding boxes, and gesture coordinates to target screen aspect ratio and DPI.

4. **Finite State Machine Controller (`MacroStateMachine.kt`)**:
   - `IDLE` $\rightarrow$ `STATE_HOME` $\rightarrow$ `STATE_SEARCHING` $\rightarrow$ `STATE_EVALUATE` $\rightarrow$ `STATE_DEPLOY` $\rightarrow$ `STATE_RETURN_HOME` $\rightarrow$ `STATE_RECOVERY`.
   - Failsafe timeout recovery for dialog popups and network disconnects.

5. **Floating Overlay Controller (`FloatingOverlayView.kt` & `FloatingMenuService.kt`)**:
   - Draggable overlay pill with live status badge, real-time loot readout, and instant start/pause toggle.

---

## 🛠 Build & Installation

### Local Build:
```bash
./gradlew assembleDebug
# APK generated at: app/build/outputs/apk/debug/app-debug.apk
```

### GitHub Actions CI:
Pushes to `main` branch trigger automated building of Debug and Release APKs with GitHub Release publishing.
