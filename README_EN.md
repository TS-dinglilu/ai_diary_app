# AI Diary App

An Android-based intelligent voice diary app that automatically records audio, transcribes speech to text, and generates diary entries with AI-powered sentiment analysis. Supports both local offline models and cloud APIs.

> **[中文文档](README.md)** | **Project Home**: <https://github.com/TS-dinglilu/ai_diary_app>

## Screenshots

| AI Chat | AI Analysis | Settings | Profile |
|:---:|:---:|:---:|:---:|
| ![Chat](pictures/微信图片_20260707185927_188_24.jpg) | ![Analysis](pictures/微信图片_20260707185927_189_24.jpg) | ![Settings](pictures/微信图片_20260707185928_190_24.jpg) | ![Profile](pictures/微信图片_20260707185929_191_24.jpg) |

- **AI Chat**: Multi-turn dialogue, an intelligent assistant based on recording transcriptions and AI analysis context
- **AI Analysis**: Grouped by date, showing transcription status, sentiment analysis, and diary content with manual note support
- **Settings**: Integrated interface for recording control, transcription mode switching, scheduled recording, model management, and backup configuration
- **Profile**: Recording management, settings entry, permission management, version updates, and project homepage

## Features

### Recording
- Auto-start recording when the app opens
- AudioRecord + WAV format, 44-byte header written first for instant playability
- Auto-segmentation every 3 minutes to minimize data loss
- fsync every 3 seconds to force disk writes and update WAV header
- Microphone conflict detection (WeChat voice, calls, etc.) immediately stops recording
- Saves current recording when process is killed; recovers orphan files and starts new recording on restart

### Speech-to-Text (ASR)
- Four modes: Off / Local Offline (sherpa-onnx) / Whisper / Cloud AI
- Local offline mode based on sherpa-onnx (Next-gen Kaldi), no network required
- Speaker diarization support
- Re-transcribe / Continue transcription (by date or individual entry)

### AI Analysis
- Cloud analysis: Supports OpenAI-compatible APIs
- Local offline analysis: MediaPipe LLM Inference + Gemma-2 2B int8 quantized model (~1.8GB)
- Generates sentiment scores, summaries, and diary content
- Aggregated display by date

### AI Chat
- Multi-turn dialogue with context awareness
- Supports both cloud and local offline models

### Scheduled Recording
- Custom auto start/stop times (e.g., start at 7:30, stop at 23:00)
- Precise triggering via AlarmManager, auto-restores after device reboot

### Data Backup & Restore
- **Jianguoyun (Nutstore) WebDAV Cloud Backup**: Backs up transcriptions, AI analysis results, notes, chat records, and settings (excludes audio and model files)
- **Local ZIP Backup**: Select a folder to export data as a ZIP archive
- **Restore from Cloud** / **Restore from Local**: One-click data recovery
- Auto-backup to Nutstore on app launch (can be disabled)
- Auto cloud backup when charging (can be disabled)

### Dark Mode
- Material Design 3 dark theme
- Three modes: Light / Dark / Follow System
- Optimized dark mode color scheme with high contrast and clear hierarchy

### GitHub Auto-Update
- Silently checks GitHub Releases for new versions on app launch
- Shows "Update available" hint next to the "Check for updates" button
- Manual check shows a dialog with update details
- Download progress dialog with cancel support

## Download

