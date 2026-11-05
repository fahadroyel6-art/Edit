# Ultra Video Merger Pro — v2.0.0
## Development Log & Test Report

**Build:** `v2.0.0` · commit `8e967de` · CI run `42097493874` ✅ success
**Repo:** https://github.com/fahadroyel6-art/Edit
**Release:** https://github.com/fahadroyel6-art/Edit/releases/tag/v2.0.0

---

## 1. What was actually verified (and how)

Being precise about evidence, because the master prompt forbids claiming untested things work.

| Item | How it was verified | Result |
|---|---|---|
| FFmpeg dependency is real and downloadable | HTTP 200 on the POM + 23 MB AAR fetched from `repo1.maven.org` | ✅ confirmed |
| AAR contains the native engine | Unpacked AAR: `libavcodec.so`, `libavformat.so`, `libffmpegkit.so` for `arm64-v8a` + `x86_64` | ✅ confirmed |
| `concat` demuxer is compiled into `libavformat.so` | `strings` scan of the .so shows the `concat` demuxer registration + `concat,file,subfile` | ✅ confirmed |
| MP4/MOV muxer is compiled in | `strings` scan shows `moov` atom writer, `isom`, `avc1`, `delay_moov` | ✅ confirmed |
| Java API signatures match my code | `javap` on every class used: `FFmpegKit`, `FFmpegKitConfig`, `FFprobeKit`, `FFmpegSession`, `Statistics`, `ReturnCode`, `MediaInformation`, `StreamInformation`, `Level` | ✅ every call site cross-checked |
| Project compiles | GitHub Actions `assembleDebug` + `assembleRelease` | ✅ both succeeded |
| APK contains the engine | `unzip -l` on the produced APK | ✅ 10 arm64 + x86_64 `.so` files present |
| Merge output is correct | **NOT verified — needs a real device** | ⚠️ see §3 |

**Not verified, and I will not claim otherwise:** actual merge runs on hardware. I have no Android device or emulator in this environment. The engine's *correctness* is asserted by the 15 on-device tests that ship inside the app — you run them and they report honestly.

---

## 2. Architecture

```
com.ultravidmerger.app
├── diag/                    ← MODULE 15
│   ├── ErrorCode.kt             UVM-1001 … UVM-1017 registry
│   ├── DiagLog.kt               log entry model
│   ├── DiagnosticLogger.kt      bounded ring buffer + rotating files (3×1.5 MB)
│   ├── SystemMonitor.kt         heap / native / CPU / storage / thermal / battery
│   ├── EngineMonitor.kt         11-component live status board
│   ├── ErrorReportGenerator.kt  redacted AI report + repair prompt
│   ├── GlobalCrashHandler.kt    uncaught exception capture + post-restart store
│   └── Diagnostics.kt           facade + ErrorContext
├── engine/
│   ├── VideoAnalyzer.kt         FFprobe primary, MediaExtractor fallback
│   ├── CompatibilityChecker.kt  decides Mode A vs Mode B
│   ├── FFmpegEngine.kt          MODE A: concat demuxer + -c copy
│   ├── MediaMuxerEngine.kt      MODE A fallback: MediaExtractor/Muxer copy
│   ├── TransformerHost.kt       dedicated HandlerThread (thread-safety fix)
│   ├── ReencodeEngine.kt        MODE B: Media3 Transformer composition
│   └── MergeEngine.kt           orchestrator + validation + single-job guard
├── service/
│   ├── MergeService.kt          foreground service + wake lock + job lifecycle
│   ├── MergeStateHolder.kt      StateFlow<MergeUiState> — single source of truth
│   ├── NotificationHelper.kt    progress / pause / resume / cancel actions
│   └── MergeJobState.kt         persistent state → interrupted-job recovery
├── selftest/                    on-device QA
│   ├── TestVideoGenerator.kt    FFmpeg lavfi synthetic clips
│   ├── MergeTestSuite.kt        15 tests that really run the engine
│   └── TestReport.kt            honest PASS/FAIL/SKIPPED model
├── storage/StorageManager.kt    temp file → validated → MediaStore
└── ui/                          Home, Merge, Processing, Completed,
                                 DiagnosticCenter, LogViewer, SelfTest, ErrorSheet
```

### The exact FFmpeg command used for Mode A

```
ffmpeg -y -hide_banner -loglevel info \
  -f concat -safe 0 -fflags +genpts -i <concat_list.txt> \
  -c copy \
  -map 0:v? -map 0:a? \
  -avoid_negative_ts make_zero \
  -movflags +faststart \
  <output.mp4>
```

Why each flag matters:

