# Signal survey (`:feature:survey`)

Mesh Survey's own feature; upstream Meshtastic-Android doesn't have it. While a survey runs, the phone saves every LoRa packet its connected node hears, with the phone's GPS position at that moment. Sessions can be exported as CSV or uploaded to a [SigSleuth](https://github.com/joroses/sigsleuth) server, which turns them into a coverage map. It lives in Settings → Advanced → Signal Survey, also reached from a launcher shortcut (long-press the app icon, once the screen has been opened) and a Quick Settings tile that starts and stops a survey. Android only, since it needs the phone's GPS.

What a survey records, from a privacy point of view, and how to install the app: see the [top-level README](../../README.md#privacy).

## Recording

- **Which packets.** Every LoRa packet the node hears, including those it can't decrypt: the hook runs before the app decodes them. Left out: packets relayed over MQTT, packets from the survey node itself, and old packets the node replays on connect (more than 60 s late).
- **Each row** holds the time, the phone's newest GPS fix with its accuracy and age, SNR, RSSI, hop counts, relay byte, sender, recipient and packet ID. For a packet the node decoded, also the sender's OK to MQTT bit and whether its channel uses the default key, the only one SigSleuth can read.
- **GPS** has its own one-second request; the app's location updates come every 30 s. Mock locations are ignored.
- **A session** saves the radio settings (preset, spreading factor, bandwidth, frequency) so SigSleuth can tell networks apart. It ends on Stop, when the node or its LoRa settings change, or when the radio is away for 30 minutes; reconnecting sooner resumes it. While the radio is away, GPS is off and nothing is recorded.
- Sessions stay in their own database, `signal_survey.db`, until you delete them.

## Uploading

- Only sessions you allow are sent: those started with **Upload automatically** on, and those you upload by hand. Turning the switch off during a session keeps the rest of it on the phone.
- While recording, the app uploads every minute, in batches of 500 rows. A failed automatic upload is tried again once the phone is online, even after a reboot.
- The server address must start with `https://`; plain `http://` works only for `localhost`. The password goes in an HTTP Basic header.
- Each session card shows what SigSleuth kept and skipped (no position, fix too old, …). Saving a different server address resets these counts, and the new server gets every allowed session from the start.

## What a session adds

A session counts the map cells where it had a fix, the relay bytes it heard and the nodes it heard directly. **New to you** counts cells: those no earlier session on this phone, on the same network, covered, and those where a relay or a direct node was heard for the first time. **Added to the map** is the cells SigSleuth's replies call new on its map. The **Surveyed so far** card, under the Upload card, adds up the cells new to you, the nodes any session heard directly and the cells added to the map. Deleting a session passes its "new to you" on to later sessions that had the same things; what it added to the map still counts.

## Relay guesses

A packet carries only the last byte of the node that relayed it, so several nodes can share a byte. The recording card names the likely relay anyway:

| Situation | Shown as |
| :--- | :--- |
| Came straight from its sender | `ABCD (direct)` |
| SigSleuth's upload reply named the relay | `3f: ABCD` |
| One node with that byte heard directly within 1 km of here in the last 30 days | `3f: ABCD` |
| None that near, and one possible node within 30 km | `3f: ABCD` |
| Several near, or none near and several possible | `3f: ABCD or EFGH` (nearest first, at most three) |
| None | `3f` |

- **Heard directly.** The main evidence is where this radio heard each node directly: in this session, and in the last 30 days on the same network. A node heard within 1 km of the packet is a likely relay; up to 30 km, a possible one. `CLIENT_MUTE` nodes never relay and are left out.
- **Known positions.** Nodes whose position is known, from the radio's node database or SigSleuth's map, can only be *possible* relays: a position is a claim, not a reception. They must be within 30 km, or within the longest link SigSleuth has seen them make.
- **Too strong.** A relay's copy can't arrive much stronger than its own packets heard from the same place. A node is ruled out when the packet is more than 30 dB stronger than its strongest direct signal within 1 km.
- **SigSleuth's map.** With a server set, a recording session downloads the server's list of nodes that could relay on its radio settings, so guesses work offline. It fetches the list at most once a day, retrying every 10 minutes while offline, and sends only the radio settings and the password, never the phone's position.
- **SigSleuth's answers.** Each upload reply names the node each relayed packet came through, from every phone's uploads and SigSleuth's MQTT gateways. When a reply names the relay, the card and the relay list show that name in place of the guess; the list still shows every candidate under it.

Tap the card's **Relay bytes** count for the full list: each byte's guess, every candidate with how it is known ("heard directly 400 m from here", "6.2 km away", "~3 km away on SigSleuth's map"), and the nodes ruled out as too strong. Guesses and answers stay in memory: the database, the CSV and the upload keep only the raw relay byte, so SigSleuth makes its own.

**Limits.** The guess errs towards ambiguity: two nodes sharing a byte in the same area stay ambiguous, and a mobile node heard once in a place stays a candidate there for 30 days. Upstream once named relays by matching the whole node database and dropped it because it named stale or wrong nodes ([#4292](https://github.com/meshtastic/Meshtastic-Android/issues/4292), [design#33](https://github.com/meshtastic/design/issues/33), [#3773](https://github.com/meshtastic/Meshtastic-Android/pull/3773)). That's why the node database only adds possible candidates here. Relying on direct neighbours follows the firmware's own relay attribution in [firmware#12090](https://github.com/meshtastic/firmware/pull/12090).

## CSV export

One session per file, with the columns `session_id, seq, heard_at_ms, latitude, longitude, accuracy_m, fix_age_ms, sender_node_id, recipient_node_id, packet_id, rx_snr, rx_rssi, hop_start, hop_limit, relay_byte, ok_to_mqtt, default_key`. A missing value is an empty cell. The radio settings are in the database's `survey_session` table, not in the CSV.

Compared with Settings → "Export all packets", it has the phone's own GPS fix with its accuracy and age instead of the node's last known position, times to the millisecond, RSSI, packet IDs, and packets the node couldn't decrypt. It has no payloads or sender names and positions, writes the relay byte as a decimal number, and keeps rows without an SNR.

## Code map

| File | What it does |
| :--- | :--- |
| `core/repository/.../SurveyPacketTap.kt` | The hook: `MeshMessageProcessorImpl` hands it every raw packet before decoding |
| `SurveyCapture.kt` | Which packets become rows, and each row's map cell |
| `SurveyRecorder.kt` | Sessions: start, stop, radio away, settings changes |
| `SurveyService.kt` | The foreground service (type `location`) and its notification |
| `SurveyLocation.kt` | The one-second GPS request |
| `SurveyDatabase.kt` | Room database `signal_survey.db` and its migrations |
| `SurveyNewToYou.kt` | "New to you", and recounting it after a delete |
| `SurveyUploader.kt`, `SurveyUploadWorker.kt` | Uploads, and the retry once online |
| `SurveyRelays.kt`, `SurveyRelayNodes.kt` | Relay guesses, and SigSleuth's list of relay nodes |
| `SurveyCsv.kt` | CSV export |
| `SurveySettings.kt` | Server address, password and switches |
| `ui/SurveyScreen.kt`, `ui/SurveyViewModel.kt` | The screen |
| `SurveyLaunch.kt`, `SurveyTileService.kt` | The launcher shortcut, the Quick Settings tile, and the link to the screen they open |

Some rules copy SigSleuth's and must change with it: the fix limits behind the GPS warning (10 s, 100 m), the map cells (zoom 17 tiles) and when two sessions are on the same network (same spreading factor and bandwidth, frequencies within half a bandwidth).

## Build and try

```bash
./gradlew :feature:survey:testDebugUnitTest
./gradlew :androidApp:installFdroidDebug
# Reach a SigSleuth running on your computer as http://localhost:8001
adb reverse tcp:8001 tcp:8001
# The database uses WAL: copy all three files to read it elsewhere.
for f in signal_survey.db signal_survey.db-wal signal_survey.db-shm; do
  adb exec-out run-as com.geeksville.mesh.fdroid.debug cat databases/$f > $f
done
```

`APPLICATION_ID` (`io.github.joroses.meshsurvey`) applies to release builds only. Debug builds use `com.geeksville.mesh.<flavor>.debug`, so the fork's debug build replaces an upstream debug build on the same phone. A `google` release also needs an `io.github.joroses.meshsurvey` client in `google-services.json`.

## Upstream files the fork touches

Keep these edits small; a rebase onto upstream has to carry them.

| File | Change |
| :--- | :--- |
| `settings.gradle.kts` | `":feature:survey"` |
| `build-logic/convention/src/main/kotlin/RootConventionPlugin.kt` | the module in `ALL_MODULES_FULL` and `ANDROID_ONLY_MODULES` |
| `androidApp/build.gradle.kts` | `implementation(projects.feature.survey)` |
| `androidApp/.../di/AppKoinModule.kt` | `FeatureSurveyModule::class` |
| `androidApp/.../ui/Main.kt` | `surveyGraph(backStack)` |
| `core/navigation/.../Routes.kt`, `NavigationConfig.kt` | `SurveyRoute` and its serializer |
| `core/navigation/.../DeepLinkRouter.kt`, `DeepLinkRouterTest.kt` | `settings/survey` opens `SurveyRoute.Survey` |
| `core/repository/.../SurveyPacketTap.kt` | new: the hook's interface and its shared instance |
| `core/data/.../manager/SurveyPacketTapImpl.kt` | new: Koin's binding, forwarding to the shared instance |
| `core/data/.../manager/MeshMessageProcessorImpl.kt` | one import and one call, no constructor parameter |
| `core/data/.../manager/MeshMessageProcessorImplTest.kt` | one test of the hook |
| `core/service/.../ConversationShortcutPublisher.kt`, `ConversationShortcutPublisherTest.kt` | prunes only conversation shortcuts, so the survey's launcher shortcut stays |
| `feature/settings/.../radio/RadioConfig.kt` | the Settings entry, behind `showSignalSurvey` |
| `feature/settings/src/androidMain/.../SettingsScreen.kt` | `showSignalSurvey = true` |
| `.github/workflows/reusable-check.yml` | the module's tests and coverage in the `shard-app` test shard |
| `core/resources/.../values/strings.xml`, `.skills/compose-ui/strings-index.txt` | `signal_survey` (the entry's title) |

The fork's own name and identity, kept out of the Meshtastic trademark (see `CONTRIBUTING.md` → Forks and rebrands):

| File | Change |
| :--- | :--- |
| `README.md` | replaced by the fork's own; it links upstream's. Upstream edits its README often: on a rebase, keep this one |
| `config.properties` | `APPLICATION_ID=io.github.joroses.meshsurvey`; `VERSION_NAME_BASE` gets a `-survey.N` suffix |
| `androidApp/src/main/res/values/strings.xml`, `feature/widget/src/main/res/values/strings.xml` | `app_name`, `nfc_tag_emulation_service` and `widget_local_stats_label` say Mesh Survey |
| `core/resources/.../values*/strings.xml`, `feature/intro/.../WelcomeScreen.kt`, `core/service/.../BootCompleteReceiver.kt`, `MeshServiceOrchestrator.kt` | app-self UI text, permission explanations and untranslated fallbacks say Mesh Survey; copyright and trademark notices stay. Finnish and Estonian call the map the mesh map (`mesh-kartta`, `mesh-kaardi`), not the Meshtastic map |
| `feature/discovery/.../PdfDiscoveryExporter.kt`, `TextDiscoveryExporter.kt`, `DiscoveryReportFormatter.kt` | the discovery report's title, footer and `meshsurvey_discovery_` file name say Mesh Survey |
| `feature/settings/.../PersistenceSection.kt`, `DeviceProfileExportFileName.kt` (and its test), `TAKConfigItemList.kt`, `feature/node/.../MetricsViewModel.kt` | suggested export file names start `MeshSurvey_`; exported GPX says `creator="Mesh Survey"` |
| `core/model/.../MeshtasticUrlConstants.kt`, `ChannelSet.kt`, `SharedContact.kt`, `UriUtils.kt`, `core/navigation/.../Routes.kt`, `androidApp/src/main/AndroidManifest.xml` | created share URLs and deep links use `meshsurvey://meshsurvey`; `meshtastic.org` manifest filters removed while in-app import of `meshtastic.org` links still works |
| `feature/settings/.../SettingsScreen.kt`, `DesktopSettingsScreen.kt` | upstream Help & Documentation entry hidden from Settings |
| `androidApp/src/main/res/drawable-anydpi/ic_launcher_*.xml`, `ic_splash.xml` | placeholder signal icon on blue |
| `core/resources/src/androidMain/res/drawable/meshtastic_ic_notification.xml`, `core/resources/.../drawable/ic_meshtastic.xml`, `feature/widget/.../drawable/widget_app_icon.xml` | the same placeholder icon (notification, top bar, widget) |
| `core/service/.../MeshNotificationManagerImpl.kt`, `feature/widget/.../LocalStatsWidget.kt` | the app's label in place of `meshtastic_app_name` |
| `feature/settings/.../AboutScreen.kt` | `GITHUB_REPO_URL` points at this fork; `copyright_notice` stays |

Everything else, including the service's and the tile's manifest entries and all other strings (English only, prefix `survey_`), is in this module, so upstream's frequent `strings.xml` and manifest changes never conflict.
