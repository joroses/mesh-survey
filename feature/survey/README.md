# `:feature:survey`

Signal survey, a fork-only feature (not in upstream Meshtastic-Android). While a survey runs, the phone records every LoRa packet its connected node hears, with where the phone was at that moment, in its own database. It uploads the records to SigSleuth (`POST /api/v1/coverage/uploads`) and can export a session as CSV. It lives in Settings → Advanced → Signal Survey. Android only: it needs the phone's GPS.

## How it works

- **Hook.** `MeshMessageProcessorImpl.handleReceivedMeshPacket` hands every raw packet to `SurveyPacketTap.Shared` (core:repository; Koin binds the interface to `SurveyPacketTapImpl` in core:data, which forwards to it) before decoding, so packets the node could not decrypt are recorded too. Upstream's Discovery hook comes after decoding, which drops them (30% of the first version's rows). The listener only builds the row and `trySend`s it to a bounded channel; one coroutine does the inserts.
- **What is recorded** (`measurementOf`): LoRa packets only (on firmware before 2.7.5, which marks every packet internal, those with an RSSI), not relayed by MQTT, not from the survey node itself or node 0. A packet whose `rx_time` trails the phone's clock by more than 60 s is a replay from the node's history and is dropped. Each row gets the newest GPS fix and its age; mock fixes count as none. Missing SNR, RSSI, position or relay byte is null; SNR 0 is a real reading. A packet the survey node decoded also records the sender's OK to MQTT bit (`Data.bitfield` bit 0; a missing bitfield is not OK, as the firmware reads it) and whether its channel's key is the default `AQ==`, the only one SigSleuth decrypts (`defaultKeyChannels`); both are null for a packet it could not decode.
- **Sessions** (`SurveyRecorder`) record the radio settings in use, so SigSleuth can tell networks apart and measure each SNR from its spreading factor's floor: the preset name, spreading factor, bandwidth in kHz (62.5, not the config's code 62) and the frequency the app's LoRa settings show (the primary channel's `radioFreq`). They end on Stop, when the survey node or its LoRa settings change (Local Mesh Discovery changes the preset while it scans; renaming the primary channel or changing the frequency offset moves the frequency too), when the radio stays away for 30 minutes (timed on a clock that runs while the phone sleeps, so a radio back after a longer sleep still ends it; a disconnect by hand counts as away, so reconnecting the same radio resumes the session), or when the service dies (the next start marks them interrupted). While the radio is away GPS is off, so a forgotten survey does not drain the battery, and nothing is recorded: after a reconnect, capture resumes only once the handshake has finished and the node and settings still match, so packets heard during the handshake never land in a session whose settings they may not share.
- **Service** (`SurveyService`): a foreground service of type `location`, because MeshService does not always hold that type. Its notification has a Stop action and its own channel.
- **GPS** (`SurveyLocation`): its own 1 s GPS request, because the app's location source asks every 30 s and drops each fix's age.
- **Storage**: `signal_survey.db` (Room, version 3; auto-migrations: 1→2 adds the server counts, 2→3 each measurement's OK to MQTT bit and default key; `SurveyDatabaseMigrationTest` checks old rows survive), apart from the per-device app database. Sessions are deleted by hand. The session list follows the session table only, so a measurement does not recount every stored row; the recording session's count comes from the recorder.
- **Upload** (`SurveyUploader`): only sessions the user lets go (`survey_session.upload`) are ever sent. A session gets the flag when it starts with "Upload automatically" on, or when the user taps Upload on it (the upload card's "Upload now" for the recording session, a session card's Upload for an ended one). Turning "Upload automatically" off while recording clears the recording session's flag, so the rest of it stays on the phone; the uploader checks the flag before every batch, so a run already sending stops at the next one. Sessions from before database version 4 start private. Batches of 500 rows after each session's `uploaded_seq`, oldest session first; the server's `last_seq` becomes the new `uploaded_seq`, and it ignores rows it already holds. It stops at the first failure and saves the error. The URL must be absolute (`https://host`): the app's shared HttpClient resolves anything else against api.meshtastic.org, so Save refuses it and the uploader sends nothing. Plain `http://` passes only for `localhost` and `127.0.0.1`, the only hosts cleartext may reach (below). Each session keeps what SigSleuth's replies say it kept and skipped, by reason (`no_position`, `too_old`, …), and its card shows them, since "Uploaded N of N" only means the server answered. That progress and those counts belong to one server, so saving a different URL resets them for every session (not the upload flags) and the new server gets every allowed session's rows (otherwise a switch from a local test instance to the real one would send nothing). Hop counts are uploaded raw, as in the CSV: a `hop_start` of 0 is either firmware older than hop counts or a zero-hop packet such as Range Test's, and SigSleuth tells them apart (`known_hop_start`: 0/0 with the sender's own relay byte is direct, any other 0 is unknown). It runs every minute while recording and once when a session ends (if "Upload automatically" is on), and from the Upload buttons. Surveys go where no gateway listens, often with no mobile data either, so when a run fails with "Upload automatically" on, `SurveyUploadWorker` (WorkManager unique work `signal_survey_upload`, KEEP) runs it again once the phone has a network connection, backing off exponentially from one minute while it keeps failing; it survives the app closing and a reboot. An invalid URL is not retried. The URL, user and password live in this module's DataStore (`signal_survey_ds`); the password is sent as `Authorization: Basic`, never in the URL, whenever a user or a password is set (SigSleuth checks only the password: its `SIGSLEUTH_UPLOAD_PASSWORD`).
- **Screen** (`SurveyScreen`): the server settings fold to one line once saved. The recording card shows the GPS line in the error colour while SigSleuth would skip the packets heard now (a fix older than 10 s or worse than 100 m: its `MAX_FIX_AGE_MS` and `MAX_ACCURACY_M`), and the session cards word its skip reasons with the same limits, so change both with the server. CSV export and delete report through the app's snackbar.
- **Cleartext HTTP** is blocked except to `localhost`, so a deployed SigSleuth needs HTTPS. Over USB, `adb reverse tcp:8001 tcp:8001` reaches a local instance as `http://localhost:8001`.

