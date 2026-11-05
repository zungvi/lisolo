# Product TODO

## 1. Make XMPP easier for newcomers

- [x] Use the XMPP Providers project's curated A/B directory and show registration route, compliance score, file limit, history retention, locations, and whether the service is free.
- [x] Add a searchable provider picker with paths for in-app signup, website signup, existing accounts, and a custom XMPP domain.
- [x] After sign-in, inspect server and account capabilities and show a simple status for OMEMO key publishing, history, uploads, and background notifications.
- [x] Surface the feature check in plain language on the connected account screen while keeping the XEP-level diagnostics available separately.
- [x] Explain that call availability also depends on the other person's app and the network.
- [x] Explain in the picker that OMEMO, history, upload, and push support are checked after sign-in, and that calls also depend on the other person's client and network.
- [x] Use cached provider data when offline, show the lookup error when no cached list exists, and keep custom-domain setup available in either case.
- [x] Explain that the public provider list is downloaded and search filtering happens on-device; never send the search query to the directory.

## 2. Improve group conversations

### Group calls

- [x] Assess XEP-0272 (MUJI) support and compatibility across active XMPP servers and clients; record interoperability requirements before implementation.
- [x] Design group call flows for starting, joining, leaving, reconnecting, and handling participant or permission changes.
- [x] Implement a per-room coordinator that owns peer sessions, injects shared WebRTC resources, and aggregates participant state.
- [x] Add reference-counted shared WebRTC resources and an injection path for peer sessions, while one-to-one calls keep private resources.
- [x] Give the coordinator one CallIntegration/audio route, suppress per-peer Telecom state updates, and preserve one-to-one call behavior.
- [x] Expose the aggregate as one call through the service, activity, conversation menu, notifications, and Telecom controls while preserving one-to-one call flows.
- [x] Add typed MUJI stanza support and retain each MUC participant's current/preparing MUJI state.
- [x] Refuse MUJI preparation in rooms that are not members-only and non-anonymous because peer Jingle sessions use real JIDs.
- [x] Send MUJI preparing presence and wait for the reflected self presence and other in-room participants to finish preparing, with timeout and MUC-loss failure handling.
- [x] Parse and build typed MUJI RTP content descriptions; convert existing RTP content maps into MUJI presence announcements and inspect active participants' advertised maps for payload ID, codec, clock-rate, channel, and parameter consistency.
- [x] Provide one room API that completes preparation, filters local media to shared payload IDs, and publishes final MUJI media only when compatible.
- [x] Use the agreed payload map in the room call coordinator and enforce it in each peer Jingle/WebRTC session.
- [x] Bound the first MUJI release to five participants and disclose the limit before call start; mesh call load grows with every participant pair.
- [x] Keep server-hosted SFU calls as a separate integration: Movim Galener is an XEP-0114 component that requires a deployed Galener/Galène service, not an XEP-0272-only server setting.
- [x] Implement group voice and video call start/join flows with room privacy, participant, and shared-codec checks; leave unsupported peers in text chat and explain setup failures.
- [ ] Verify interoperability with a documented server/client matrix, including call setup, reconnect, and media permission cases.

#### Group-call assessment and design (2026-11-04)

