# Local privacy and app lock

Lisolo's optional app lock is in **Settings → Security → On this device**. When enabled, Lisolo asks Android to confirm the device screen lock whenever the app returns from the background. Android may offer an enrolled biometric as part of that system prompt. The app does not store its own PIN, biometric template, or unlock key.

While the app lock is enabled, Lisolo also blocks screenshots and hides the app's task preview. If the device no longer has a secure screen lock, Lisolo turns off the app lock so you are not left unable to open the app. Enable it again after setting a device lock.

## What it protects

The app lock adds a gate before someone can reopen Lisolo on a phone that was left unlocked. It keeps chat screens out of Android's app-switcher preview and prevents screenshots while enabled. The app lock state lasts only in memory; after Lisolo's process is recreated, Android authentication is required again.

## What it does not protect

The app lock is an interface gate. It does **not** encrypt Lisolo's SQLite database, message content, encryption keys, or media files. The database is in Android's app-private storage, but this build stores attachments in shared external storage. A rooted or compromised device, access to those external files, privileged access to the app's files, or access while Lisolo is unlocked is outside the protection provided by this feature. Notification content shown on the device lock screen is controlled separately by Android's notification settings.

The app lock does not erase messages, remove server archives, or prevent recipients from keeping copies. It also does not turn off backups.

## Backups and migration

Lisolo's manual account backup is encrypted separately using the account password. It retains its existing format and is not changed by app lock. The app-lock preference is an Android app setting and follows the system's app-data backup and device-transfer rules; the in-memory unlocked state is never copied. On a restored device without a secure screen lock, Lisolo disables the app lock.

App lock does not change the database schema, account logout, or local account-data deletion behavior, so it requires no database migration. Database encryption is not currently implemented.
