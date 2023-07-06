# Unquestionify Android app Privacy Policy

This app does not collect and even connect to the internet. The app monitors all notifications on your device though. It doesn't store the data in any form, and will only read the notification content (when configured to do so) and create 1-bit image of the content. The Garmin Connect app then access the generated image through http at 127.0.0.1 (localhost).

If you look at `AndroidManifest.xml` there is `android.permission.INTERNET` there. That's because the NanoHTTPD, an utility used by this app, needs it to create a http server on your phone so Garmin Connect can query the generated 1-bit image through http://127.0.0.1:8080/.

