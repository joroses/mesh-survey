# `:feature:survey`

Signal survey, a fork-only feature (not in upstream Meshtastic-Android). While a survey runs, the phone records every LoRa packet its connected node hears, with where the phone was at that moment, in its own database. It uploads the records to SigSleuth (`POST /api/v1/coverage/uploads`) and can export a session as CSV. It lives in Settings → Advanced → Signal survey. Android only: it needs the phone's GPS.

## How it works

- **Hook.** `MeshMessageProcessorImpl.handleReceivedMeshPacket` hands every raw packet to `SurveyPacketTap` (core:repository, held by `SurveyPacketTapImpl` in core:data) before decoding, so packets the node could not decrypt are recorded too. Upstream's Discovery hook comes after decoding, which drops them (30% of the first version's rows). The listener only builds the row and `trySend`s it to a bounded channel; one coroutine does the inserts.
- **What is recorded** (`measurementOf`): LoRa packets only (on firmware before 2.7.5, which marks every packet internal, those with an RSSI), not relayed by MQTT, not from the survey node itself or node 0. A packet whose `rx_time` trails the phone's clock by more than 60 s is a replay from the node's history and is dropped. Each row gets the newest GPS fix and its age; mock fixes count as none. Missing SNR, RSSI, position or relay byte is null; SNR 0 is a real reading.
- **Sessions** (`SurveyRecorder`) record the radio settings in use, so SigSleuth can tell networks apart and measure each SNR from its spreading factor's floor: the preset name, spreading factor, bandwidth in kHz (62.5, not the config's code 62) and the frequency the app's LoRa settings show (the primary channel's `radioFreq`). They end on Stop, when the survey node or its LoRa settings change (Local Mesh Discovery changes the preset while it scans; renaming the primary channel or changing the frequency offset moves the frequency too), when the radio stays away for 30 minutes, or when the service dies (the next start marks them interrupted). While the radio is away GPS is off, so a forgotten survey does not drain the battery, and nothing is recorded: after a reconnect, capture resumes only once the handshake has finished and the node and settings still match, so packets heard during the handshake never land in a session whose settings they may not share.
- **Service** (`SurveyService`): a foreground service of type `location`, because MeshService does not always hold that type. Its notification has a Stop action and its own channel.
- **GPS** (`SurveyLocation`): its own 1 s GPS request, because the app's location source asks every 30 s and drops each fix's age.
- **Storage**: `signal_survey.db` (Room, version 2; 1→2 is an auto-migration adding the server counts), apart from the per-device app database. Sessions are deleted by hand.
- **Upload** (`SurveyUploader`): batches of 500 rows after each session's `uploaded_seq`, oldest session first; the server's `last_seq` becomes the new `uploaded_seq`, and it ignores rows it already holds. It stops at the first failure and saves the error. The URL must be absolute (`https://host`): the app's shared HttpClient resolves anything else against api.meshtastic.org, so Save refuses it and the uploader sends nothing. Each session keeps what SigSleuth's replies say it kept and skipped, by reason (`no_position`, `too_old`, …), and its card shows them, since "Uploaded N of N" only means the server answered. A `hop_start` of 0 (firmware older than hop counts) is uploaded with both hop fields null, as SigSleuth's MQTT side stores it; the CSV keeps the raw values. It runs every minute while recording and once when a session ends (if "Upload automatically" is on), and from the Upload buttons. The URL, user and password live in this module's DataStore (`signal_survey_ds`); the password is sent as `Authorization: Basic`, never in the URL, whenever a user or a password is set (SigSleuth checks only the password: its `SIGSLEUTH_UPLOAD_PASSWORD`).
- **Cleartext HTTP** is blocked except to `localhost`, so a deployed SigSleuth needs HTTPS. Over USB, `adb reverse tcp:8001 tcp:8001` reaches a local instance as `http://localhost:8001`.

## Relay guesses

A packet carries only the last byte of the node that relayed it (`relay_byte`), and the firmware sends a node number ending in `00` as `FF` (`getLastByteOfNodeNum` in `NodeDB.h`), so many nodes can share a byte. The recording card's "Last packet" line names the likely relay anyway, marked as a guess (`SurveyRelays.kt`):

