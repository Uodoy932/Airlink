# AirLink - stage 1 (screen mirroring Android -> Windows)

## PC
Run AirLink-Receiver.exe. Allow it through Windows Firewall (Private networks) when asked.
It shows a 6-digit PIN and its address.

## Phone
Get the APK: push this folder to a GitHub repo -> Actions tab -> "Build APK" -> download the AirLink-apk artifact.
(Or open the `android` folder in Android Studio and press Run.)
Install it, open AirLink, wait for the PC address to fill in (or type it), enter the PIN, tap Start mirroring.
Your browser opens on the PC showing the phone screen; double-click for fullscreen.

Phone and PC must be on the same Wi-Fi. Rebuild the .exe: `cd windows && GOOS=windows go build -o AirLink-Receiver.exe .`
