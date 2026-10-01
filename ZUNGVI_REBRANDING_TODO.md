# Zungvi Messenger Rebranding Roadmap

This plan converts the current `snikket-android` app into **Zungvi Messenger** while keeping the first releasable build small, testable, and low-risk. The priority is to ship a correctly branded, installable, working Zungvi app before doing broad package or source-tree refactors.

## Guiding Decisions

- [x] **Choose the launch package name**
  - Recommended: `com.zungvi.messenger`
  - Keep this stable once published. Changing it later creates a new app identity for Android and app stores.

- [x] **Choose the flavor strategy before editing resources**
  - Recommended first release: reuse the existing `conversations` mode flavor as the Zungvi app and keep `free` / `playstore` as distribution flavors.
  - Avoid adding new `zungviStandard`, `zungviFree`, or `zungviPlaystore` flavors unless there is a real need to keep Snikket/Conversations builds alive in the same checkout.
  - Defer deleting `quicksy` until the Zungvi build is green, because it may share assumptions in Gradle, source sets, or resources.

- [x] **Avoid Java package refactoring for the first release**
  - Keep `namespace 'eu.siacs.conversations'` initially unless there is a specific technical or store requirement to change it.
  - Change `applicationId` for install identity.
  - Treat `eu.siacs.conversations` and `im.conversations.android` package names as internal implementation details for now.

- [ ] **Define launch infrastructure**
  - [x] Default account/server domain: `zungvi.com`
  - [x] Help URL: `https://zungvi.com/help`
  - [x] Privacy policy: `https://zungvi.com/privacy`
  - [ ] Terms URL or in-app terms source.
  - [x] Bug/support JID: `bugs@zungvi.com`
  - [x] Channel discovery endpoint remains the public XMPP search endpoint for now.
  - [x] Push strategy: keep existing upstream push implementation/config untouched for this rebrand pass.

## Phase 1: Minimal Buildable Zungvi App

Goal: produce an APK that installs as Zungvi Messenger and still passes the existing app flows.

- [x] **Update Gradle identity**
  - File: `build.gradle`
  - Change default `applicationId` from `org.snikket.android` to the chosen Zungvi ID.
  - Change default `appName` / generated `APP_NAME` to `Zungvi Messenger`.
  - Update the `conversations` flavor `PRIVACY_POLICY` to the Zungvi privacy URL.
  - Keep the existing `free` and `playstore` distribution flavors.

- [x] **Verify provider authorities remain applicationId-based**
  - File: `src/main/AndroidManifest.xml`
  - Confirm FileProvider uses `${applicationId}.files`.
  - Confirm BarcodeProvider uses `${applicationId}.barcodes`.
  - Do not hardcode provider authorities unless a store or migration requirement demands it.

- [x] **Update app theme reference**
  - Files: `src/main/AndroidManifest.xml`, `src/conversations/AndroidManifest.xml`, `src/main/res/values/themes.xml`, `src/main/res/values-night/themes.xml`
  - Rename or alias `Theme.Snikket` to `Theme.Zungvi`.
  - Keep old theme aliases temporarily if many references remain.

- [x] **Update core config**
  - File: `src/main/java/eu/siacs/conversations/Config.java`
  - Update bug report JID, help URL, default/magic create domain, registration behavior, and channel discovery.
  - Decide whether Zungvi is invite-only, open registration, or manual server login.

- [x] **Build both primary debug variants**
  - [x] Installed local Temurin JDK 21 at `/home/sammy/.gradle/jdks/jdk-21.0.12.1+1`.
  - [x] Run `./gradlew assembleConversationsFreeDebug`.
  - [x] Run `./gradlew assembleConversationsPlaystoreDebug`.
  - [x] Fixed missing Play Store `app_server` resource with Zungvi domain.

## Phase 2: Visible Brand Sweep

Goal: remove user-visible Snikket/Conversations/Quicksy naming from the Zungvi user experience without breaking translations or internal code.

- [ ] **Update English source strings first**
  - Files: `src/main/res/values/strings.xml`, `src/conversations/res/values/snikket.xml`, relevant `src/*/res/values/*.xml`
  - [x] Replace launch-critical visible app names with `Zungvi Messenger` or `Zungvi`.
  - [x] Continue auditing secondary English strings and flavor-specific text.
  - Keep protocol or project references accurate. For example, "based on Conversations" may belong in legal/about text, but not in onboarding copy.
  - Do not blindly replace class names, package names, XML resource IDs, or technical identifiers.

- [ ] **Handle localized strings deliberately**
  - Files: `src/main/res/values-*/strings.xml`, `src/conversations/res/values-*/snikket.xml`
  - Replace only clearly branded strings for launch-critical locales.
  - [x] Removed localized `snikket_intro` overrides that still said Snikket so Android falls back to the updated English Zungvi intro.
  - [ ] Track and commission proper translated Zungvi strings for store/account setup before enabling localized Play Store listings.

- [ ] **Update onboarding and account copy**
  - Review `WelcomeActivity`, `PickServerActivity`, `MagicCreateActivity`, `EasyOnboardingInviteActivity`, and their layouts/strings.
  - [x] Update launch-critical English provider/onboarding strings from conversations.im/Conversations/Quicksy to Zungvi wording.
  - Make copy match the chosen account model:
    - Invite-only Zungvi account.
    - Zungvi-hosted public registration.
    - Bring-your-own XMPP server.

- [ ] **Update user documentation**
  - Files: `docs/user/backup.md`, `docs/user/migrating_to_new_device.md`, other `docs/user/*.md`
  - [x] Replace visible "Conversations" instructions in backup and migration docs with "Zungvi Messenger".
  - Verify backup folder names from the actual app behavior before documenting them.
  - Update screenshots after the visual brand pass.

