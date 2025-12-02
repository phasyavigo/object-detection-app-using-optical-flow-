# Enhanced Obstacle Detection App using Optical Flow

![Platform](https://img.shields.io/badge/platform-Android-green.svg)
![Language](https://img.shields.io/badge/language-Kotlin-blue.svg)
![OpenCV](https://img.shields.io/badge/OpenCV-4.x-red.svg)
![MinSDK](https://img.shields.io/badge/minSDK-24-orange.svg)

Real-time obstacle detection application for Android using advanced optical flow analysis with temporal integration, looming detection, and adaptive baseline tracking.

## 🎯 Features

### Core Detection Methods
- **📊 Magnitude Detection** - Fast motion detection for quick obstacles
- **⏱️ Temporal Integration** - Slow approach detection for gradual movements
- **🎯 Looming Detection** - Radial expansion analysis for approaching objects

### Advanced Capabilities
- **🧭 3-Zone Direction Detection** - Left/Center/Right spatial awareness
- **📈 Adaptive Baseline Tracking** - Dynamic threshold adjustment
- **🎨 Enhanced Real-time Overlay** - Visual feedback with metric bars
- **🔊 Audio Feedback** - Text-to-Speech alerts in Bahasa Indonesia
- **⚡ Optimized Performance** - 25-30 FPS on mid-range devices

## 📱 Screenshots

### Main Interface
The app displays real-time camera feed with comprehensive overlay showing:
- Status indicator (green/red circle)
- Detection mode (MAG/INT/LOOM)
- Three metric bars (Magnitude, Integrator, Looming)
- Direction display
- Zone activity bars (left/center/right)

## 🏗️ Architecture

### Technology Stack
- **Language:** Kotlin
- **Computer Vision:** OpenCV 4.x
- **Audio:** Android TextToSpeech API
- **Min SDK:** Android 7.0 (API 24)
- **Target SDK:** Android 14 (API 34)

### Detection Algorithm
```
Camera Input → Optical Flow (Farneback) → Multi-Metric Analysis
                                              ↓
                        ┌─────────────────────┴─────────────────────┐
                        ↓                     ↓                     ↓
                  Magnitude              Integrator            Looming
                  (Fast Motion)       (Slow Approach)      (Radial Expansion)
                        ↓                     ↓                     ↓
                        └─────────────────────┬─────────────────────┘
                                              ↓
                                    Combined Detection (OR Logic)
                                              ↓
                                   Direction Classification
                                              ↓
                                   Alert (Visual + Audio)
```

## 🚀 Getting Started

### Prerequisites
- Android Studio Arctic Fox or later
- Android device with camera (API 24+)
- OpenCV Android SDK 4.x

### Installation

1. **Clone the repository**
```bash
git clone https://github.com/phasyavigo/object-detection-app-using-optical-flow-.git
cd object-detection-app-using-optical-flow-
```

2. **Open in Android Studio**
- File → Open → Select project directory
- Wait for Gradle sync to complete

3. **Build the project**
```bash
./gradlew assembleDebug
```

4. **Install to device**
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

Or use Android Studio: Run → Run 'app' (Shift+F10)

## 🔧 Configuration

### Detection Parameters

All tuned parameters are defined in `MainActivity.kt`:

```kotlin
// Resolution
RESIZE_WIDTH = 480
RESIZE_HEIGHT = 360

// Detection Thresholds
THRESHOLD_HIGH = 2.0          // Magnitude detection threshold
THRESHOLD_LOW = 1.4           // Detection release threshold
INTEGRATION_THRESHOLD = 15.0  // Temporal integrator threshold
LOOMING_THRESHOLD = 0.3       // Radial expansion threshold

// Smoothing & Integration
ALPHA_SMOOTH = 0.5            // Magnitude smoothing factor
ALPHA_INTEGRATE = 0.88        // Integrator decay (higher = slower decay)

// Other
MIN_MAGNITUDE = 0.5           // Minimum magnitude to consider
DIRECTION_MARGIN = 0.4        // Direction classification margin
COOLDOWN_MS = 2000L           // Alert cooldown (2 seconds)
```

### Optical Flow Parameters (Farneback)
```kotlin
pyr_scale = 0.5    // Pyramid scale
levels = 3         // Number of pyramid levels
winsize = 15       // Averaging window size
iterations = 3     // Number of iterations
poly_n = 5         // Polynomial expansion neighborhood
poly_sigma = 1.2   // Gaussian sigma for polynomial expansion
```

## 📖 Usage

### Basic Operation

1. **Launch the app** - Grant camera permission when prompted
2. **Point camera** at potential obstacles
3. **Observe overlay** for real-time metrics
4. **Listen for alerts** when obstacles are detected

### Understanding the Overlay

#### Status Indicator (Top-Right)
- 🟢 **Green Circle** = Clear, no obstacles
- 🔴 **Red Circle** = Obstacle detected

#### Detection Mode (Top-Right Text)
- **MAG** (Cyan) = Fast motion detected
- **INT** (Orange) = Slow approach detected
- **LOOM** (Yellow) = Looming (approaching) detected
- **NONE** (Gray) = No detection

#### Metric Bars (Top-Left)
- **Mag Bar** (Cyan) - Current motion magnitude (0-5)
- **Int Bar** (Orange) - Temporal integrator value (0-30)
- **Loom Bar** (Yellow) - Looming score (0-0.5)

#### Zone Bars (Bottom)
- **Red** (Left 30%) - Left zone activity
- **Yellow** (Center 40%) - Center zone activity
- **Blue** (Right 30%) - Right zone activity

### Testing Scenarios

#### Test 1: Fast Motion
**Action:** Wave hand quickly in front of camera  
**Expected:** MAG detection, immediate alert

#### Test 2: Slow Approach
**Action:** Slowly move hand toward camera (3+ seconds)  
**Expected:** INT detection via temporal accumulation

#### Test 3: Looming
**Action:** Move large object (book) straight toward camera  
**Expected:** LOOM detection via radial expansion

#### Test 4: Direction
**Action:** Approach from left/right/center  
**Expected:** Correct direction in alert ("kiri"/"kanan"/"depan")

## 🐛 Troubleshooting

### Low FPS (<20)

**Solutions:**
1. Lower processing resolution:
```kotlin
RESIZE_WIDTH = 320  // from 480
RESIZE_HEIGHT = 240  // from 360
```

2. Close background apps
3. Test on faster device

### No Audio Feedback

**Check:**
1. Logcat for TTS initialization: `✅ TTS initialized`
2. Media volume is up (TTS uses STREAM_MUSIC)
3. Install "Google Text-to-Speech" from Play Store
4. Bahasa Indonesia language data installed

### Too Sensitive / False Positives

**Increase thresholds:**
```kotlin
THRESHOLD_HIGH = 2.5           // from 2.0
INTEGRATION_THRESHOLD = 18.0   // from 15.0
LOOMING_THRESHOLD = 0.4        // from 0.3
```

### Not Sensitive Enough

**Decrease thresholds:**
```kotlin
THRESHOLD_HIGH = 1.5           // from 2.0
INTEGRATION_THRESHOLD = 12.0   // from 15.0
LOOMING_THRESHOLD = 0.2        // from 0.3
```

## 📊 Performance Metrics

### Target Performance
- **FPS:** 25-30 on mid-range devices
- **Latency:** <50ms detection response
- **Accuracy:** 95%+ detection rate

### Measured Performance
- **Memory:** Minimal allocation (Mat reuse optimization)
- **CPU:** Efficient baseline caching
- **Battery:** Optimized UI updates

## 🔬 Technical Details

### Key Optimizations

1. **Mat Reuse** - Eliminated per-frame allocation (78MB/s → 0MB/s)
2. **Cached Baseline** - Reduced sorting overhead (30×/s → 5×/s)
3. **Optimized UI Updates** - Only update on state change
4. **Validated Looming Calc** - Size checking prevents crashes

### Detection Logic

```kotlin
// Multi-metric OR detection
val magDetected = smoothMag > thresholdHigh && smoothMag > MIN_MAGNITUDE
val intDetected = temporalIntegrator > INTEGRATION_THRESHOLD
val loomDetected = loomingScore > LOOMING_THRESHOLD

if (magDetected || intDetected || loomDetected) {
    detectedState = true
    detectionMode = when {
        magDetected -> "MAG"
        intDetected -> "INT"
        else -> "LOOM"
    }
}
```

### Adaptive Baseline

```kotlin
// Dynamic threshold adjustment based on environment
val baseline = median(baselineSamples)  // Only when not detecting
val adaptiveThreshold = clamp(baseline * factor, minThreshold, maxThreshold)
```

## 📚 Documentation

### Project Structure
```
app/
├── src/main/
│   ├── java/com/example/optflow/
│   │   └── MainActivity.kt          # Main application logic
│   ├── res/
│   │   ├── layout/
│   │   │   └── activity_main.xml    # UI layout
│   │   └── values/
│   │       └── strings.xml          # String resources
│   └── jniLibs/                     # OpenCV native libraries
└── build.gradle.kts                 # Build configuration
```

### Key Files
- **MainActivity.kt** - Core detection algorithm and UI handling
- **activity_main.xml** - Camera view and overlay elements
- **strings.xml** - Alert messages and labels

## 🤝 Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

### Development Setup
1. Fork the repository
2. Create your feature branch (`git checkout -b feature/AmazingFeature`)
3. Commit your changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to the branch (`git push origin feature/AmazingFeature`)
5. Open a Pull Request

## 📄 License

This project is open source and available for educational purposes.

## 👥 Authors

- **Phasya Vigo**
- **Muhammmad Rafie Habibie**

## 🙏 Acknowledgments

- OpenCV community for the excellent computer vision library
- Python reference implementation for algorithm design
- Android TextToSpeech for audio feedback capabilities

## 📧 Contact

For questions or support, please open an issue on GitHub.

---

**Built with ❤️ for Computer Vision applications**
