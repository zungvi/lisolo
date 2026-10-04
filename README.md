# Zungvi Messenger Android client

This is the source code for the Zungvi Messenger Android client.

Zungvi Messenger for Android is based on [Snikket](https://snikket.org/) and [Conversations](https://conversations.im/) by Daniel Gultsch.

## Building

See [docs/BUILDING.md](docs/BUILDING.md) for prerequisites, the build variants, and how to
produce a debug or release APK.

Quick start — a debug APK for an arm64 phone such as a Galaxy S23:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew assembleConversationsFreeDebug
adb install -r -t build/outputs/apk/conversationsFree/debug/Lisolo-*-conversations-free-arm64-v8a-debug.apk
```

The official Conversations repository is available at: https://codeberg.org/iNPUTmice/Conversations

Copyright (c) 2014-2025 Daniel Gultsch and Snikket Community Interest Company.

Snikket and the Snikket logo are trademarks of Snikket Community Interest Company.

Licensed under GPL License Version 3.
