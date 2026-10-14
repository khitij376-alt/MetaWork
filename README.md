# MetaWork — 24/7 Voice-Only Autonomous Mobile Agent

[![License: MIT](https://img.shields.io/badge/License-MIT-emerald.svg)](LICENSE)
[![Platform: Android 8.0+](https://img.shields.io/badge/Platform-Android%208.0%2B%20(API%2026%2B)-38BDF8.svg)](https://developer.android.com)
[![Voice Engine: Gemini Live API](https://img.shields.io/badge/Voice-Gemini%20Multimodal%20Live-F59E0B.svg)](https://ai.google.dev/gemini-api/docs/live-api)

**MetaWork** is an open-source, 24/7 hands-free autonomous voice agent for Android hosted at [github.com/khitij376-alt/MetaWork](https://github.com/khitij376-alt/MetaWork). It runs continuously on your phone with **no keyboard** and **no text prompt UI**—listening in the background and executing complex, multi-step tasks across your installed mobile apps purely through natural voice conversation.

Whether you want to book train tickets on IRCTC, search flights on MakeMyTrip, hail a cab on Uber, reserve bus seats on RedBus, grab movie tickets on BookMyShow, send WhatsApp or SMS messages, or place direct phone calls, MetaWork reads the screen, fills forms, taps buttons, and talks back to you in real time.

## Key Capabilities

* **24/7 Always-On Background Daemon**: Powered by an Android `ForegroundService` (`microphone|specialUse`), `PARTIAL_WAKE_LOCK`, `START_STICKY`, a 15-second self-healing watchdog loop, and `BootCompletedReceiver` so the voice agent stays alive 24/7 without closing after 30 seconds.
* **Dual-Engine Voice Brain (Cloud + On-Device Fallback)**: Connects to the [Gemini Multimodal Live API](https://ai.google.dev/gemini-api/docs/live-api) (`BidiGenerateContent` WebSocket) when `GEMINI_API_KEY` is configured, and automatically runs a continuous on-device `SpeechRecognizer` + `TextToSpeech` loop even if no API key is set.
* **Zero-Keyboard, Zero-Prompt Experience**: Designed for complete hands-free operation. You never have to type prompts or tap input boxes—MetaWork injects text directly into app fields and clicks buttons on your behalf.
* **Autonomous Screen Navigation**: Uses Android's `AccessibilityService` (`canRetrieveWindowContent` + `canPerformGestures`) to inspect every visible button, label, seat, price, and text field on the active screen, then executes taps, text entry, and directional scrolling step-by-step.
* **Built-In Payment & Safety Guardrail**: Always asks for your explicit spoken confirmation before tapping any final payment or checkout button.

## System Architecture & Execution Flow

| Layer | Source File | How It Works |
| :--- | :--- | :--- |
| **Always-On Voice Service** | `VoiceAgentForegroundService.kt` | Acquires a non-reference-counted partial CPU wake lock, runs a 15-second watchdog heartbeat, and maintains the 24/7 voice session in the background. |
| **Bidirectional Voice & Tool Engine** | `GeminiVoiceSessionManager.kt` | Streams 16kHz PCM audio from `AudioRecord`, plays 24kHz voice responses via `AudioTrack`, auto-reconnects on network drops, and dispatches real-time tool calls. |
| **Autonomous Screen Controller** | `AgentAccessibilityService.kt` | Dumps the live window UI tree to JSON (`dumpScreenTreeJson`), clicks elements by label or coordinates (`clickElementByLabel`, `tapCoordinates`), types text without a keyboard (`inputTextIntoField`), and scrolls (`scrollScreen`). |
| **Multi-App Workflow Executor** | `TaskActionExecutor.kt` | Resolves contact names from your phonebook, triggers app deep-links (Uber, IRCTC, MakeMyTrip, RedBus, BookMyShow, WhatsApp, SMS, Phone), and chains UI actions. |
| **Auto-Start & Voice Orb HUD** | `BootCompletedReceiver.kt` & `MainActivity.kt` | Automatically starts the 24/7 service on device boot and keeps the HUD screen awake (`FLAG_KEEP_SCREEN_ON`) while showing live voice transcripts. |

## How to Install & Use MetaWork (Step-by-Step Guide)

### Option A: Download & Install Pre-Built APK Directly on Your Phone (No PC Needed)

1. Open this repository on GitHub and tap the **Actions** tab at the top ([github.com/khitij376-alt/MetaWork/actions](https://github.com/khitij376-alt/MetaWork/actions)).
2. Tap the latest green **MetaWork Android CI** workflow run.
3. Scroll down to the **Artifacts** section and tap **MetaWork-24x7-debug-apk** to download the zip file, extract `app-debug.apk`, and install it on your Android phone (Android 8.0 / API 26 or newer).

### Option B: Build from Source (Android Studio or Command Line)

1. **Clone the Repository**:
   ```bash
   git clone https://github.com/khitij376-alt/MetaWork.git
   cd MetaWork
   ```
2. **Add Your Gemini API Key**:
   * Get a free Gemini API key from [Google AI Studio](https://ai.google.dev/gemini-api/docs/get-started).
   * Copy `local.properties.example` to `local.properties` and paste `GEMINI_API_KEY=AIzaSy...`.
3. **Compile & Install on Your Phone**:
   ```bash
   ./gradlew installDebug
   ```

## First-Time Phone Setup (One-Time Only)

1. **Approve Runtime Permissions**: Tap **Allow** when prompted for **Microphone**, **Contacts**, **SMS**, **Phone Calls**, and **Notifications**.
2. **Allow Unrestricted Battery Usage**: Tap **Allow** on the battery optimization prompt so Android never suspends the 24/7 background service.
3. **Enable the Accessibility Screen Controller**:
   * Tap **Enable Screen Controller** inside the app (or open **Settings → Accessibility → Installed apps → MetaWork Autonomous Screen Controller**) and toggle it **ON**.
   * *(Android 13/14 Tip: If Android shows "Restricted setting", open **Settings → Apps → MetaWork 24/7 Agent → ⋮ (top right) → Allow restricted settings**, then enable it in Accessibility.)*

## What You Can Say (Voice Command Examples)

* *"Book a train ticket from Patna to New Delhi tomorrow on IRCTC."*
* *"Search flights from Patna to Mumbai this Friday on MakeMyTrip."*
* *"Book an Uber to Patna Junction railway station."*
* *"Send a WhatsApp message to Rahul saying I have booked the train tickets."*
* *"Call Rahul on the phone."*
* *"Scroll down"*, *"Click on Search"*, or *"Go back."*

## License

Distributed under the [MIT License](LICENSE).
