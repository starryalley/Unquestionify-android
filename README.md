# Unquestionify-Android

Unquestionify is a [Garmin Connect IQ](https://apps.garmin.com/en-US/) watch widget, which displays your phone's notifications (selectable from which apps) as a 1-bit monochrome image.

This enables the user to read notifications in any language which is not possible on some garmin Connect IQ devices. For example, Chinese text is display as diamond question marks on Fenix 6 sold in countries other than Taiwan and China.

This project is the Android companion app.

## Overview

This is the Android companion app. You'll need Unquestionify Garmin Connect IQ widget or this doesn't make sense.

See [Unquestionify watch widget](https://github.com/starryalley/Unquestionify)

## Functions

This Android app creats a Service running in the background, monitoring selected apps' notifications. It exposes a semi-restful http service on 127.0.0.1 (localhost) for watch to access. (The builtin garmin communication module is buggy and can't be used reliably. [See bug report here](https://forums.garmin.com/developer/connect-iq/i/bug-reports/failure_during_transfer)).

### Configuration

- Non-ASCII message only: when checked, only text which contains non-ASCII encoding will be passed to Unquestionify
- Append to old messages: when an Android StatusBarNotification comes, it looks for the same previous notification with the same key, and append the text to it. This reduces number of notifications seen in watch. It will only keep the latest 3 messages with the same key.
- text height on watch (pixels): 20 is fine in vivoactive 4s, while 25 is more appropriate for Fenix 6 Pro. 
- Select Notification Apps: select which applications to send notifications to Unquestionify

## Status

Currently only tested on Fenix 6 Pro and Vivoactive 4s.