## Relay guesses

A packet carries only the last byte of the node that relayed it (`relay_byte`), and the firmware sends a node number ending in `00` as `FF` (`getLastByteOfNodeNum` in `NodeDB.h`), so many nodes can share a byte. The recording card's "Relay" line names the likely relay anyway, marked as a guess (`SurveyRelays.kt`):

| Situation | Relay text |
| :--- | :--- |
| The packet came straight from its sender | `ABCD (direct)` |
| One neighbour with that byte heard directly within 1 km of the packet's fix in the last 30 days | `3f ≈ ABCD` |
| None near that fix, and one candidate within 30 km: heard directly elsewhere in the last 30 days, or reporting a position | `3f ≈ ABCD?` |
| Several in the first nonempty tier (near, then elsewhere) | `3f: ABCD or EFGH` (distance, then node number; at most three) |
| None | `3f` |

- **Direct** means `hop_start == hop_limit` and the relay byte is the sender's own (`isDirect`).
- **Candidates** are mainly senders this survey radio has heard directly: in this session, and in earlier sessions from the last 30 days with the same `receiver_node_id`, spreading factor, bandwidth and frequency, as SigSleuth's survey links match, so a second network surveyed with the same radio adds no candidates (`SurveyDao.directPlaces`, loaded when a session starts). Direct receptions are grouped into 0.002° cells (`round(latitude * 500)`, `round(longitude * 500)`), keeping each cell's latest time and the node's last direct time, including fixless receptions. The sender itself is never a candidate, and a packet is never evidence for its own guess. Nodes whose role in the survey radio's node database is `CLIENT_MUTE` are excluded because they never rebroadcast; an unknown role keeps the candidate.
- **Reported positions.** A packet with a fix also gets, as candidates, the nodes in the survey radio's node database with that byte and a position, last heard in the last 30 days (`reportedWith`); the survey radio itself is left out. They only ever join the possible tier: a position is the node's claim, possibly stale, not a reception. A node already a candidate by its direct receptions is listed once, by those.
- **Far.** Every candidate must be within 30 km of the packet's fix (`FAR`): heard directly within 30 km of it, or reporting a position that close. A neighbour heard only 200 km away on a road trip is dropped, though if it reports a nearby position it comes back by that. A candidate without cells has no distance and stays.
- **Near** means within 1 km of the closest cell centre heard in those 30 days, using the packet's own GPS fix whatever its age. A candidate without cells counts as near only if heard directly in the last hour. One near candidate is likely; several stay ambiguous, ordered by distance then node number. With none near, the neighbours heard elsewhere and the reported positions are merged by distance, with those without cells last, and are possible or ambiguous. Sorting the two kinds together is rough, since one distance is to where the phone heard the node and the other to where the node says it is. Without a packet fix, guesses keep the time tiers: last hour first, then the rest of the 30-day history, newest first, and reported positions are not used.
- **Too strong.** A relay sends its copy at its own power over its own path, so the copy cannot arrive much stronger than that node's own packets heard from the same place. A packet's signal is `rx_rssi + min(rx_snr, 0)` (below the noise floor RSSI stops falling and the negative SNR carries the rest; RSSI 0 is unset). A neighbour is ruled out for a packet when the packet's signal is more than 30 dB (`TOO_STRONG_DB`) above the strongest signal of its own direct receptions in cells within 1 km of the packet's fix in the last 30 days; it then leaves every tier, reported positions included. The check is one-sided because arriving weaker is normal (pocket, indoors, fading). On SigSleuth's loranet.pl gateways (2026-09-27 to 10-12, 279k relayed traceroute receptions with a known last relay) a 30 dB margin wrongly ruled out 0.035% of true relays and removed 15% of wrong directly heard candidates; gateways don't move, so the margin is wide until phone data calibrates it. Packets without a fix or RSSI, candidates heard only farther away, and reported positions are not checked.
- **The relay list.** Tapping the recording card's "Relay bytes" count lists this session's relay bytes, most packets first: each byte's guess, packet count and when its last packet arrived, and every candidate of that byte's last packet, not only the guessed tier: the guessed tier first, then the rest by distance, so a likely guess still shows the other nodes it could have been. Each line says how the node is known, under a one-line legend; nodes that packet ruled out follow, marked ✕ with their strongest signal here and the packet's. 📡 (heard directly) gives the distance from the phone's current fix to the node's nearest direct reception in the last 30 days and that reception's time, or without a fix the last direct time; that distance runs from where this phone heard the node, not from the node, and a later direct reception elsewhere is not shown. 📍 (reports a position) gives the distance from the phone's current fix to where the node says it is. Reception places rank the guesses, so a router heard along a route can remain a candidate even far from the router's own position.
- **Names** come from the node database of the connected radio, which is the survey radio; a node missing from it shows as `!xxxxxxxx`.
- **Not stored or exported.** Guesses are derived: the database, the CSV and the SigSleuth upload keep only the raw fields, so SigSleuth can make its own.

