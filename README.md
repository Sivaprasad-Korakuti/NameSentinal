# Name Sentinel (Android, built for OPPO Reno 10 5G / Android 13+)

## Get the APK without Android Studio
1. Create a free GitHub repo and upload everything in this folder (keep the .github folder).
2. Open the repo > Actions > "Build APK" > wait ~5 min > download the NameSentinel-APK artifact.
3. Unzip, copy app-debug.apk to the phone, install (allow "install unknown apps").

## Or with Android Studio
Open this folder, let Gradle sync, press Run with the phone connected by USB.

## First run on the Reno 10
Tap the sentinel, allow Microphone, Notifications and Nearby devices. In the setup dialog do
Battery > unrestricted, Auto-launch > ON, then lock the app card in Recent apps.
Arm it once: with earbuds off it sits on standby (mic off, near-zero battery) and starts listening by itself
when your earbuds connect. After a reboot it posts a "tap to resume" notification.
# NameSentinal
