# Messages.png

This is a [Garmin Connect IQ](https://apps.garmin.com/en-US/) watch app, which displays your phone's notifications (selectable from which apps) as a 1-bit monochrome image.

This enables the user to read notifications in any language which is not possible on some garmin devices.

This project is the Android companion app.

## Overview

This is the Android companion app. You'll need a Garmin Connect IQ watchapp or this doesn't make sense.

See [Connect IQ Watchapp](https://github.com/starryalley/Messages.png)

## Functions

This Android app creats a Service running in the background, monitoring selected apps' notifications. It exposes a semi-restful http service on 127.0.0.1 (localhost) for whatapp to access. (The builtin garmin communication module is buggy and can't be used reliably. [See bug report here](https://forums.garmin.com/developer/connect-iq/i/bug-reports/failure_during_transfer)).

Currently no UI is built in this app and I plan to add:
* configurable notifciation source (which app's notification to send through)
* statistics
* configurable font size and preferences

## Status

No UI is there.

Currently only tested on Fenix 6 Pro and Vivoactive 4s.

Not yet submitted to Google Play.
