ARDUINO LED CONTROLLER - build steps

OPTION A - Android Studio (PC/laptop)
 1. Unzip, File > Open > select the ArduinoLEDController folder.
 2. Wait for Gradle sync (needs internet once).
 3. Build > Build Bundle(s)/APK(s) > Build APK(s).
 4. APK: app/build/outputs/apk/debug/app-debug.apk

OPTION B - GitHub builds it for you (no PC software)
 1. Create a free GitHub account and a new repository.
 2. Upload ALL files of this project (keep folders, including the hidden
    .github folder).
 3. Open the "Actions" tab > "Build APK" > wait ~5 minutes for the green tick.
 4. Open the run > Artifacts > download Arduino-LED-Controller-APK (zip),
    unzip it, install app-debug.apk on the phone (allow "install unknown apps").

On the phone: plug Arduino USB cable into OTG adapter, adapter into phone,
open the app, tap CONNECT USB, press OK on the permission popup.