Limits: the signal only rules candidates out, never in, and a node never heard directly within 1 km of the packet isn't checked, so a far node heard weakly only elsewhere can still be guessed. A relay the survey radio has never heard directly is named only as possible, and only if it reports a position within 30 km; two neighbours sharing a byte and reception area stay ambiguous regardless of which spoke last. A mobile node heard just once at a place can remain a candidate there for 30 days; the guess deliberately errs towards ambiguity. Earlier receptions count immediately when returning to a place, and expire after 30 days. Upstream named relays by matching the whole node database and dropped it because it named stale or wrong nodes ([#4292](https://github.com/meshtastic/Meshtastic-Android/issues/4292), [design#33](https://github.com/meshtastic/design/issues/33), [#3773](https://github.com/meshtastic/Meshtastic-Android/pull/3773)), which is why the node database here only adds possible candidates, within 30 km and heard in the last 30 days; relying on direct neighbours follows the firmware's own relay attribution in [firmware#12090](https://github.com/meshtastic/firmware/pull/12090).

## CSV export compared with "Export all packets"

Settings' "Export all packets" (`ExportDataUseCase`; "Export rangetest packets" is the same export filtered to the range-test port) writes `date, time, from, sender name, sender lat, sender long, rx lat, rx long, rx elevation, rx snr, distance(m), hop limit, hop start, relay node, payload`. The survey's CSV (`SurveyCsv`, one session per file) writes `session_id, seq, heard_at_ms, latitude, longitude, accuracy_m, fix_age_ms, sender_node_id, recipient_node_id, packet_id, rx_snr, rx_rssi, hop_start, hop_limit, relay_byte, ok_to_mqtt, default_key`.

| | Export all packets | Survey CSV |
| :--- | :--- | :--- |
| Time | `date` and `time`, local, whole seconds | `heard_at_ms`: epoch milliseconds, the phone's clock when the packet arrived |
| Receiver position | `rx lat/long/elevation`: the connected node's last position in the log, which can be minutes old, of unknown accuracy | `latitude/longitude`: the phone's newest GPS fix (1 s request), with `accuracy_m` and `fix_age_ms`; no altitude; mock fixes left out |
| Sender | `sender name`, `sender lat/long`, `distance(m)` | `sender_node_id` only |
| Only in the survey | | `rx_rssi`, `recipient_node_id`, `packet_id`, `seq`, `session_id` |
| Payload | `payload`: text of text and range-test messages, `<PORTNUM>` or "N encrypted bytes" | none |
| Relay | `relay node` as a hex byte (`d4`) | `relay_byte` as a decimal number (0–255) |
| Hop order | `hop limit, hop start` | `hop_start, hop_limit` (raw, as uploaded) |
| SNR | rows without one are dropped | rows kept with an empty `rx_snr` |
| Which rows | everything in the app's mesh log, from any time, decoded packets only, no transport filter; replays get the time the app received them | packets heard live over LoRa while recording: no MQTT, not the survey node, not node 0, no replays more than 60 s late; includes packets the node could not decrypt |
| Radio settings | none | not in the CSV either: preset, spreading factor, bandwidth and frequency are in `survey_session`, linked by `session_id` |
| Format | every field quoted; only quotes in the payload escaped | unquoted, numbers only; a missing value is an empty cell |

Both write the sender as an unsigned decimal node number and use `snrOrNull()`, so a 0 dB SNR is a real reading in both.

## Upstream files the fork touches

Keep these edits small; they are what a rebase onto upstream has to carry.

| File | Change |
| :--- | :--- |
| `settings.gradle.kts` | `":feature:survey"` |
| `build-logic/convention/src/main/kotlin/RootConventionPlugin.kt` | the module in `ALL_MODULES_FULL` and `ANDROID_ONLY_MODULES` |
| `androidApp/build.gradle.kts` | `implementation(projects.feature.survey)` |
| `androidApp/.../di/AppKoinModule.kt` | `FeatureSurveyModule::class` |
| `androidApp/.../ui/Main.kt` | `surveyGraph(backStack)` |
| `core/navigation/.../Routes.kt`, `NavigationConfig.kt` | `SurveyRoute` and its serializer |
| `core/repository/.../SurveyPacketTap.kt` | new: the hook's interface and its shared instance |
| `core/data/.../manager/SurveyPacketTapImpl.kt` | new: Koin's binding, forwarding to the shared instance |
| `core/data/.../manager/MeshMessageProcessorImpl.kt` | one import and one call, no constructor parameter |
| `core/data/.../manager/MeshMessageProcessorImplTest.kt` | one test of the hook |
| `feature/settings/.../radio/RadioConfig.kt` | the Settings entry, behind `showSignalSurvey` |
| `feature/settings/src/androidMain/.../SettingsScreen.kt` | `showSignalSurvey = true` |
| `.github/workflows/reusable-check.yml` | the module's tests and coverage in the `shard-app` test shard |
| `core/resources/.../values/strings.xml`, `.skills/compose-ui/strings-index.txt` | `signal_survey` (the entry's title) |

The fork's own name and identity, kept out of the Meshtastic trademark (see `CONTRIBUTING.md` → Forks and rebrands):

| File | Change |
| :--- | :--- |
| `README.md` | the fork notice at the top, in place of upstream's logo and badges |
| `config.properties` | `APPLICATION_ID=io.github.joroses.meshsurvey`; `VERSION_NAME_BASE` gets a `-survey.N` suffix |
| `androidApp/src/main/res/values/strings.xml` | `app_name` and `nfc_tag_emulation_service` say Mesh Survey |
| `androidApp/src/main/res/drawable-anydpi/ic_launcher_*.xml`, `ic_splash.xml` | placeholder signal icon on blue |
| `core/resources/src/androidMain/res/drawable/meshtastic_ic_notification.xml`, `core/resources/.../drawable/ic_meshtastic.xml`, `feature/widget/.../drawable/widget_app_icon.xml` | the same placeholder icon (notification, top bar, widget) |
| `core/service/.../MeshNotificationManagerImpl.kt`, `feature/widget/.../LocalStatsWidget.kt` | the app's label in place of `meshtastic_app_name` |
| `feature/settings/.../AboutScreen.kt` | `GITHUB_REPO_URL` points at this fork; `copyright_notice` stays |

Everything else, including the service's manifest entry and all other strings (English only, prefix `survey_`), is in this module, so upstream's frequent `strings.xml` and manifest changes never conflict.

## Build and try

See the top-level README for [what a survey records and where it goes](../../README.md#privacy) and [how to install a release APK](../../README.md#install).

```bash
./gradlew :feature:survey:testDebugUnitTest
./gradlew :androidApp:installFdroidDebug
# The database uses WAL: copy all three files to read it elsewhere.
for f in signal_survey.db signal_survey.db-wal signal_survey.db-shm; do
  adb exec-out run-as com.geeksville.mesh.fdroid.debug cat databases/$f > $f
done
```

`APPLICATION_ID` (`io.github.joroses.meshsurvey`) applies to release builds only: `androidApp/build.gradle.kts` sets every debug build to `com.geeksville.mesh.<flavor>.debug`, so the fork's debug build replaces an upstream debug build on the same phone. A `google` release also needs an `io.github.joroses.meshsurvey` client in `google-services.json`.