- **Protocol maturity:** [XEP-0272 (MUJI)](https://xmpp.org/extensions/xep-0272.html) is version 0.2.0 and Experimental. It coordinates multiparty calls inside MUC; it is not a stable, broadly deployed baseline. [XEP-0482 (Call Invites)](https://xmpp.org/extensions/xep-0482.html) is also Experimental and is an optional invitation path, not a prerequisite for every MUJI call flow.
- **How the call works:** participants publish audio/video content and a shared room-wide payload-type mapping in room presence, synchronize a preparing/joining phase, then establish one Jingle RTP session with each other participant. Payload IDs and codec parameters must be consistent across the room; independent per-peer SDP negotiation cannot be assumed to satisfy this. Leaving requires removing MUJI presence before closing that participant’s Jingle sessions. This means Lisolo needs a room-level call coordinator that owns multiple peer sessions; the existing call UI and manager model a single peer, and `JingleManager` currently rejects another RTP session while one is active.
- **Server requirements:** no MUJI-specific server module is defined. Servers must provide working XEP-0045 MUC presence reflection and ordinary XMPP routing for the pairwise Jingle signaling. Media still needs working ICE connectivity; test direct UDP and TURN-assisted paths. Treat MUC capability as necessary, but do not label a server “MUJI-compatible” based on server discovery alone because MUJI support is a client capability.
- **Privacy constraint:** MUJI’s Jingle sessions address participants by real JID, while a semi-anonymous MUC intentionally hides real JIDs from ordinary occupants. Never silently change an existing room’s anonymity. Before starting, disclose any real-JID exposure; prefer a dedicated private call room with an explicit consent flow, and gracefully decline setup when compatible routing cannot be provided.
- **Client landscape:** Movim 0.30 (released April 2025) reported interoperation with Dino using MUJI and Call Invites. [Movim 0.34 (June 2026) redesigned its flow, removed XEP-0482, and explicitly notes that the change breaks compatibility with Dino](https://mov.im/community/pubsub.movim.eu/Movim). [Movim 0.35 (September 2026) introduced Galener](https://mov.im/community/pubsub.movim.eu/Movim/456123c0-78eb-4353-9560-79aba7eb4681), a server-side SFU exposed through a dedicated XEP-0114 component; this is a different call topology and requires server deployment. An active [Monocles/DinoX Android branch](https://git.dinox.im/dinoxim/monocles-chat) also advertises MUJI, but its project notes say some physical-device call testing remains pending. Lisolo now has an initial XEP-0272 mesh start/join flow, room privacy checks, shared codec negotiation, and a five-person cap. Interoperability remains to be verified against exact client versions and call-flow profiles; one-to-one Jingle support alone does not mean group-call support.
- **First implementation scope:** target the XEP-0272 mesh profile in small rooms, with XEP-0482 invitations only where that profile is supported. Do not imply compatibility with Movim's room-native profile or Galener unless tested. The room call must own its peer Jingle sessions as one user-facing call; it cannot reuse the existing per-peer Telecom call surface unchanged.
- **Implementation constraint:** each `JingleRtpConnection` still owns a `WebRTCWrapper`, while the MUJI coordinator supplies one `CallIntegration` and audio route to every peer session. The coordinator owns shared WebRTC resources, peer-session teardown, and participant-state aggregation; the first UI implementation exposes it as one call through the service, activity, conversation menu, notifications, and Telecom controls.
- **Start and join:** initiate or join from a group chat; confirm the room and real-JID privacy model; request microphone/camera permissions and offer audio-only when camera access is denied. A caller can wait in the room for compatible members; participants publish their media profile before pairwise sessions start. Keep unsupported participants in text chat and explain setup failures. Implement one documented MUJI profile first, and keep profile-specific room setup and invitations explicit.
- **Join and change media:** use an explicit call invitation where XEP-0482 is supported; where a tested profile negotiates in the existing room, use its documented join action. Send the MUJI preparing presence, wait for its reflection and other participants’ preparation, agree the shared media/codecs, then establish peer sessions. Adding or removing audio/video content must update MUJI presence and matching peer sessions; mute and camera state must be reflected consistently across the aggregate call.
- **Leave and membership changes:** update presence to leave before closing peer sessions. On kick, ban, or removal from the call room, stop that peer’s media and tear down its session. Keep the call active for remaining participants; show failed or unsupported participants without dropping the whole call.
- **Reconnect and end:** on network loss, mark the affected peer as reconnecting, remove stale Jingle state, rejoin the room and negotiate again. Support late joiners through the same preparation and codec agreement. Ending the call removes MUJI presence, closes all peer sessions, releases capture/audio resources, and clears the call room when permitted.
- **Interoperability targets:**

  | Client / server path | Assessment or test role |
  | --- | --- |
  | Lisolo Android ↔ Dino desktop | Cross-platform MUJI target; pin Dino version and call profile, test setup, audio/video, late join, leave |
  | Lisolo Android ↔ Movim 0.34 web | Separate room-native MUJI profile; test independently and document the known profile difference from Dino |
  | Lisolo Android ↔ Movim 0.35 Galener | Separate SFU integration target; requires Galener service discovery/configuration and is outside the first MUJI mesh scope |
  | Movim 0.30 ↔ Dino | Historical positive reference reported by Movim; compare with 0.34 to catch profile divergence |
  | Lisolo Android ↔ Monocles/DinoX Android | Android peer implementation; pin exact build and verify physical-device behavior |
  | Lisolo ↔ client without MUJI | Negative control; hide or disable call join and preserve text chat |
  | Snikket/Prosody, ejabberd, Openfire MUC | Verify reflected MUJI presence, room membership events, peer Jingle routing, and anonymity behavior |
  | Wi-Fi, mobile data, restrictive NAT with TURN | Verify ICE negotiation, relay fallback, reconnect, and media permissions |

- **Acceptance cases:** cover host start, invitation, join, late join, participant leave, host end, kick/ban, camera or microphone denied, audio-only, network handoff, process recreation, no common codec, unavailable TURN, and anonymous-room privacy refusal. Start with small rooms; MUJI’s peer mesh adds a Jingle session for every participant pair, and the XEP calls for relays or mixers as group size grows.
- **Verification status:** pending. The current workstation has no Java runtime and no attached Android device or emulator, so the unit-test task and live server/client matrix could not run. Keep this unchecked until the required runtime and test devices are available.

### Message expiry, live location, and abuse reporting

- [x] Define message expiry behavior: carry a per-message timer using XEP-0466; start each device's countdown when that device first marks the message read; remove local content on expiry and leave a lightweight “Message expired” marker. This synchronizes the requested duration with compatible clients, while deletion remains best-effort and local to each device. Keep offline delivery enabled; do not claim that server MAM archives, recipient backups, screenshots, or exports are deleted.
- [x] Implement the defined expiry behavior, including timer controls, XEP-0466 parsing/sending and discovery, per-device read-start persistence, local content/attachment cleanup, and an expired marker that prevents MAM replay from restoring the content.
  - [x] Add the XEP-0466 stanza model and persist each message's timer, local read-start timestamp, and expired tombstone state.
  - [x] Add timer controls plus outgoing and incoming stanza handling, including encrypted messages.
  - [x] Start timers when messages are read, expire local text and attachments, and keep expired markers through archive replay.
- [ ] Verify message expiry across process restarts, MAM replay, attachment cleanup, and clients that support XEP-0466.
  - [x] Preserve pending attachment cleanup paths in expired rows and retry cleanup after process restart.
  - Runtime verification is still pending; this source-level review found and fixed the interrupted-cleanup case, but does not replace restart/device/client checks.
- [x] Implement live location sharing with explicit duration, visible sharing status, and a clear stop-sharing action; request background location only when needed.
  - [x] Offer 15-minute, 1-hour, and 8-hour sharing with an explanation of who can see updates and that the final location remains in chat.
  - [x] Send location updates as corrections to one geo message, use the conversation's encryption, and keep the selected expiry duration active through process recreation where Android permits.
  - [x] Show an ongoing notification with the recipient, end time, and Stop action; also expose Stop in the conversation menu and announce when sharing ends.
- [ ] Verify live location on Android 14+, including approximate-location permission, background updates, process recreation, expiry, notification Stop, and direct/group conversations with OMEMO.
- [x] Add in-app abuse reporting for direct chats and group chats, including an optional report note, a clear explanation of what is sent, and success or failure confirmation.
- [x] Send direct reports with XEP-0377 when both reporting and blocking are advertised; explain unsupported servers and offer blocking as a separate action.
- [x] Gate experimental group participant reports on the room's `urn:xmpp:gcreport:0` feature and an available occupant ID; disclose the experimental behavior and explain when no report can be sent.

## 3. Close everyday privacy gaps

- [x] Define the privacy goal as gating chat access when someone returns to an unattended, unlocked phone; choose a device-credential app lock for this release.
- [x] Use Android's existing secure screen lock for authentication. Lisolo stores no additional PIN or encryption key; if the device no longer has a secure lock, disable app lock rather than strand the user.
- [x] Defer SQLCipher database encryption. The database remains in Android's app-private storage and this app lock does not encrypt the database or protect extracted app data.
- [x] Add an app lock that requests the device credential whenever Lisolo returns from the background and hides task previews and screenshots while enabled.
- [x] Keep the database schema and encrypted account backup format unchanged. The app-lock preference follows the existing Android settings backup rules; the unlocked session state is memory-only, and the app disables the lock if a restored device has no secure screen lock.
- [ ] Verify app-lock behavior across activity transitions, process recreation, rotation, lock-screen removal, and app-switcher previews.
- [x] Preserve the existing account logout and local-data deletion paths; no database migration is needed because database storage is unchanged.
- [x] Document what app lock protects and its limits, including the unencrypted database, media, notification previews, and data visible while the app is unlocked.
