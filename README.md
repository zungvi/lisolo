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
- Searchable recommendations from the XMPP Providers directory, plus custom-server signup; provider search stays on-device after the public list is downloaded
- Zungvi sign-in, which exchanges Zungvi credentials for XMPP ones
- Custom hostname and port
- A plain-language server and app check for OMEMO key publishing, message history, file uploads, and background notifications in supported builds

**Notifications**

- FCM in the `playstore` build, UnifiedPush in `free`
- Grace periods, per-conversation ringtones, separate handling for strangers

**Backup** — encrypted local backups with restore on a new device; see
[docs/user/backup.md](docs/user/backup.md)

**Local privacy** — device-credential app lock and its limits; see
[docs/user/local_privacy.md](docs/user/local_privacy.md)

**Crash reports** — after a crash the app offers to send the stack trace to
`bugs@xmpp.zungvi.net`, as a plaintext message from the user's own account. On by default,
declinable permanently.

## Not supported

- Group-call interoperability is not yet verified; the initial MUJI mesh is capped at five participants.
- Abuse reporting requires server support for direct XEP-0377 reports; group participant reporting is experimental and requires room support.
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