- `-f concat -safe 0` — the only way to join N files without re-encoding; `-safe 0` is required because SAF pseudo-paths are absolute.
- `-c copy` — **the lossless guarantee.** No decoder or encoder is instantiated.
- `-map 0:v? -map 0:a?` — keep video and the first audio stream, and tolerate inputs with no audio at all.
- `-avoid_negative_ts make_zero` — normalises per-segment start timestamps so the joined timeline stays monotonic. Skipping this is the #1 cause of "merged file plays only the first clip".
- `-movflags +faststart` — moves the `moov` atom to the front, so a partially-written or interrupted file is still diagnosable and the file streams.

### How `content://` is handled without copying

FFmpegKit registers a `saf:` pseudo-protocol. `FFmpegKitConfig.getSafParameterForRead(context, uri)` returns a token like `saf:1234`, and the concat list stores `file 'saf:1234'`. FFmpeg opens it through the registered handler, so a 10 GB input is streamed straight from MediaStore — **no temp copy, no doubled storage**.

---

## 3. Test matrix — status

Fifteen tests ship in the app (`Self-Test` screen). Each one generates real clips with FFmpeg, runs the real engine, and probes the real output with FFprobe. **They have not been run yet — that requires you to install the APK and press "Run All Tests".**

| # | Test | What it proves | Status |
|---|---|---|---|
| T01 | Two compatible clips | 2×4 s @720p30 → ≈8 s output | ⏳ awaiting device run |
| T02 | Three compatible clips | 3-way concat, ≈10 s | ⏳ |
| T03 | Different durations | 4 s + 2 s → ≈6 s | ⏳ |
| T04 | Different frame rates | 30 fps + 24 fps → checker must pick re-encode | ⏳ |
| T05 | Different resolutions | 720p + 480p → lossless must be refused | ⏳ |
| T06 | Different audio codecs | aac + silent → timeline gap handled | ⏳ |
| T07 | Video without audio | silent clip detected as no-audio | ⏳ |
| T08 | Larger file | 30 s + 2 s merge, buffer stays bounded | ⏳ |
| T09 | Corrupted input | analyzer returns null, merge fails cleanly, no crash | ⏳ |
| T10 | Storage pre-flight | 1 KB passes, free+10 GB correctly refused | ⏳ |
| T11 | **Transformer thread contract** | every call on owner looper; off-thread access rejected | ⏳ |
| T12 | Cancellation | post-cancel output is not a valid video | ⏳ |
| T13 | A/V duration sync | output within 5 % of sum of inputs | ⏳ |
| T14 | Progress monotonicity | reported progress never goes backwards | ⏳ |
| T15 | Validation rejects junk | 8 KB of `0x41` is flagged invalid | ⏳ |

**How to run them:** open the app → `🧪 Automated Self-Test` → **Run All Tests** → **Copy Test Report**. The report is a plain-text block you can paste back to me and I will fix whatever fails.

If a fixture cannot be generated on your device (e.g. a codec is missing from the build), that test reports **SKIPPED with the reason** rather than pretending to pass.

---

## 4. The threading bug — root cause and fix

**Error:** `IllegalStateException: Transformer is accessed on the wrong thread.`

**Root cause:** Media3's `Transformer` is strictly single-threaded. The instance, its `start()`/`cancel()` calls and all listener callbacks must be confined to one `Looper`. The previous version constructed it on a `Dispatchers.IO` coroutine and then polled progress from another coroutine — so construction and access happened on different threads.

**Fix — `TransformerHost.kt`:**

```kotlin
object TransformerHost {
    private val thread = HandlerThread("uvm-transformer", THREAD_PRIORITY_BACKGROUND).apply { start() }
    private val handler = Handler(thread.looper)
    private val ownerThreadId = thread.id

    fun post(block: () -> Unit)      // run on owner looper, safe from anywhere
    fun <T> sync(block: () -> T): T  // run and block for result
    fun assertOwnerThread()          // throws if called from the wrong thread
}
```

Every Transformer operation now goes through `TransformerHost`:
- `ReencodeEngine.merge()` builds the composition, encoder factory, listener and calls `transformer.start()` inside `TransformerHost.sync { … }`.
- `cancel()` is posted to the same handler.
- Progress polling is itself posted to the same handler.
- `assertOwnerThread()` exists so test T11 can prove the contract holds.

**Second layer of defence — `MergeEngine.running: AtomicBoolean`.** Only one job can ever own the engine; a second request returns `UVM-1015 JOB_ALREADY_RUNNING` instead of racing.

---

## 5. Module 15 — what each requirement maps to

