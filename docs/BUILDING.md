# Building Lisolo

How to produce an APK of the Lisolo Android client, install it on a phone, and
run the tests.

## Prerequisites

| Tool | Version | Notes |
| --- | --- | --- |
| JDK | **21** | `compileOptions` targets Java 21; 17 will not compile the project |
| Android SDK | API 36 | `targetSdkVersion 36`, `minSdkVersion 23` |
| Gradle | 8.14.3 | Supplied by the wrapper — use `./gradlew`, do not install Gradle |
| Android Gradle Plugin | 8.13.2 | Pinned in `build.gradle` |

### Java is the usual first failure

If `./gradlew` exits immediately with:

```
The operation couldn't be completed. Unable to locate a Java Runtime.
```

there is no JDK on your `PATH` and `JAVA_HOME` is unset. This happens on a Mac with no system
Java and no Android Studio bundled runtime. Install one and point the build at it:

```bash
brew install openjdk@21
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
```

Add that `export` to `~/.zshrc` to make it permanent, or prefix individual commands with it:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew assembleConversationsFreeDebug
```

Every command below assumes `JAVA_HOME` is set.

## Build variants

There are two flavour dimensions, so a variant name is
`<mode><Distribution><BuildType>`:

| Dimension | Values | Meaning |
| --- | --- | --- |
| `mode` | `conversations`, `quicksy` | `conversations` is Lisolo. `quicksy` is the upstream phone-number product and is not shipped. |
| `distribution` | `free`, `playstore` | `free` enables contacts integration; `playstore` adds Firebase messaging and the install referrer, and disables contacts integration. |
| build type | `debug`, `release` | `release` runs R8 (minify + resource shrinking). |

**For everyday work and side-loaded test builds, use `conversationsFreeDebug`.**

## Recipes

### Debug build for a Galaxy S23 (and any modern Android phone)

The S23 is `arm64-v8a`. This is the smallest APK that will run on it:

```bash
./gradlew assembleConversationsFreeDebug
```

Then take:

```
build/outputs/apk/conversationsFree/debug/Lisolo-<version>-conversations-free-arm64-v8a-debug.apk
```

### Universal debug build

The same command also produces a universal APK containing every ABI. It is roughly 60% larger but
installs on any device, which is useful when you do not know the target's architecture:

```
build/outputs/apk/conversationsFree/debug/Lisolo-<version>-conversations-free-universal-debug.apk
```

### Release build

```bash
./gradlew assembleConversationsFreeRelease
```

Output lands in `build/outputs/apk/conversationsFree/release/`, named `…-release.apk`.

**Release APKs are unsigned unless `signing.properties` exists** in the repository root. The file
is gitignored and is read at configuration time; without it no `signingConfig` is attached and
the resulting APK cannot be installed. Create it with:

```properties
keystore=/absolute/path/to/zungvi-release.keystore
keystore.password=<password>
keystore.alias=<alias>
```

The same password is used for the store and the key.

For the Play Store build, swap the flavour:

```bash
./gradlew assembleConversationsPlaystoreRelease
```

## Output naming

APKs are named `Lisolo-<versionName>-<mode>-<distribution>-<abi>-<buildType>.apk`, for example
`Lisolo-2.19.8r2-conversations-free-arm64-v8a-debug.apk`. The version comes from `versionName` in
`build.gradle`, and the distribution flavour appends a suffix (`+free`, `+playstore`).

Each build produces five APKs — `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` and `universal` —
because ABI splits are enabled. Pick the one you need; the others are harmless.

## Installing on a phone

```bash
adb install -r -t build/outputs/apk/conversationsFree/debug/Lisolo-<version>-conversations-free-arm64-v8a-debug.apk
```

On the device, "Install unknown apps" must be allowed for whatever app opens the file if you are
side-loading rather than using `adb`.

### `INSTALL_FAILED_UPDATE_INCOMPATIBLE`

A debug APK is signed with the local debug key, so it cannot update an installation that came
from a release-signed build (or from another machine's debug key). Uninstall first:

```bash
adb uninstall com.zungvi.messenger
```

**This erases local chat history on that device.** Messages the server still holds in its MAM
archive re-sync after signing back in; drafts, unsent messages and local media do not.

## Local configuration

`local.properties` is gitignored and holds machine-local settings. Beyond the SDK path:

```properties
# Zungvi AI developer token, used by the AI features. Optional —
# when absent the AI settings section is hidden.
zungvi.ai.token=zap_...
```

Nothing secret belongs in `build.gradle`: it is committed, and anything compiled into an APK can
be extracted from it with `aapt2 dump strings`.

## Tests

```bash
./gradlew testConversationsFreeDebugUnitTest
```

Unit tests run on Robolectric with real Android resources (`testOptions.unitTests
.includeAndroidResources`). Results are written to
`build/test-results/testConversationsFreeDebugUnitTest/` and a readable report to
`build/reports/tests/testConversationsFreeDebugUnitTest/index.html`.

To run one class:

```bash
./gradlew testConversationsFreeDebugUnitTest --tests '*AboutMessageTest*'
```

## Verifying what is actually inside an APK

Gradle reporting `BUILD SUCCESSFUL` with everything `UP-TO-DATE` does not prove the APK contains
your change. To check a string resource directly:

```bash
$ANDROID_HOME/build-tools/35.0.0/aapt2 dump strings <apk> | grep "<text>"
```

## Known papercuts

- **`./gradlew tasks` fails** with `Could not create task ':spotlessJava' > No such reference
  '2.17.4'`. The Spotless ratchet is configured against a git ref that is not present. Build and
  test tasks are unaffected; only task listing breaks.
- **Piping Gradle through `tail` hides its exit code.** `./gradlew … | tail -40` reports the exit
  status of `tail`, so a failed build looks like a success. Redirect to a file and check `$?`
  instead.
