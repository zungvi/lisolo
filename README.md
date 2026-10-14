# Lisolo Android client

An XMPP chat client for Android, published as `com.zungvi.messenger`. Works with any compliant
XMPP server.

Based on [Snikket](https://snikket.org/) and [Conversations](https://conversations.im/) by
Daniel Gultsch.

## Features

**Messaging**

- One-to-one and group chats (MUC), with bookmarks and pinning
- Server-side history (MAM), paginated on scrollback
- Replies, with tap-to-jump to the quoted message
- Emoji reactions
- Read/unread toggle and a local unread marker
- Typing notifications and delivery receipts
- Contact blocking

**Media and files**

- Images, video, files and voice messages, over HTTP upload (XEP-0363) or Jingle
- Per-conversation control over whether media appears in the device gallery
- Configurable image compression and video quality

**Calls**

- One-to-one voice and video over Jingle, routed through the system telecom stack

**Encryption**

- OMEMO, with Blind Trust Before Verification
- OpenPGP, via an external keychain app
- TLS, with per-server certificate decisions remembered

**AI features** — all off by default; when enabled, the relevant text is sent to `ai.zungvi.net`
in plaintext and is readable there

| Feature | Does |
| --- | --- |
| Translate messages | Renders a message in the reader's language |
| Catch up on groups | Summarises what you missed in busy groups |
| Warn about likely scams | Checks a first message from a non-contact for spam detection |
| Suggest replies | Offers short answers to last incomming messages above the message box |

**Accounts**

- Any XMPP account, or in-band registration
- Zungvi sign-in, which exchanges Zungvi credentials for XMPP ones
- Custom hostname and port

**Notifications**

- FCM in the `playstore` build, UnifiedPush in `free`
- Grace periods, per-conversation ringtones, separate handling for strangers

**Backup** — encrypted local backups with restore on a new device; see
[docs/user/backup.md](docs/user/backup.md)

**Crash reports** — after a crash the app offers to send the stack trace to
`bugs@xmpp.zungvi.net`, as a plaintext message from the user's own account. On by default,
declinable permanently.

## Not supported

- Group calls (no XEP-0272 / Muji)
- In-app abuse reporting (no XEP-0377); blocking only
- On-device translation — everything goes through the server
- Swahili translation is unreliable; the server model misreads tense and clock times

## Building

Two flavour dimensions: `mode` (`conversations`, `quicksy`) × `distribution` (`playstore`,
`free`). The Play artifact is `conversationsPlaystoreRelease`.

`minSdk` 24, `targetSdk` 36, Java 21. The `playstore` build needs a `google-services.json` for
FCM, which is not committed.

See [docs/BUILDING.md](docs/BUILDING.md).

## License

Copyright (c) 2014-2025 Daniel Gultsch and Snikket Community Interest Company.

GPL version 3. Modifications here are GPLv3 too, and distributing a build obliges you to offer
its corresponding source.

Snikket and the Snikket logo are trademarks of Snikket Community Interest Company.

Upstream: https://codeberg.org/iNPUTmice/Conversations
