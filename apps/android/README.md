# TukuMail Android

Native Material 3 client for the first TukuMail vertical slice. Debug builds point to `10.0.2.2:8080`; release builds point to `https://mail.tukutuku.org/api/v1`.

This first client deliberately uses the TukuMail message API so the app is functional before importing the much larger Thunderbird/K-9 upstream tree. The next hardening milestone is to import the Apache-2.0 Thunderbird Android mail-core modules for offline-first local storage, direct IMAP IDLE/push, attachment handling and mature MIME behaviour while preserving this Tuku-owned UI and package identity.

Build with Android Studio or `gradle :app:assembleDebug` using Gradle 8.11.1/JDK 17.
