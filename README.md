<div align="center">

# ⏱️ CockBlocker

**Uncompromising, tamper-resistant Pomodoro focus enforcement for Android.**

[![Build Android APK](https://github.com/blamingcksh/CockBlocker/actions/workflows/build-apk.yml/badge.svg)](https://github.com/blamingcksh/CockBlocker/actions/workflows/build-apk.yml)
[![Platform](https://img.shields.io/badge/Platform-Android_8.0+_(API_26+)-brightgreen.svg?style=flat-square)](https://android.com)
[![License](https://img.shields.io/badge/License-Proprietary-blue.svg?style=flat-square)](#)
[![Zero Network](https://img.shields.io/badge/Network-0%20Requests-success.svg?style=flat-square)](#)

<p align="center">
  <b>CockBlocker blocks impulse, distractions, and digital temptations with zero escape hatches.</b><br>
  Built for individuals who need absolute, non-negotiable discipline.
</p>

</div>

> [!NOTE]
> ### 🤡 What is this?
> Let's be real: you have zero dopamine receptor integrity left and your attention span is fried beyond recognition. This app is an anti-goon device-admin hostage situation disguised as a Pomodoro timer. It locks your phone, bounces you to Home when you get itchy thumbs, and locks the Android Settings app so you can't even force-stop or uninstall it mid-session. There is no cancel button. There is no safe word. Suffer and study.

> [!CAUTION]
> ### ⚠️ HARD LOCKOUT WARNING — READ BEFORE USE
>
> **THERE IS INTENTIONALLY NO EARLY RELEASE BUTTON.**
>
> * **Zero Override During Sessions**: Once a Pomodoro focus block starts, it runs until the signed, monotonic hardware timer hits zero. You cannot pause, shorten, or dismiss it.
> * **Settings App is Blocked**: The Android Settings application is blocked by default during active sessions to prevent force-stopping or uninstalling.
> * **Safety-Critical Whitelist**: Critical emergency functions (Emergency dialer, Phone, Default SMS) are hardcoded and will never be blocked under any condition.
> * **Do not engage in long lock sessions** if you anticipate needing arbitrary access to work, banking, or non-whitelisted apps.

---

## ✨ Features

- 🔒 **Zero-Bypass Focus Sessions**: Strict Pomodoro timer backed by signed, monotonic hardware clocks to prevent clock-tampering.
- 🚫 **Instant Foreground Redirection**: Leverages Android Accessibility Services to detect blocked app launches and immediately bounce them back to the Home screen.
- 🛡️ **Tamper-Resistant Journaling**: Monitored session state stored in an encrypted/signed journal with watchdog service protection.
- 🔕 **Notification Suppression**: Cancels distracting notifications from blocked applications on a best-effort basis without storing or inspecting message contents.
- 📴 **Zero Network Footprint**: Contains **0 network permissions, 0 tracking SDKs, 0 third-party analytics, and 0 ads**. Your data never leaves your device.
- ⏱️ **Focus Dial UI**: Custom, reactive dial control to set intervals and view active countdown health.

---

## 🏗️ Architecture & Permissions

CockBlocker operates strictly locally on your device using Android system capabilities:

| Permission / Service | Usage & Scope |
| :--- | :--- |
| **Accessibility Service** | Reads the foreground app package name only to bounce blocked apps back to Home. **Does not read screen text, inspect keystrokes, or simulate taps.** |
| **Notification Listener** | Intercepts package metadata to dismiss incoming alerts from blocked apps. Discards notification text immediately. |
| **Usage Stats** | Fallback layer to detect active foreground packages when accessibility state transitions lag. Never stores usage history. |
| **System Alert Window** | Provides a minimal, non-intrusive redirection surface when a restricted app is launched. |
| **Device Admin** *(Optional)* | Prevents accidental deactivation on supported Android builds. CockBlocker cannot lock the screen or wipe storage. |

---

## 📦 How to Download & Install

You don't need Android Studio installed to get the APK. GitHub Actions builds it automatically on every commit.

### Downloading the APK from GitHub Actions

1. Open the [**Actions** tab](https://github.com/blamingcksh/CockBlocker/actions) in this repository.
2. Click on the latest workflow run under **Build Android APK**.
3. Scroll down to the **Artifacts** section at the bottom of the page.
4. Click **`cockblocker-debug-apk`** to download the zip containing the ready-to-install APK.
5. Unzip and transfer `app-debug.apk` to your Android device to install.

---

## 🛠️ Building Locally

To build from source on your local machine:

```bash
# Clone the repository
git clone https://github.com/blamingcksh/CockBlocker.git
cd CockBlocker

# Build the Debug APK
./gradlew assembleDebug

# Output APK path:
# app/build/outputs/apk/debug/app-debug.apk
```

To run unit tests:

```bash
./gradlew testDebugUnitTest
```

---

<div align="center">
  <sub>Built for self-mastery. Dedicated to focus.</sub>
</div>
