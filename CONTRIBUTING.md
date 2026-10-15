# Contributing to MetaWork

Thank you for contributing to **MetaWork** ([khitij376-alt/MetaWork](https://github.com/khitij376-alt/MetaWork)), an open-source Android voice assistant with optional screen automation.

## Contribution Guidelines

1. **Fork & Branch**: Fork `https://github.com/khitij376-alt/MetaWork` and create your feature branch from `main` (`git checkout -b feat/your-feature`).
2. **Protect API Keys**: Never commit `local.properties` or hardcoded API keys.
3. **Voice-First, User-Controlled**: Prefer voice-driven flows, but do not bypass Android permissions, CAPTCHA/OTP checks, private verification, or user-required confirmation. Accessibility automation may not work for every app or screen.
4. **Spoken Confirmation Before Payment**: Any booking or financial workflow must require explicit verbal confirmation from the user before tapping a final payment button.
