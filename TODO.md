# Zungvi Messenger Rebranding TODO

This document tracks all tasks required to rebrand the codebase into **Zungvi Messenger**, a dedicated XMPP-based secure messaging app.

---

## 1. App Identity & Package Configuration
- [ ] Update `namespace` (`com.zungvi.messenger`) and `applicationId` (`com.zungvi.messenger`) in `build.gradle`.
- [ ] Consolidate product flavors in `build.gradle` for Zungvi Messenger distributions (`zungviFree`, `zungviPlaystore`).
- [ ] Update FileProvider (`${applicationId}.files`) and BarcodeProvider (`${applicationId}.barcodes`) authorities in `src/main/AndroidManifest.xml`.
- [ ] Update intent actions and permissions to match `com.zungvi.messenger`.

---

## 2. Visual Branding & Assets
- [ ] Replace app icons in `src/main/res/mipmap-*` (`ic_launcher`, `ic_launcher_round`, `new_launcher`).
- [ ] Replace splash and main logos (`main_logo`, `splash_logo`) across `src/*/res/drawable/`.
- [ ] Update vector asset rendering script in `art/render.rb`.
- [ ] Replace notification icons (`ic_notification.png`) with Zungvi brand icon.

---

## 3. Strings & Localization
- [ ] Update `<string name="app_name">Zungvi Messenger</string>` in `src/main/res/values/strings.xml`.
- [ ] Replace occurrences of "Snikket", "Conversations", and "Quicksy" with "Zungvi".
- [ ] Rename notification channels (e.g., "Zungvi Messages", "Zungvi Calls").
- [ ] Sync localization string files in `src/main/res/values-*/strings.xml`.

---

## 4. XMPP Infrastructure & Configuration
- [ ] Update configuration constants in `src/main/java/eu/siacs/conversations/Config.java`:
  - `APP_NAME = "Zungvi Messenger"`
  - `MAGIC_CREATE_DOMAIN = "zungvi.com"`
  - `BUG_REPORTS = Jid.of("bugs@zungvi.com")`
  - `HELP = Uri.parse("https://zungvi.com/help")`
- [ ] Customize onboarding flows (`WelcomeActivity`, `PickServerActivity`, `MagicCreateActivity`).
- [ ] Update UnifiedPush distributor settings in `UnifiedPushDistributor.java`.

---

## 5. Codebase Refactoring
- [ ] Refactor package structure from `eu.siacs.conversations` to `com.zungvi.messenger`.
- [ ] Update imports, data-binding XML layouts, and `proguard-rules.pro`.
- [ ] Remove unused legacy branding/SMS REST code.

---

## 6. UI/UX & Theme Styling
- [ ] Update primary/accent colors in `src/main/res/values/colors-md.xml` and `src/main/res/values/themes.xml`.
- [ ] Apply custom light and dark themes (`Theme.Zungvi`).

---

## 7. Legal, Store Metadata & Documentation
- [ ] Update Privacy Policy URL (`https://zungvi.com/privacy`) in `build.gradle` and `Config.java`.
- [ ] Update Terms of Service (`activity_tos.xml`).
- [ ] Update Fastlane store metadata in `fastlane/metadata/android/` (`title.txt`, `full_description.txt`, `short_description.txt`).
- [ ] Update project `README.md`, `CHANGELOG.md`, and `conversations.doap`.

---

## 8. Build Verification & Testing
- [ ] Execute `./gradlew assembleFreeDebug` and `./gradlew assemblePlaystoreDebug` to verify compilation.
- [ ] Test standard XMPP capabilities: Auth, 1:1 & Group Chat, OMEMO (XEP-0384), HTTP File Upload (XEP-0363), Jingle Calls, and Push Notifications.
