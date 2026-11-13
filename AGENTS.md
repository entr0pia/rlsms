# AGENTS.md

Welcome! This document provides technical context, architectural guidelines, and executable commands for AI coding agents (such as Antigravity, Cursor, Claude Code, Aider, etc.) operating in this repository.

---

## 1. Project Overview

**QKSMS** is a feature-rich, open-source SMS/MMS messaging client for Android, distributed under the GNU General Public License v3.0 (GPLv3).

### Key Architecture Patterns
The project adheres strictly to **Clean Architecture** principles separated across multiple Gradle modules:

```
+-----------------------------------------------------------+
|                      :presentation                        |
|   (Conductor Controllers, Presenters/ViewModels, UI, DI)  |
+-----------------------------+-----------------------------+
                              | depends on
                              v
+-----------------------------------------------------------+
|                         :domain                           |
|       (Pure Kotlin/Java: Entities, Use Cases/Interactors, |
|                 Repository Interfaces)                    |
+-----------------------------+-----------------------------+
                              ^
                              | implements
+-----------------------------+-----------------------------+
|                          :data                            |
| (Realm DB, ContentResolver/Telephony Providers, Repos)    |
+-----------------------------------------------------------+
        |                                       |
        v                                       v
+------------------+                   +--------------------+
|     :common      |                   |   :android-smsmms  |
| (Shared utils)   |                   | (PDU, APN, MMS Lib)|
+------------------+                   +--------------------+
```

### Module Responsibilities
- **`:presentation`**: Android Application module containing the UI layer. Uses **Conductor** (Controllers instead of Android Fragments), ViewPager2, Material components, and Dagger-Android.
- **`:domain`**: Business logic layer defining core models, Interactors / Use Cases, and Repository contracts.
- **`:data`**: Implements Repository interfaces using **Realm** for local caching/preferences and Android `Telephony`/`ContentResolver` providers for SMS/MMS storage.
- **`:common`**: Shared extensions, common abstractions, and utilities.
- **`:android-smsmms`**: Customized telephony library handling low-level PDU encoding/decoding, MMS transactions, and APN management.

---

## 2. Technical Stack & Environment

- **Languages**: Kotlin `1.7.20` & Java `1.8` (Source/Target Compatibility: 1.8)
- **Target SDK**: `33` (Android 13) | **Compile SDK**: `33` | **Min SDK**: `23` (Android 6.0 Marshmallow)
- **Application ID**: `com.entr0pia.RLSMS` | **App Name**: `RLSMS`
- **Build Tools Version**: `33.0.1` / `34.0.0`
- **Gradle Version**: `7.4` (Android Gradle Plugin: `7.3.1`)
- **Reactive & Async**: RxJava 2 (`2.1.4`), RxKotlin, RxAndroid, RxBinding, AutoDispose (`1.3.0`), Kotlin Coroutines (`1.4.3`)
- **Dependency Injection**: Dagger 2 (`2.16`) with `dagger-android`
- **Database**: Realm (`10.13.0`)
- **UI Frameworks**: Conductor (`2.1.5`), AndroidX Material Components, Glide (`4.8.0`), ExoPlayer (`2.8.1`)

---

## 3. Product Flavors & Build Configurations

The application defines a single flavor dimension `analytics`:
- **`noAnalytics` (Recommended for Local Dev & Testing)**:
  - Intended for F-Droid and local builds.
  - Does **not** require Firebase credentials (`google-services.json`) or Google Play Billing.
- **`withAnalytics`**:
  - Intended for Google Play releases.
  - Bundles Firebase Crashlytics and Google Play In-App Billing (`com.android.billingclient`).
  - Requires Google Play Services configuration and valid keystores.

> **Agent Note**: Always default to building or testing against the `noAnalytics` flavor locally unless explicitly requested otherwise.

---

## 4. Development Workflow & Commands

All commands below assume execution from the repository root using the Gradle Wrapper. On Windows PowerShell, use `.\gradlew.bat`; on Linux/macOS, use `./gradlew`.

### Build Commands
- **Assemble Debug APK (Recommended)**:
  ```powershell
  .\gradlew.bat :presentation:assembleNoAnalyticsDebug
  ```
  *Output APK location*: `presentation/build/outputs/apk/noAnalytics/debug/`

- **Assemble Release APK (with Analytics / Proguard)**:
  ```powershell
  .\gradlew.bat :presentation:assembleWithAnalyticsRelease
  ```

- **Compile Sources & Code Analysis**:
  ```powershell
  .\gradlew.bat compileNoAnalyticsDebugSources
  ```

- **Clean Project Artifacts**:
  ```powershell
  .\gradlew.bat clean
  ```

