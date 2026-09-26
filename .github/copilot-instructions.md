# Sound Stop Timer

- Native Android app written in Kotlin with Jetpack Compose.
- Minimum Android version is API 31; build with JDK 17 or newer and compile SDK 36.
- Bluetooth input is routed through AudioManager communication devices. The monitor must stop and restore the prior audio mode/device when it ends.
- Capture PCM only for live level detection. Do not store or transmit microphone audio.
- Keep user-facing strings in Japanese and preserve the existing Compose visual language.