# MetaWork — Android Voice Assistant with Screen Automation

[![License: MIT](https://img.shields.io/badge/License-MIT-emerald.svg)](LICENSE)
[![Platform: Android 8.0+](https://img.shields.io/badge/Platform-Android%208.0%2B%20(API%2026%2B)-38BDF8.svg)](https://developer.android.com)

**MetaWork** is an Android voice assistant that combines a wake-word foreground service, deterministic voice commands, Android Accessibility screen actions, and an optional Gemini task planner. It can perform supported actions across installed apps; it is **not** a guarantee that every task in every app can be completed autonomously.

## What it can do

- Listen for the configurable-by-toggle **“Muse”** wake word using Android speech recognition. Wake mode is on by default and is remembered across service restarts. On-device recognition is preferred where the phone provides it; otherwise Android may use its configured speech-recognition provider.
- Launch apps and websites; read visible screen text; tap accessible controls; type into visible fields; scroll and perform supported system actions.
- Handle common English and Hindi/Hinglish intents for YouTube search/play, contact calls, WhatsApp messages, scrolling, settings, alarms, and other built-in commands.
- Use an optional Gemini API key for multi-step planning against the current Accessibility UI tree. Common deterministic commands do not require the Gemini planner.

## Important limits

- Android requires the user to grant microphone and other runtime permissions and manually enable Muse Screen Controller in **Settings → Accessibility**. The service cannot grant these permissions itself.
- Android can stop background microphone services, especially after reboot or under battery restrictions. The boot receiver requests startup, but Android version/device policy determines whether a microphone foreground service can start. **Continuous 24/7 availability cannot be guaranteed.** Keep the foreground notification enabled and reopen Muse if the OS stops it.
- “Always-on” means the microphone is active to detect the wake word. Android's offline-recognition preference is not a guarantee that audio never reaches the device's configured speech service. The app responds to commands only after “Muse” (or when the user explicitly wakes it from the app).
- Accessibility automation depends on what the target app exposes to Android. App redesigns, protected views, permission dialogs, and unavailable controls can prevent an action.
- Muse will not bypass OTPs, CAPTCHAs, age checks, or other verification. Account signup needs real user details and may require the user to complete private or verification steps. A named signup request opens that app rather than turning it into a Google search.
- Phone calls and messages require the relevant permissions/apps and a clearly identified contact/message. WhatsApp UI behavior varies by version; check the recipient and result on the phone.

## Architecture

| Layer | Source | Responsibility |
|---|---|---|
| Voice service and wake loop | `VoiceAgentForegroundService.kt` | Foreground microphone service, wake-word recognition, speech-to-text dispatch, text-to-speech feedback, and command lifecycle. |
| Intent parsing | `VoiceCommandParser.kt` | Tested English/Hinglish parsing for YouTube queries, calls, WhatsApp recipients/messages, and signup targets. |
| Screen controller | `AgentAccessibilityService.kt` | Reads the visible accessibility tree and performs supported taps, typing, scrolling, and gestures. Must be enabled manually by the user. |
| Action executor and planner | `TaskActionExecutor.kt` | Executes built-in Android/app actions and, when configured, runs a bounded Gemini screen-planning loop. |
| App UI and setup | `MainActivity.kt` | Permission onboarding, wake-mode toggle, API-key configuration, service status, and Accessibility setup entry point. |
| Boot receiver | `BootCompletedReceiver.kt` | Requests service startup after boot, subject to Android's foreground-service and microphone restrictions. |

## Setup

1. Install the debug APK from the repository's latest successful **MetaWork Android CI** run, or open the project in Android Studio and build `app`.
2. Grant **Microphone** permission. Contacts, Phone, SMS, and Notifications permissions are used only for their corresponding features.
3. Enable **Muse Screen Controller** under Android **Settings → Accessibility → Installed apps** for cross-app screen reading/actions.
4. Disable battery optimization for MetaWork if you want the OS to be less aggressive about stopping the foreground service. Device policy may still stop it.
5. Add a Gemini API key in the app only if you want the multi-step Gemini planner. Keep the key private; do not commit it to source control.

## Example commands

Say the wake word followed by one request:

- “Muse, open YouTube and play Arijit Singh.”
- “Muse, YouTube par Arijit Singh ke gaane chalao.”
- “Muse, call Rahul.” / “Muse, Rahul ko phone lagao.”
- “Muse, WhatsApp par Rahul ko ‘I will be late’ bhejo.”
- “Muse, scroll down three times.”
- “Muse, create an Instagram account.” — opens Instagram; signup may require the user to provide details and complete verification.
- “Muse, stop listening.” / “Muse, enable wake word.”

Without the wake word, voice commands are ignored while the service is in wake-word standby. The user can turn standby listening off in the app or by voice.

## Build and tests

The GitHub Actions workflow runs `testDebugUnitTest` and `assembleDebug`. Voice parsing regression tests live under `app/src/test/`.

## License

Distributed under the [MIT License](LICENSE).