| Source | URL | Note |
|--------|-----|------|
| GitHub Release | [Releases](https://github.com/TS-dinglilu/ai_diary_app/releases) | International |
| China (Lanzou) | https://dingliu.lanzoue.com/iHNOX3uylppe | Password: `dms0` |

## Important: Battery & Background Settings

To ensure the app runs reliably in the background for continuous recording, please configure the following settings on your device:

1. **Enable Auto-start**: Allow the app to auto-start on boot so recording can resume after restart
2. **Battery Saver: Unrestricted**: Set battery optimization to "Unrestricted" (or "No restrictions") to prevent the system from killing the background recording service
3. **Lock the App**: Lock the app in the recent tasks view to prevent it from being cleared

> These settings are especially important on Chinese Android ROMs (MIUI, EMUI, ColorOS, OriginOS, etc.) which have aggressive background task management.

## Tested Device

This app has been tested on a **Xiaomi 15** device. If you encounter any bugs or issues on other devices, please report them in [Issues](https://github.com/TS-dinglilu/ai_diary_app/issues).

## Tech Stack

| Category | Technology |
|----------|-----------|
| Language | Kotlin |
| Architecture | MVVM + Room + ViewBinding |
| Recording | AudioRecord + WAV |
| ASR | sherpa-onnx (local) / Whisper (local) / Cloud API |
| AI Analysis | MediaPipe LLM Inference 0.10.14 (local) / Cloud API |
| Local Model | Gemma-2 2B int8 quantized |
| Database | Room (SQLite) |
| Background Tasks | WorkManager |
| UI | Material Design 3 |

## Project Structure

```
ai_diary_app/
├── app/
│   └── src/main/
│       ├── assets/
│       │   ├── sherpa/          # ASR model (downloaded at runtime)
│       │   └── llm/             # Local AI analysis model (downloaded at runtime)
│       └── java/com/example/ailogapp/
│           ├── ai/              # AI analysis & LLM inference
│           ├── data/            # Room database, Entity, DAO
│           ├── fragment/        # Fragments (Chat, Analysis, Settings)
│           ├── service/         # Recording service
│           ├── ui/              # RecyclerView Adapter, data items
│           ├── util/            # Utilities (PrefsManager, FileUtils, etc.)
│           └── worker/          # WorkManager background tasks
├── pictures/                    # App screenshots
├── gradle/
├── settings.gradle.kts
└── build.gradle.kts
```

## Getting Started

### Requirements
- Android Studio
- JDK 17+
- Android SDK 34 (targetSdk)
- minSdk 26

### Build
```bash
./gradlew assembleDebug
```

### Model Files

Models are NOT bundled in the APK. They are downloaded on first use to keep the installation package small:

| Model | Purpose | Download Source | Size |
|-------|---------|----------------|------|
| sense-voice.onnx | Speech-to-Text | [ModelScope](https://www.modelscope.cn/models/dingliu/ai_diary_app) | ~937MB |
| gemma-2b-it.bin | Local offline AI analysis | [ModelScope](https://www.modelscope.cn/models/dingliu/ai_diary_app) | ~1.8GB |

> Download URL: https://www.modelscope.cn/models/dingliu/ai_diary_app
>
> After entering the model download link in the Settings screen, the app will automatically download and install the models to the app's private directory (filesDir). The two models combined require approximately 2.7GB of storage space.

## Configuration

| Setting | Description |
|---------|-------------|
| Transcription Mode | Off / Local Offline / Whisper / Cloud AI |
| AI Analysis Mode | Cloud / Local Offline |
| Auto Transcribe | Automatically triggered when charging |
| Auto Analyze | Automatically triggered when charging |
| Auto Delete | Auto-delete audio files older than N days (keeps text) |
| Default Collapse | Collapse recording entries by date on the AI Analysis page |
| Dark Mode | Light / Dark / Follow System |
| Scheduled Recording | Custom auto start/stop times |
| Nutstore Backup | WebDAV URL / email / app key configuration |
| Local Backup | Select a folder to export ZIP |
| Auto-backup on Launch | Auto-backup to Nutstore when opening the app |
| Model Link | ASR / LLM model download link (direct URL) |

## Third-Party Libraries

- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) - Speech recognition
- [MediaPipe LLM Inference](https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference) - On-device LLM inference
- Room - Database
- WorkManager - Background tasks
- Material Components for Android - UI components

## Bug Reports

Found a bug? Please open an issue on [GitHub Issues](https://github.com/TS-dinglilu/ai_diary_app/issues) with:
- Device model and Android version
- Steps to reproduce
- Expected vs. actual behavior
- Log output (if available)

## License

MIT