### Testing Commands
- **Run Unit Tests (noAnalytics)**:
  ```powershell
  .\gradlew.bat :presentation:testNoAnalyticsDebugUnitTest
  ```
  *(Or run all unit tests across all modules)*:
  ```powershell
  .\gradlew.bat testNoAnalyticsDebugUnitTest
  ```

- **Run Specific Unit Test Class**:
  ```powershell
  .\gradlew.bat :presentation:testNoAnalyticsDebugUnitTest --tests "com.moez.QKSMS.feature.main.MainViewModelTest"
  ```

- **Assemble Instrumented Tests (without running on device)**:
  ```powershell
  .\gradlew.bat assembleAndroidTest
  ```

- **Run Instrumented Tests on Connected Device/Emulator**:
  ```powershell
  .\gradlew.bat connectedNoAnalyticsDebugAndroidTest
  ```

### Code Style & Lint
- **Run Android Lint**:
  ```powershell
  .\gradlew.bat lintNoAnalyticsDebug
  ```

---

## 5. Architectural & Coding Conventions

### 1. Conductor Controller Architecture
- The application avoids `Fragment` and standard `FragmentManager` lifecycles in favor of **Conductor `Controller`** classes (`com.bluelinelabs.conductor`).
- Custom controllers extend base classes in `com.moez.QKSMS.common.base`.
- View binding is lifecycle-aware and managed with AutoDispose:
  ```kotlin
  // Ensure subscriptions are bound to Controller lifecycle
  myObservable
      .autoDispose(scopeProvider)
      .subscribe { ... }
  ```

### 2. Realm Database Guidelines
- **Thread Safety**: Realm objects (`RealmObject`) are confined to the thread on which they were queried. Never pass managed Realm instances across threads.
- Map Realm data to pure Kotlin immutable domain entities before handing data to the Domain or Presentation layer.
- **Gradle Plugin Order**: In `build.gradle` files, `apply plugin: 'realm-android'` **must** precede `apply plugin: 'kotlin-android'`, otherwise Kapt and bytecode weaving will fail.

### 3. Dependency Injection (Dagger 2)
- Inject dependencies via constructor injection wherever possible.
- UI components (Controllers, Services, Receivers) obtain injectables through `@Subcomponent` or module definitions under `presentation/src/main/java/com/moez/QKSMS/injection/`.

### 4. Telephony & Default SMS App Constraints
- QKSMS functions as an Android Default SMS App.
- Essential receivers such as `SmsReceiver`, `MmsReceiver`, `HeadlessSmsSendService`, and response action handlers are registered in `presentation/src/main/AndroidManifest.xml`.
- When modifying message sending or receiving flows, ensure compliance with Android Telephony Provider requirements (Android 4.4+ KitKat and Android 10+ scoped rules).

---

## 6. Important Gotchas for Coding Agents

1. **Gradle & JDK Compatibility (CRITICAL)**:
   - The project uses Gradle `6.0.1` and AGP `3.5.4`, which are **strictly incompatible with JDK 14+** (fails with Groovy `ReflectionCache` errors on JDK 17+).
   - **Always run Gradle with JDK 8 or JDK 11**:
     ```powershell
     $env:JAVA_HOME = "C:\Users\Peyton\scoop\apps\openjdk11\current"
     .\gradlew.bat :presentation:assembleNoAnalyticsDebug
     ```
2. **NDK & JNI Symbol Stripping**:
   - Modern Android SDK NDKs (v23+) removed the deprecated GCC toolchain (`arm-linux-androideabi-strip`), which AGP 3.5.4 expects.
   - The project configuration disables GCC symbol stripping and automatically copies native libraries (`librealm-jni.so`) from `merged_native_libs` to `stripped_native_libs` before packaging in `presentation/build.gradle`, ensuring native libraries are never omitted from APKs.
3. **Debug Keystore Encryption**:
   - Keystores created with newer JDKs (Java 17/21/25) default to `HmacPBESHA256`, unsupported by older Java 11 runtimes. Ensure `~/.android/debug.keystore` is generated using Java 11.
4. **Secrets & Credentials**:
   - `secrets.tar.enc` is an encrypted archive for CI builds containing release signing keystores and official `google-services.json`. Do not attempt to commit decrypted keystores or credentials into git.
5. **PowerShell Path Separators**:
   - When executing commands on Windows pwsh, use backslashes `\` for file paths and `;` for command separators. Always prefer `.\gradlew.bat` over `./gradlew`.
6. **Git Safety**:
   - Do not commit changes without human approval.
   - Do not run `git push` or modify remote branch configurations automatically.