- [x] **Update legal/about text**
  - Files: `src/main/res/values/about.xml`, `src/*/res/layout/activity_tos.xml`, license/about screens
  - Keep upstream attribution where required.
  - Make the user-facing app identity Zungvi.

## Phase 3: Visual Assets and Theme

Goal: make the installed app look like Zungvi across launcher, splash, notifications, onboarding, and in-app surfaces.

- [x] **Replace launcher assets**
  - Files: `src/*/res/mipmap-*`, `src/*/res/mipmap-anydpi-v26`
  - [x] Replace `new_launcher`, `ic_launcher`, and round/adaptive launcher assets for the Zungvi flavor path being shipped.
  - Verify icon appearance in light/dark launchers and Android adaptive icon masks.

- [x] **Replace splash and onboarding logos**
  - Files: `src/*/res/drawable*`, `art/render.rb` if asset generation is still used
  - [x] Replace `main_logo`, `splash_logo`, and related branded drawables.
  - Prefer source vector or high-resolution master assets checked into the repo.

- [x] **Replace notification assets**
  - Files: notification icon drawables and any status icon resources
  - [x] Use a proper monochrome silhouette for Android notification status bar icons.
  - Verify visibility on light and dark notification surfaces.

- [x] **Apply Zungvi colors**
  - Files: `src/main/res/values/colors-md.xml`, `src/main/res/values/themes.xml`, `src/main/res/values/styles.xml`
  - Update primary, secondary, accent, status bar, and message-related colors.
  - [x] Apply orange/white palette from the real Zungvi Messenger logo.
  - Check contrast in light and dark mode.

## Phase 4: Store, Distribution, and Release Metadata

Goal: prepare app-store-facing identity and release artifacts.

- [ ] **Update Fastlane metadata**
  - Files: `src/conversations/fastlane/metadata/android/` or the selected shipping metadata path
  - [x] Updated English `short_description.txt`, `full_description.txt`, and store icon.
  - [ ] Add screenshots, feature graphic, release changelog, and contact URLs when Play Console assets are available.
  - [ ] Decide whether localized metadata ships now or waits for translation.

- [ ] **Update repository metadata**
  - Files: `README.md`, `CHANGELOG.md`, `conversations.doap`, issue templates, CI labels if applicable
  - [x] Update README project identity while preserving upstream attribution.
  - Explain that Zungvi Messenger is based on Snikket/Conversations where attribution is needed.
  - Replace obsolete project links with Zungvi links.

- [ ] **Configure signing and release channels**
  - Confirm `keystore.properties` expectations.
  - Confirm Play Store, F-Droid, direct APK, or internal distribution target.
  - Verify version code/name policy for the first Zungvi release.

## Phase 5: Functional Verification

Goal: prove the rebrand did not break messaging, account setup, backup, notifications, or release builds.

- [ ] **Build checks**
  - `./gradlew clean`
  - [x] `./gradlew assembleConversationsFreeDebug`
  - [x] `./gradlew assembleConversationsPlaystoreDebug`
  - Release build when signing is configured.

- [ ] **Install and launch checks**
  - Fresh install on emulator/device.
  - Upgrade install over a previous Zungvi debug build.
  - Confirm launcher name, icon, splash, about screen, and app settings label.

- [ ] **Account and messaging checks**
  - Login with existing XMPP account.
  - Create/register account if enabled.
  - Send and receive 1:1 messages.
  - Join/create group chat where supported.
  - Send image/file via HTTP upload.
  - Verify OMEMO encrypted messages.
  - Verify audio/video calling if enabled.

- [ ] **System integration checks**
  - Push notifications through the selected distribution path.
  - Notification channel names and icons.
  - Share sheet send/receive flows.
  - URI handling for `xmpp:` links and invite links.
  - Backup export and restore.
  - Android Auto / call integration if still supported.

## Phase 6: Optional Cleanup After First Green Release

Do this only after Zungvi has a working build and verified core flows.

- [ ] **Rename flavors if still valuable**
  - Consider replacing `conversations` with `zungvi`.
  - Consider deleting `quicksy` source sets and dependencies.
  - Update CI, Fastlane paths, and build docs at the same time.

- [ ] **Remove unused legacy features**
  - Quicksy SMS verification.
  - Quicksy contact discovery.
  - Snikket-specific onboarding assumptions that no longer apply.
  - Obsolete REST endpoints or hardcoded domains.

- [ ] **Consider namespace/package refactoring**
  - Only do this if there is a clear benefit.
  - Plan it as a separate mechanical refactor with dedicated build verification.
  - Include ProGuard/R8 rules, data binding references, generated code, manifests, tests, and import rewrites.

## Tracking Queries

Use these checks during the conversion. Prefer reviewing results before editing rather than bulk replacing blindly.

```sh
grep -RIn "Snikket\\|snikket\\|Conversations\\|Quicksy\\|quicksy" build.gradle settings.gradle src docs README.md CHANGELOG.md 2>/dev/null
grep -RIn "org.snikket.android\\|snikket.org\\|quicksy.im\\|conversations.im" build.gradle src docs 2>/dev/null
grep -RIn "Theme.Snikket\\|TextAppearance.Snikket" src/main/res src/conversations/res 2>/dev/null
```

## Launch Acceptance Criteria

- [x] The selected Zungvi debug variants build successfully.
- [ ] The APK installs as `Zungvi Messenger` with the correct package/application ID.
- [ ] Launcher, splash, notification, onboarding, settings, about, backup, and docs no longer show stale branding in English.
- [ ] Privacy/help/support links point to Zungvi-controlled destinations.
- [ ] Core XMPP flows work on a real device or emulator.
- [ ] Push behavior is verified for the intended distribution channel.
- [ ] Upstream attribution and license text remain accurate.
