# Sortia

Your files, in order. One tap.

Sortia is an Android app that sorts the files, photos and videos on your phone into tidy folders, safely, with undo.

## What it does

- **Organize now**: scans your folders and files them into `Sortia/Images`, `Videos`, `Audio`, `Documents`, `Archives`, `Apps` and `Other`. Documents are split into `PDF`, `Word`, `Excel`, `PowerPoint` and `Other`. Photos and videos can go into `Year/Month` folders, using the date in the file name, the date the photo was taken (EXIF), or the file date.
- **Preview**: see exactly how many files of each type will move before anything happens (you can switch this off for a true one-tap run).
- **Undo**: every run is logged. Put back one run, any time, from History.
- **Find duplicates**: compares what is inside the files (not just names), keeps the oldest copy, and moves the extras to `Sortia/Duplicates` or deletes them to free space.
- **Auto-tidy**: optional daily or weekly background run, with a notification of what it did.
- **Folders and options**: choose Downloads, Documents, Pictures, Movies, Music, loose files and (optionally) Camera.

## Safe by design

- Never touches app data (`Android/`), hidden files, or files still downloading.
- Never overwrites: a name clash becomes `name (1).ext`.
- Never uploads anything. Everything happens on your phone.
- Camera (DCIM) is off by default, because moving camera photos can stop cloud backup apps from seeing them.

## Install

GitHub builds the APK for you.

1. Open the **Actions** tab and wait for **Build Android APK** to go green (or press **Run workflow**).
2. Download `Sortia.apk` from the **Releases** page (tag `latest`) and install it (allow "install unknown apps" for your browser).
3. Open Sortia and tap **Allow access**. Android asks for "All files access" once; this is what lets Sortia move files.

## Folders

- `android/`: Kotlin + Jetpack Compose app (`Engine.kt` is the organizer, `Work.kt` the auto-tidy)
- `.github/workflows/`: APK build
