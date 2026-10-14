# MetaWork — 24/7 Voice-Only Autonomous Mobile Agent

[![License: MIT](https://img.shields.io/badge/License-MIT-emerald.svg)](LICENSE)
[![Platform: Android 8.0+](https://img.shields.io/badge/Platform-Android%208.0%2B-38BDF8.svg)](https://developer.android.com)
[![Voice Engine: Gemini Live API](https://img.shields.io/badge/Voice-Gemini%20Multimodal%20Live-F59E0B.svg)](https://ai.google.dev/gemini-api/docs/live-api)

**MetaWork** (`com.metawork.agent`) is a public open-source, 24/7 hands-free autonomous voice agent for Android hosted at [github.com/khitij376-alt/MetaWork](https://github.com/khitij376-alt/MetaWork). It operates with **no keyboard** and **no text prompt UI**—listening continuously in the background for your wake word and executing multi-step tasks across your phone such as booking train, flight, bus, movie, or cab tickets, sending WhatsApp and SMS messages, and placing phone calls.

## Core Architecture

| Layer | File / Module | Role |
| :--- | :--- | :--- |
| **24/7 Background Daemon** | `VoiceAgentForegroundService.kt` | Android `ForegroundService` (`microphone|specialUse`) + `PARTIAL_WAKE_LOCK` + `START_STICKY` + `BootCompletedReceiver.kt` |
| **On-Device Wake Word** | `VoiceAgentForegroundService.kt` | Picovoice Porcupine Android SDK (`"Jarvis"`) |
| **Bidirectional Voice Brain** | `GeminiVoiceSessionManager.kt` | Gemini Multimodal Live API (`BidiGenerateContent` WebSocket, 16kHz PCM input / 24kHz PCM audio output) |
| **Autonomous Screen Controller** | `AgentAccessibilityService.kt` | Android `AccessibilityService` (`dumpScreenTreeJson`, `clickElementByLabel`, `inputTextIntoField`, `scrollScreen`) |
| **Multi-App Booking Executor** | `TaskActionExecutor.kt` | Deep-links & UI automation for IRCTC, MakeMyTrip, Uber, RedBus, BookMyShow, WhatsApp, SMS, and Calls |

## Quick Start

1. Copy `local.properties.example` to `local.properties` and add your `GEMINI_API_KEY` and `PORCUPINE_ACCESS_KEY`.
2. Build and install on your Android phone:
   ```bash
   ./gradlew installDebug
   ```
3. Launch **MetaWork 24/7 Agent** once to approve Microphone, SMS, Call, Contacts, Unrestricted Battery, and Accessibility Service permissions.

## License

Released under the [MIT License](LICENSE).