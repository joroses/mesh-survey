# `:feature:survey`

Signal survey, a fork-only feature (not in upstream Meshtastic-Android). While a survey runs, the phone records every LoRa packet its connected node hears, with where the phone was at that moment, in its own database. It uploads the records to SigSleuth (`POST /api/v1/coverage/uploads`) and can export a session as CSV. It lives in Settings → Advanced → Signal survey. Android only: it needs the phone's GPS.

## How it works

- **Hook.** `MeshMessageProcessorImpl.handleReceivedMeshPacket` hands every raw packet to `SurveyPacketTap.Shared` (core:repository; Koin binds the interface to `SurveyPacketTapImpl` in core:data, which forwards to it) before decoding, so packets the node could not decrypt are recorded too. Upstream's Discovery hook comes after decoding, which drops them (30% of the first version's rows). The listener only builds the row and `trySend`s it to a bounded channel; one coroutine does the inserts.
- **What is recorded** (`measurementOf`): LoRa packets only (on firmware before 2.7.5, which marks every packet internal, those with an RSSI), not relayed by MQTT, not from the survey node itself or node 0. A packet whose `rx_time` trails the phone's clock by more than 60 s is a replay from the node's history and is dropped. Each row gets the newest GPS fix and its age; mock fixes count as none. Missing SNR, RSSI, position or relay byte is null; SNR 0 is a real reading.
- **Sessions** (`SurveyRecorder`) record the radio settings in use, so SigSleuth can tell networks apart and measure each SNR from its spreading factor's floor: the preset name, spreading factor, bandwidth in kHz (62.5, not the config's code 62) and the frequency the app's LoRa settings show (the primary channel's `radioFreq`). They end on Stop, when the survey node or its LoRa settings change (Local Mesh Discovery changes the preset while it scans; renaming the primary channel or changing the frequency offset moves the frequency too), when the radio stays away for 30 minutes, or when the service dies (the next start marks them interrupted). While the radio is away GPS is off, so a forgotten survey does not drain the battery, and nothing is recorded: after a reconnect, capture resumes only once the handshake has finished and the node and settings still match, so packets heard during the handshake never land in a session whose settings they may not share.
- **Service** (`SurveyService`): a foreground service of type `location`, because MeshService does not always hold that type. Its notification has a Stop action and its own channel.
- **GPS** (`SurveyLocation`): its own 1 s GPS request, because the app's location source asks every 30 s and drops each fix's age.
- **Storage**: `signal_survey.db` (Room, version 2; 1→2 is an auto-migration adding the server counts), apart from the per-device app database. Sessions are deleted by hand.
- **Upload** (`SurveyUploader`): batches of 500 rows after each session's `uploaded_seq`, oldest session first; the server's `last_seq` becomes the new `uploaded_seq`, and it ignores rows it already holds. It stops at the first failure and saves the error. The URL must be absolute (`https://host`): the app's shared HttpClient resolves anything else against api.meshtastic.org, so Save refuses it and the uploader sends nothing. Each session keeps what SigSleuth's replies say it kept and skipped, by reason (`no_position`, `too_old`, …), and its card shows them, since "Uploaded N of N" only means the server answered. A `hop_start` of 0 (firmware older than hop counts) is uploaded with both hop fields null, as SigSleuth's MQTT side stores it; the CSV keeps the raw values. It runs every minute while recording and once when a session ends (if "Upload automatically" is on), and from the Upload buttons. The URL, user and password live in this module's DataStore (`signal_survey_ds`); the password is sent as `Authorization: Basic`, never in the URL, whenever a user or a password is set (SigSleuth checks only the password: its `SIGSLEUTH_UPLOAD_PASSWORD`).
- **Cleartext HTTP** is blocked except to `localhost`, so a deployed SigSleuth needs HTTPS. Over USB, `adb reverse tcp:8001 tcp:8001` reaches a local instance as `http://localhost:8001`.

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

Everything else, including the service's manifest entry and all other strings (English only, prefix `survey_`), is in this module, so upstream's frequent `strings.xml` and manifest changes never conflict.

## Build and try

```bash
./gradlew :feature:survey:testDebugUnitTest
./gradlew :androidApp:installFdroidDebug
# The database uses WAL: copy all three files to read it elsewhere.
for f in signal_survey.db signal_survey.db-wal signal_survey.db-shm; do
  adb exec-out run-as com.geeksville.mesh.fdroid.debug cat databases/$f > $f
done
```

`APPLICATION_ID` (`com.geeksville.mesh.survey`) applies to release builds only: `androidApp/build.gradle.kts` sets every debug build to `com.geeksville.mesh.<flavor>.debug`, so the fork's debug build replaces an upstream debug build on the same phone. A `google` release also needs a `com.geeksville.mesh.survey` client in `google-services.json`.