| Spec requirement | Implementation |
|---|---|
| §1 Live Engine Monitor | `EngineMonitor` + `DiagnosticCenterFragment`. 11 components, event-driven updates, 1 s cheap system poll (no frame sampling) |
| §2 Automatic error detection | All engines call `Diagnostics.report(code, stage, …)`; the stage string maps to the right component automatically |
| §3 Error code generator | `ErrorCode` enum, `UVM-1001`…`UVM-1017`, each with title, description and likely cause |
| §4 AI-ready error report | `ErrorReportGenerator.build()` — app/device version, error block, environment (heap, native heap, CPU, thermal, battery, storage), FFmpeg command + exit code + last 120 log lines, job configuration, component states, service state, stack trace, last 40 log entries |
| §5 One-click copy | `ErrorSheet` → Copy Full Report / Copy Technical Logs / Copy AI Repair Prompt / View Details |
| §6 Long-video diagnostics | 64-bit durations everywhere (`Long` µs), `-avoid_negative_ts make_zero`, 8 MB bounded buffers, duration-vs-sum validation, watchdog counters |
| §7 Live log viewer | `LogViewerFragment` — search, 4 level filters, tap-to-copy a line, copy-all, TXT + JSON export, clear |
| §8 Automatic recovery | `MergeJobState` persisted before work starts; `MergeStateHolder` reports the real terminal state; interrupted jobs are detected on next launch (`UVM-1006`) and never auto-retried |
| §9 Privacy | Everything generated locally. No `INTERNET` permission is declared (it is explicitly removed via `tools:node="remove"`). `redact()` strips `content://` and `/data|/storage|/sdcard` paths down to basenames. Logs are capped at 3 × 1.5 MB |
| §10 Fully integrated | Reachable from Home, Processing, Completed and directly from the Diagnostic Center |

### Redaction example

```
before: /data/user/0/com.ultravidmerger.app/cache/merge/JOB-20261105-193000-1.mp4
after:  …/‹redacted›/JOB-20261105-193000-1.mp4
```

The basename is kept because that is what makes a report debuggable; the directory tree is not.

---

## 6. Honest limitations

1. **FFmpeg build scope.** The free `dev.ffmpegkit-maintained:ffmpeg:8.1.9` artifact ships `arm64-v8a` + `x86_64` only, and its documented codec set is VP8/VP9/AV1 + Opus/Vorbis plus FFmpeg's built-in codecs. It is **not** an x264/x265 encoder build. That is fine for Mode A (stream copy needs no encoder at all) and fine for Mode B in practice (Transformer uses the device's hardware H.264 encoder, not FFmpeg's). But it means: **32-bit (`armeabi-v7a`) devices cannot install this APK.**
2. **Sync is guaranteed for Mode A only.** Stream copy preserves the original timestamps, so A/V sync is bit-identical to the inputs. Mode B re-encodes, so sync is as good as Transformer's — test T13 measures the actual drift.
3. **Thermal handling is advisory, not a throttle.** The app reports thermal state (`🌡️` banner + notification) and refuses to invent speed-ups. It does not dynamically down-clock the encoder, because that would change output quality — which the spec forbids ("Never sacrifice audio or video integrity just to increase processing speed").
4. **WorkManager** is included for watchdog scheduling, but the render itself runs in the foreground service (correct choice for a user-initiated long job). WorkManager is not the renderer.
5. **No physical-device QA.** Stated plainly above. The in-app suite is the mechanism that closes this gap — it is designed so a single button press gives me everything I need to fix real failures.

---

## 7. Install & first-run checklist

```
1. Uninstall any older RawCapture / Ultra Video Merger build.
2. Install UltraVideoMergerPro_v2.0.0_debug.apk   (Android 7.0+, arm64)
3. Grant: Videos access + Notifications.
4. Home → Select Videos → pick 2 clips.
5. Start Merging → watch the mode banner:
      MODE A · LOSSLESS STREAM COPY      (green)  — codecs match
      MODE B · COMPATIBILITY RE-ENCODE   (amber)  — inputs differ, reason listed
6. Lock the screen or leave the app — the notification keeps running,
   with Pause / Cancel actions.
7. On completion: file is in Movies/UltraVideoMerger.
8. Diagnostics → Run Automated Tests → Copy Test Report → paste it back to me.
```

If anything fails at any point: **Diagnostics → Copy AI Repair Prompt**. That puts the full redacted report plus a ready-made repair prompt on your clipboard.

---

## 8. Deliverables

| # | Item | Location |
|---|---|---|
| 1 | Complete Android Studio project | https://github.com/fahadroyel6-art/Edit |
| 2 | Kotlin source (30 files) | same repo, `app/src/main/java/com/ultravidmerger/app/` |
| 3 | Native merging engine | `engine/FFmpegEngine.kt` + `engine/MediaMuxerEngine.kt` |
| 4 | Background rendering | `service/MergeService.kt` |
| 5 | Automated testing system | `selftest/MergeTestSuite.kt` (15 tests) |
| 6 | This report | `TEST_REPORT.md` |
| 7 | Debug APK (34 MB, installable) | [download](https://github.com/fahadroyel6-art/Edit/releases/download/v2.0.0/UltraVideoMergerPro_v2.0.0_debug.apk) |
| 8 | Release APK (30 MB, unsigned) | [download](https://github.com/fahadroyel6-art/Edit/releases/download/v2.0.0/UltraVideoMergerPro_v2.0.0_release-unsigned.apk) |
| 9 | Install instructions | §7 above |
