# MobileNetV1 Face Detection App

A cross-platform face detection application built with Kotlin and Java, leveraging the MobileNetV1 architecture. This project demonstrates real-time face detection on Android devices.

## Features

- Real-time face detection using MobileNetV1
- Camera integration for live detection
- Optimized for performance on mobile devices
- User-friendly interface
- Modular and extensible codebase

## Getting Started

### Prerequisites

- Android Studio (2025.1.2 or later)
- JDK 17 or later
- Android device or emulator (API 21+ recommended)
- Git

### Installation

1. **Clone the repository:**
   ```sh
   git clone https://github.com/aditya13504/mobilenetv1-face-detection-APP.git
   cd mobilenetv1-face-detection-APP

2. **Open the project in Android Studio**.

3. **Sync Gradle****:** Android Studio will prompt you to sync the Gradle files. Click "Sync Now".

4. **Build the project****:** Use the "Build" menu or the toolbar button.
  
5. **Run the app****:** Connect your Android device or start an emulator, then click "Run".
   ```
---

### Usage

1. Launch the app on your device.
2. Grant camera permissions if prompted.
3. Point the camera at a face; detected faces will be highlighted in real-time.

### Project Structure

mobilenetv1-face-detection-APP/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/
│   │   │   │   └── ... (Kotlin/Java source files)
│   │   │   ├── res/
│   │   │   │   └── ... (layouts, drawables, etc.)
│   │   │   └── AndroidManifest.xml
│   └── build.gradle
├── build.gradle
└── README.md

---
### Dependencies
1. TensorFlow Lite
2. CameraX
3. AndroidX Libraries
---

### Model
The app uses a face detection trained MobileNetV1 model for face detection. The model file (FaceDetector.tflite) should be placed in the assets directory.

### Contributing
Contributions are welcome! Please open issues or submit pull requests for improvements and bug fixes.

### License
This project is licensed under the MIT License. See the LICENSE file for details.
