# MetaWork — 24/7 Voice-Only Autonomous Mobile Agent

[![License: MIT](https://img.shields.io/badge/License-MIT-emerald.svg)](LICENSE)
[![Platform: Android 8.0+](https://img.shields.io/badge/Platform-Android%208.0%2B%20(API%2026%2B)-38BDF8.svg)](https://developer.android.com)
[![Voice Engine: Gemini Live API](https://img.shields.io/badge/Voice-Gemini%20Multimodal%20Live-F59E0B.svg)](https://ai.google.dev/gemini-api/docs/live-api)

**MetaWork** is an open-source, 24/7 hands-free autonomous voice agent for Android hosted at [github.com/khitij376-alt/MetaWork](https://github.com/khitij376-alt/MetaWork). It runs continuously on your phone with **no keyboard** and **no text prompt UI**—listening in the background and executing complex, multi-step tasks across your installed mobile apps purely through natural voice conversation.

Whether you want to book train tickets on IRCTC, search flights on MakeMyTrip, hail a cab on Uber, reserve bus seats on RedBus, grab movie tickets on BookMyShow, send WhatsApp or SMS messages, or place direct phone calls, MetaWork reads the screen, fills forms, taps buttons, and talks back to you in real time.

## Key Capabilities

* **24/7 Always-On Background Daemon**: Powered by an Android `ForegroundService` (`microphone|specialUse`), `PARTIAL_WAKE_LOCK`, `START_STICKY`, and `BootCompletedReceiver` so the voice agent stays alive even when your screen is locked or after your phone restarts.
* **Zero-Keyboard, Zero-Prompt Experience**: Designed for complete hands-free operation. You never have to type prompts or tap input boxes—MetaWork injects text directly into app fields and clicks buttons on your behalf.
* **Real-Time Full-Duplex Voice Brain**: Connects directly to the [Gemini Multimodal Live API](https://ai.google.dev/gemini-api/docs/live-api) (`BidiGenerateContent` WebSocket), streaming raw 16kHz PCM microphone audio and playing back 24kHz natural spoken responses with sub-second latency.
* **Autonomous Screen Navigation**: Uses Android's `AccessibilityService` (`canRetrieveWindowContent` + `canPerformGestures`) to inspect every visible button, label, seat, price, and text field on the active screen, then executes taps, text entry, and directional scrolling step-by-step.
* **Built-In Payment & Safety Guardrail**: Always asks for your explicit spoken confirmation before tapping any final payment or checkout button.

## System Architecture & Execution Flow

| Layer | Source File | How It Works |
| :--- | :--- | :--- |
| **Always-On Voice Service** | `VoiceAgentForegroundService.kt` | Acquires a partial CPU wake lock, posts a persistent status notification with `ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE`, and maintains the 24/7 voice session in the background. |
| **Bidirectional Voice & Tool Engine** | `GeminiVoiceSessionManager.kt` | Streams 16kHz PCM audio from `AudioRecord`, plays 24kHz voice responses via `AudioTrack`, and dispatches real-time tool calls returned by Gemini. |
| **Autonomous Screen Controller** | `AgentAccessibilityService.kt` | Dumps the live window UI tree to JSON (`dumpScreenTreeJson`), clicks elements by label or coordinates (`clickElementByLabel`, `tapCoordinates`), types text without a keyboard (`inputTextIntoField`), and scrolls (`scrollScreen`). |
| **Multi-App Workflow Executor** | `TaskActionExecutor.kt` | Resolves contact names from your phonebook, triggers app deep-links (Uber, IRCTC, MakeMyTrip, RedBus, BookMyShow, WhatsApp, SMS, Phone), and chains UI actions. |
| **Auto-Start & Voice Orb HUD** | `BootCompletedReceiver.kt` & `MainActivity.kt` | Automatically starts the 24/7 service on device boot and provides a dark-mode voice status screen that guides one-time permission setup. |

### How a Voice Task Executes End-to-End

1. **You Speak**: You say a command out loud (e.g., *"Book an Uber to Patna Junction railway station"* or *"Send a WhatsApp message to Rahul saying I have booked the tickets"*).
2. **Real-Time Audio Streaming**: `GeminiVoiceSessionManager` streams your voice over WebSocket to Gemini Live.
3. **Tool Invocation**: Gemini invokes `open_app_or_booking` or `send_whatsapp_message`, followed by `read_screen` to inspect the opened app's UI elements.
4. **Autonomous UI Action**: `AgentAccessibilityService` locates the matching buttons or input fields on screen, injects text or taps the target element, and reports the updated screen state back to the agent.
5. **Spoken Feedback**: MetaWork speaks the result or asks for your verbal confirmation before completing final booking steps.

## How to Install & Use MetaWork (Step-by-Step Guide)

You can install and use MetaWork in **two ways**: either by downloading the pre-built APK directly from GitHub Actions on your phone (no computer required), or by building from source in Android Studio.

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
   * Copy `local.properties.example` to `local.properties`:
     ```bash
     cp local.properties.example local.properties
     ```
   * Open `local.properties` and paste your key:
     ```properties
     GEMINI_API_KEY=AIzaSyYourActualGeminiApiKeyHere
     ```
3. **Compile & Install on Your Phone**:
   * Enable **USB Debugging** in your Android phone's Developer Options and connect it via USB.
   * Run:
     ```bash
     ./gradlew installDebug
     ```

## First-Time Phone Setup (One-Time Only)

When you open **MetaWork 24/7 Agent** for the first time on your phone, complete these three one-time permissions so the agent can operate 24/7 without interruption:

1. **Approve Runtime Permissions**: Tap **Allow** when prompted for **Microphone** (voice streaming), **Contacts** (looking up contact names by voice), **SMS**, **Phone Calls**, and **Notifications**.
2. **Allow Unrestricted Battery Usage**: A system dialog will ask to ignore battery optimizations—tap **Allow** so Android does not put the 24/7 voice listener to sleep when your screen turns off.
3. **Enable the Accessibility Screen Controller**:
   * Your phone will open **Settings → Accessibility**.
   * Find **Installed apps** (or **Downloaded services**) → tap **MetaWork Autonomous Screen Controller** → toggle it **ON**.
   * Once enabled, MetaWork can read screen elements and click buttons across any app hands-free.

## What You Can Say (Voice Command Examples)

Once active, you never need to touch your keyboard. Speak naturally in English or Hinglish:

### 1. Booking Train, Flight, Bus & Movie Tickets
* *"Book a train ticket from Patna to New Delhi tomorrow on IRCTC and read me the available trains."*
* *"Search flights from Patna to Mumbai this Friday on MakeMyTrip."*
* *"Open RedBus and find AC sleeper buses to Ranchi tonight."*
* *"Open BookMyShow and check evening movie showtimes."*

### 2. Hailing Cabs & Rides
* *"Book an Uber to Patna Junction railway station."*
* *"Request an Uber ride to the airport from my current location."*

### 3. Hands-Free WhatsApp, SMS & Phone Calls
* *"Send a WhatsApp message to Rahul saying I have booked the train tickets."*
* *"Send an SMS to Mom saying I will reach home by 8 PM."*
* *"Call Rahul on the phone."*

### 4. Controlling Any Screen Hands-Free
* *"Read what is currently on my screen."*
* *"Click on the Search Trains button."*
* *"Enter Patna Junction into the From station field."*
* *"Scroll down to see more options."*
* *"Go back to the previous screen"* or *"Go to the home screen."*

## Project Structure

```text
MetaWork/
├── .github/workflows/android-ci.yml      # Automated JDK 17 Gradle CI & APK artifact builder
├── .gitignore                            # Excludes local.properties & build outputs
├── CODE_OF_CONDUCT.md                    # Community Code of Conduct
├── CONTRIBUTING.md                       # Contribution rules
├── LICENSE                               # Permissive MIT License
├── README.md                             # Project documentation & user guide
├── build.gradle.kts                      # Root Gradle plugin configuration
├── gradle.properties                     # JVM & AndroidX settings
├── local.properties.example              # Template for GEMINI_API_KEY
├── settings.gradle.kts                   # Root project settings
└── app/
    ├── build.gradle.kts                  # App module dependencies & BuildConfig injection
    └── src/main/
        ├── AndroidManifest.xml           # 24/7 ForegroundService, Accessibility & Boot permissions
        ├── java/.../
        │   ├── AgentAccessibilityService.kt     # UI tree reader, clicker, text injector & scroller
        │   ├── BootCompletedReceiver.kt         # Auto-starts the 24/7 service when phone reboots
        │   ├── GeminiVoiceSessionManager.kt     # Real-time 16kHz/24kHz PCM WebSocket & tool router
        │   ├── MainActivity.kt                  # Zero-keyboard voice HUD & permission onboarding
        │   ├── TaskActionExecutor.kt            # Deep-links & multi-app workflow execution
        │   └── VoiceAgentForegroundService.kt   # 24/7 sticky background service with WakeLock
        └── res/
            ├── values/strings.xml
            └── xml/accessibility_service_config.xml
```

## License

Distributed under the [MIT License](LICENSE).
