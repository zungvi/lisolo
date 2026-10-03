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

---

## 9. Chat Experience: Parity Gaps vs Signal / WhatsApp

The chat screen is now **correct** — bubbles render, sent messages appear
immediately, the list opens on the newest message, the composer clears the
navigation bar and the keyboard. "Correct" is the floor Signal starts from, not
where it sits. What follows is features, not polish; each is real work.

### 9.1 Verify before building anything else
- [ ] **Long-press on a message.** Never exercised. `adb` cannot synthesise a
      long-press this view accepts, and `ContextMenuRecyclerView` is new code
      replacing the `AdapterContextMenuInfo` casts that RecyclerView would have
      thrown on. If broken, copy / quote / reply / delete are all gone.
- [ ] **Keyboard behaviour on real hardware.** The emulator has a hardware
      keyboard attached and never shows a soft one, so no IME insets are ever
      dispatched and that path has only ever been tested by proxy (rotation).
- [ ] **Account switcher.** Needs two accounts on one device; it stays hidden
      below two, so it has never rendered.

### 9.2 Missing features
> Corrected after a second pass: the first audit here used case-sensitive
> patterns and under-reported what already exists. Typing indicators and audio
> scrubbing were both wrongly listed as missing.

- [x] ~~Typing indicators.~~ **Already implemented.** `updateStatusMessages()`
      renders "X is typing…" as a status row for 1:1 chats from
      `ChatStateManager.getIncoming()`, and for group chats from the per-user
      chat state, including the plural "X and Y are typing…" form and the
      "stopped typing" variants.
- [ ] **Jump to quoted message.** Tapping a reply quote does nothing. Both apps
      scroll to the original and flash it.
- [ ] **Multi-select.** No action-mode selection. Signal lets you select several
      messages to forward or delete; here it is one-at-a-time via the context
      menu.
- [ ] **Link previews.** No Open Graph fetching. Every modern messenger has them.
- [ ] **Voice note waveforms.** Scrubbing *does* work — `item_message_content`
      has a `SeekBar` wired to `AudioPlayer.seekTo()`. What is missing is only
      the waveform rendering that Signal and WhatsApp draw behind it.

### 9.3 Known trade-off, deliberately taken
- [ ] **`areContentsTheSame` always returns `false`**, so every visible row
      rebinds on every update. Correctness was chosen over efficiency because
      `Message` is mutable and there was no cheap way to detect real changes.
      Signal does proper payload diffing. On long conversations on a mid-range
      phone this will show.

### 9.4 Suggested order
1. Long-press verification — a bug check, and the highest risk
2. Jump to quoted message — self-contained, no new infrastructure
3. Multi-select — the largest genuine gap in day-to-day use
4. Voice note waveforms — presentation only; playback and seeking already work
5. Link previews — weigh first: fetching Open Graph data means the client
   requests arbitrary third-party URLs, which leaks IP and metadata. That is a
   product decision for a privacy-focused messenger, not just an implementation
   task. Signal does it deliberately and carefully.

### 9.5 Confidence note
The scroll bug in this area was diagnosed wrong three times, and two of those
"fixes" made it worse. Each was caught only by testing on real hardware. Treat
anything marked verified as *verified on an emulator that cannot show a
keyboard*; a physical device is the real gate.