| Situation | Relay text |
| :--- | :--- |
| The packet came straight from its sender | `ABCD (direct)` |
| One neighbour with that byte heard directly in the last hour | `3f ≈ ABCD` |
| None in the last hour, one in the last 30 days | `3f ≈ ABCD?` |
| Several in the best of those two tiers | `3f: ABCD or EFGH` (newest first, at most three) |
| None | `3f` |

- **Direct** means `hop_start == hop_limit` and the relay byte is the sender's own (`isDirect`).
- **Candidates** are only senders this survey radio has heard directly: in this session, and in earlier sessions with the same `receiver_node_id` from the last 30 days (`SurveyDao.directSenders`, loaded when a session starts). The sender itself is never a candidate, and a packet is never evidence for its own guess.
- **Names** come from the node database of the connected radio, which is the survey radio; a node missing from it shows as `!xxxxxxxx`.
- **Not stored or exported.** Guesses are derived: the database, the CSV and the SigSleuth upload keep only the raw fields, so SigSleuth can make its own.

Limits: a relay the survey radio has never heard directly is never named, a neighbour heard directly only from another spot may be out of range now, and two neighbours sharing a byte stay ambiguous. Upstream named relays by matching the whole node database and dropped it because it named stale or wrong nodes ([#4292](https://github.com/meshtastic/Meshtastic-Android/issues/4292), [design#33](https://github.com/meshtastic/design/issues/33), [#3773](https://github.com/meshtastic/Meshtastic-Android/pull/3773)); limiting candidates to direct neighbours follows the firmware's own relay attribution in [firmware#12090](https://github.com/meshtastic/firmware/pull/12090).

## CSV export compared with "Export all packets"

Settings' "Export all packets" (`ExportDataUseCase`; "Export rangetest packets" is the same export filtered to the range-test port) writes `date, time, from, sender name, sender lat, sender long, rx lat, rx long, rx elevation, rx snr, distance(m), hop limit, hop start, relay node, payload`. The survey's CSV (`SurveyCsv`, one session per file) writes `session_id, seq, heard_at_ms, latitude, longitude, accuracy_m, fix_age_ms, sender_node_id, recipient_node_id, packet_id, rx_snr, rx_rssi, hop_start, hop_limit, relay_byte`.

| | Export all packets | Survey CSV |
| :--- | :--- | :--- |
| Time | `date` and `time`, local, whole seconds | `heard_at_ms`: epoch milliseconds, the phone's clock when the packet arrived |
| Receiver position | `rx lat/long/elevation`: the connected node's last position in the log, which can be minutes old, of unknown accuracy | `latitude/longitude`: the phone's newest GPS fix (1 s request), with `accuracy_m` and `fix_age_ms`; no altitude; mock fixes left out |
| Sender | `sender name`, `sender lat/long`, `distance(m)` | `sender_node_id` only |
| Only in the survey | | `rx_rssi`, `recipient_node_id`, `packet_id`, `seq`, `session_id` |
| Payload | `payload`: text of text and range-test messages, `<PORTNUM>` or "N encrypted bytes" | none |
| Relay | `relay node` as a hex byte (`d4`) | `relay_byte` as a decimal number (0–255) |
| Hop order | `hop limit, hop start` | `hop_start, hop_limit` (raw; only the upload nulls them when `hop_start` is 0) |
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
| `core/repository/.../SurveyPacketTap.kt` | new: the hook's interface |
| `core/data/.../manager/SurveyPacketTapImpl.kt` | new: the hook's holder |
| `core/data/.../manager/MeshMessageProcessorImpl.kt` | one constructor parameter and one call |
| `core/data` tests constructing `MeshMessageProcessorImpl` | the new parameter, and one test of the hook |
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

What a survey records and where it goes, for users, and how to build and sign a release APK to share, are in the top-level [README](../../README.md#privacy).

```bash
./gradlew :feature:survey:testDebugUnitTest
./gradlew :androidApp:installFdroidDebug
# The database uses WAL: copy all three files to read it elsewhere.
for f in signal_survey.db signal_survey.db-wal signal_survey.db-shm; do
  adb exec-out run-as com.geeksville.mesh.fdroid.debug cat databases/$f > $f
done
```

`APPLICATION_ID` (`io.github.joroses.meshsurvey`) applies to release builds only: `androidApp/build.gradle.kts` sets every debug build to `com.geeksville.mesh.<flavor>.debug`, so the fork's debug build replaces an upstream debug build on the same phone. A `google` release also needs an `io.github.joroses.meshsurvey` client in `google-services.json`.
