# Vault Videos + Web Management Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Videos appear in the vault as auto-uploaded thumbnails; full videos upload chunked/resumable only when queued from the reworked web management UI (tabs, sort, bulk delete, playback, sticky device status, pause/resume).

**Architecture:** Approach A from the spec: server-side upload sessions (8 MB parts, idempotent per index+hash, resume via `receivedParts`), thumbnails ride the existing image drain, web page gains tabbed management with a self-refreshing sticky status bar.

**Tech Stack:** Kotlin multi-module (`shared` DTOs, `server` Ktor 3.0.3 + file storage, `client` Ktor SyncApi, `app` Android Room/WorkManager/Robolectric). JDK 17 for all Gradle runs: `$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-17.0.17.10-hotspot"`.

**Spec:** `docs/superpowers/specs/2026-11-05-vault-videos-design.md`

## Global Constraints

- Part size **8 MB** (`VAULT_PART_SIZE = 8L * 1024 * 1024`); part upload limit = part size + 64 KB headroom (NOT `maxFileBytes`, which stays 25 MB untouched).
- All new DTO fields have defaults; old app/server JSON must keep decoding (`ignoreUnknownKeys`, `explicitNulls = false` on both sides already).
- App UI strings are German; server web strings are German. Class/method names English.
- App module tests: JUnit4 + Robolectric SDK 34, `org.junit.*` (no kotlin.test on app classpath), inline `MockEngine { }` lambdas, compare `req.method == HttpMethod.X`.
- Server tests: JUnit5, `testApplication`, helpers `testServices(dir)`, `jsonClient()`, `authed(TOKEN_A/B)` in `TestSupport.kt`/`VaultRoutesTest.kt`.
- Test commands: `:server:test`, `:client:test`, `:shared:test`, `:app:testDebugUnitTest` (add `--tests "<class>"` for focused runs).
- Every task ends green and commits; do not sweep unrelated working-tree changes into task commits (stage files explicitly).

## Review Focus

1. **Stale MediaStore size:** `MediaStore.Video.Media.SIZE` can be outdated. The app must declare the session `size` from `contentResolver.openAssetFileDescriptor(uri, "r")?.length`, never from MediaStore — pinned in Task 8 (test: declared size comes from the injected stream length, not the cursor value).
2. **Pause lands mid-part:** the in-flight part finishes, no new part starts — pinned in Task 8 (test: pause flag flips after part 1 → exactly 1 part uploaded, pass ends without error).
3. **Janitor vs. active upload:** session expires (>7 d) while a device still uploads → part PUT answers 404 → the app re-inits and resumes — pinned in Task 3 (404 on stale session) and Task 8 (re-init path).
4. **Range playback:** browsers send `Range` headers — `/vault/vid/{id}` must answer 206 with `Content-Range` — pinned in Task 4.
5. **Odd video MIME types** (HEVC etc.): stored and served as declared, fallback `application/octet-stream` — pinned in Task 4 (test: unknown mime → octet-stream).

---

### Task 1: Shared DTOs

**Files:**
- Modify: `shared/src/main/kotlin/de/wanderlogplus/shared/sync/VaultProtocol.kt`
- Test: `shared/src/test/kotlin/de/wanderlogplus/shared/sync/VaultProtocolTest.kt` (create)

**Interfaces:**
- Produces (used by every later task):
  ```kotlin
  enum class VaultKind { PHOTO, VIDEO }
  data class VaultImageInfo( // existing fields unchanged, then:
      ..., val kind: VaultKind = VaultKind.PHOTO, val videoRef: String? = null,
      val durationMs: Long? = null, val videoBytes: Long? = null,
      val hasVideo: Boolean = false, val videoHash: String? = null, val videoMimeType: String? = null)
  data class VaultScanUpdate( // existing fields, then:
      ..., val photosOnDevice: Int? = null, val videosOnDevice: Int? = null, val pendingUploads: Int? = null)
  data class VaultScanStatus( // existing fields, then same three optional Int? fields)
  data class VaultUploadTrigger(val requested: Boolean, val paused: Boolean = false, val videoRefs: List<String> = emptyList())
  data class VaultVideoSession(val id: String, val videoRef: String, val fileName: String,
      val size: Long, val sha256: String, val partSize: Long, val receivedParts: List<Int>, val complete: Boolean)
  const val VAULT_PART_SIZE = 8L * 1024 * 1024 // shared by server store, routes and the app's part loop
  ```
- Consumes: nothing.

- [ ] **Step 1: Failing test** — `VaultProtocolTest`: (a) decode `{"id":"i","fileName":"f","mimeType":"image/jpeg","size":1,"score":0.0,"uploadedAt":2}` → `VaultImageInfo` with `kind == PHOTO`, `hasVideo == false`; (b) decode `{"requested":true}` → `VaultUploadTrigger(true, false, emptyList())`; (c) round-trip a fully populated VIDEO `VaultImageInfo` and a `VaultVideoSession`; use `Json { explicitNulls = false; ignoreUnknownKeys = true }` like the stores do.

- [ ] **Step 2: Run** `:shared:test --tests "de.wanderlogplus.shared.sync.VaultProtocolTest"` → FAIL (unresolved symbols).

- [ ] **Step 3: Implement** the DTO changes above in `VaultProtocol.kt` (append fields with defaults; keep existing KDoc style; update the stale `score` KDoc to "legacy classifier value, 0 for plain backups").

- [ ] **Step 4: Run** the same test → PASS.

- [ ] **Step 5: Commit** `feat(shared): vault DTOs for video sessions, pause and device stats`

---

### Task 2: Server store — sessions, requests, pause flag

**Files:**
- Modify: `server/src/main/kotlin/de/wanderlogplus/server/VaultStore.kt`
- Test: `server/src/test/kotlin/de/wanderlogplus/server/VaultStoreSessionsTest.kt` (create; plain JUnit5 with `@TempDir`, constructs `VaultStore(dir, 25L * 1024 * 1024)` directly)

**Interfaces:**
- Produces:
  ```kotlin
  // flags & queue (same file-flag/JSON pattern as upload-request)
  fun setUploadPaused(user: String, paused: Boolean); fun uploadPaused(user: String): Boolean
  fun requestVideo(user: String, videoRef: String); fun unrequestVideo(user: String, videoRef: String)
  fun videoRequests(user: String): Set<String>
  // sessions (session key = videoRef + sha256; id random 32-hex)
  fun startVideoSession(user: String, videoRef: String, fileName: String, size: Long, sha256: String, videoMimeType: String): VaultVideoSession // dedup: entry with hasVideo && videoHash == sha256 → VaultVideoSession(complete = true) with a blank id (client only checks `complete`), else resume-or-create
  fun videoSession(user: String, sessionId: String): VaultVideoSession? // null for unknown/id-mismatch; applies the 7-day janitor first
  fun openVideoSessions(user: String): List<VaultVideoSession> // for the web status bar; janitor-applied
  fun savePart(user: String, sessionId: String, index: Int, input: InputStream): VaultVideoSession // streams ≤ VAULT_PART_SIZE + 64K, computes part SHA-256 while streaming; same content twice = no-op; throws PartConflict() on different content at a taken index, SessionGone() for unknown id, FileTooLarge() past the cap
  fun completeVideoSession(user: String, sessionId: String): VaultImageInfo // all parts present → stream-assemble to tmp, verify whole sha256 → move to videos/<entryId>, entry gains hasVideo=true, videoHash, videoMimeType; clears videoRef from video-requests; deletes session; throws SessionGone() / BadInput() when parts missing or hash mismatches
  fun videoPath(user: String, id: String): Path? // like imagePath for videos/<id>
  class PartConflict : RuntimeException(); class SessionGone : RuntimeException()
  ```
- `deleteImage` additionally deletes `videos/<id>`, removes the entry's `videoRef` from `video-requests.json`, and deletes any open session with that ref.
- Consumes: Task 1 DTOs.

- [ ] **Step 1: Failing tests** — `VaultStoreSessionsTest`:
  - `sessionLifecycleResumeAndComplete`: seed a photo entry via `addImage`; `startVideoSession` for ref r/hash h (size = 3 parts worth); `savePart` 0 and 2 → `videoSession` shows `receivedParts == [0, 2]`; re-`startVideoSession` same key → same session id (resume); `savePart` 1; `completeVideoSession` → entry has `hasVideo`, `videoHash == h`; `videoRequests(r)` cleared; `videoPath` serves bytes equal to the three parts concatenated.
  - `saveScanCarriesDeviceStats`: `saveScan` with `photosOnDevice = 100, videosOnDevice = 7, pendingUploads = 42` → `scan(user)` returns all three (mapping must not drop the extended fields).
  - `savePartIsIdempotentAndConflicts`: same bytes twice at index 0 → ok; different bytes at index 0 → `PartConflict`.
  - `completeWithMissingPartThrows` / `completeWithHashMismatchThrows` (tamper: declare hash of other content) → `SessionGone`/`BadInput`, session file removed after hash mismatch.
  - `startSessionDeduplicatesByVideoHash`: complete one session; `startVideoSession` with same sha256 → `complete == true` without creating a session file.
  - `janitorDropsStaleSessions`: write a session JSON with `createdAt = now - 8 days` (store-internal format) → `openVideoSessions` empty, files gone.
  - `pauseFlagAndVideoRequestsRoundTrip` incl. per-user isolation.
  - `deleteImageCleansVideoArtifacts`: complete a session, then `deleteImage(entryId)` → video file, request ref, session gone.

- [ ] **Step 2: Run** `:server:test --tests "de.wanderlogplus.server.VaultStoreSessionsTest"` → FAIL (unresolved).

- [ ] **Step 3: Implement** in `VaultStore.kt`: new dirs `videos/`, `sessions/`; internal `@Serializable private data class SessionState(id, videoRef, fileName, size, sha256, videoMimeType, partSize = VAULT_PART_SIZE, receivedParts: Set<Int>, createdAt)`; mapping to/from the shared `VaultVideoSession`; janitor constant `SESSION_TTL_MILLIS = 7 * 24 * 3600 * 1000L`; part size comes from the shared `VAULT_PART_SIZE` (Task 1); `saveScan` copies the three extended stat fields into `VaultScanStatus`. Follow the existing comment style about which methods synchronize (streaming methods stay lock-free like `addImage`, metadata commits synchronized).

- [ ] **Step 4: Run** → PASS (plus full `:server:test` still green).

- [ ] **Step 5: Commit** `feat(server): vault video sessions, requests and pause flag in the store`

---

### Task 3: Server JSON API endpoints

**Files:**
- Modify: `server/src/main/kotlin/de/wanderlogplus/server/VaultRoutes.kt`
- Test: `server/src/test/kotlin/de/wanderlogplus/server/VaultVideoRoutesTest.kt` (create, same helpers as `VaultRoutesTest`)

**Interfaces:**
- Produces (HTTP, all inside `vaultApi`'s `route("/api/vault")`):
  - `POST /videos/sessions` body `{"videoRef","fileName","size","sha256","videoMimeType"}` → 200 `VaultVideoSession`; 400 on blank ref/name, size ≤ 0, or non-`^[0-9a-f]{64}$` hash.
  - `GET /videos/sessions/{id}` → 200 `VaultVideoSession` | 404 `not_found`.
  - `PUT /videos/sessions/{id}/parts/{n}` raw body + `X-Part-Sha256` header → 200 `VaultVideoSession` | 404 (`SessionGone`) | 409 `{"error":"part_conflict"}` (`PartConflict`) | 413 (`FileTooLarge`); index must be `< ceil(size / VAULT_PART_SIZE)` else 400.
  - `POST /videos/sessions/{id}/complete` → 200 `VaultImageInfo` | 404 | 400 (`BadInput` → existing handler).
  - `POST /videos/{id}/request` / `POST /videos/{id}/unrequest` → 204; 404 when the id is not a VIDEO entry. The `id` is the vault entry id; the store call uses the entry's `videoRef`.
  - `POST /uploads/pause` / `POST /uploads/resume` → 204.
  - `GET /uploads/request` → 200 `VaultUploadTrigger` (`{requested, paused, videoRefs}`).
  - `POST /images/bulk-delete` body `{"ids":[...]}` → 200 `{"deleted": <Int>}` (best effort per id).
  - `POST /images` (existing multipart) additionally accepts optional form fields `kind` (`"video"` → VIDEO), `videoRef`, `durationMs`, `videoBytes` and stores them on the entry.
- Consumes: Task 1 DTOs, Task 2 store methods.

- [ ] **Step 1: Failing tests** — `VaultVideoRoutesTest`: session happy path over HTTP (init → PUT two parts with correct `X-Part-Sha256` → complete → entry hasVideo); unknown session → 404 on GET/PUT; conflicting part → 409 `part_conflict`; request/unrequest/pause trigger round trip via `GET /uploads/request` incl. TOKEN_B isolation and 401 unauthenticated; bulk delete (2 of 3 ids) → `{"deleted":2}`; image upload with `kind=video` + metadata lands on the entry; hash validation on init → 400.

- [ ] **Step 2: Run** `:server:test --tests "de.wanderlogplus.server.VaultVideoRoutesTest"` → FAIL.

- [ ] **Step 3: Implement** the routes (multipart field handling mirrors the existing score/fileName pattern; raw PUT body via `call.receiveStream()` or a channel; `PartConflict`/`SessionGone` mapped in `StatusPages` next to `FileTooLarge` — check `Application.kt` for the existing exception mapping and add `part_conflict` 409 + `not_found` 404 mappings).

- [ ] **Step 4: Run** → PASS (plus full `:server:test`).

- [ ] **Step 5: Commit** `feat(server): vault video session, queue, pause and bulk-delete endpoints`

---

### Task 4: Server web UI rework

**Files:**
- Modify: `server/src/main/kotlin/de/wanderlogplus/server/VaultRoutes.kt` (`vaultWeb`, `vaultPage`), `server/src/main/kotlin/de/wanderlogplus/server/Application.kt` (install `io.ktor.server.plugins.partialcontent.PartialContent`)
- Test: `server/src/test/kotlin/de/wanderlogplus/server/VaultRoutesTest.kt` (extend)

**Interfaces:**
- Produces:
  - `GET /vault?token=…&tab=photos|videos&sort=date|size|name&page=N` (defaults: photos, date desc); `vaultPage(...)` gains `tab`, `sort`, `paused: Boolean`, `requests: Set<String>`, `sessions: List<VaultVideoSession>`.
  - `GET /vault/vid/{id}?token=…` → 200/206 streamed `videos/<id>` with the entry's `videoMimeType` (fallback `application/octet-stream`), 404 unknown, 401 without token.
  - `GET /vault/state` fingerprint becomes `{"images","latest","scanUpdated","requests","sessionsProgress","paused"}` (`sessionsProgress` = sum of `receivedParts.size` over open sessions — cheap and change-sensitive).
- Consumes: Task 2 store (`openVideoSessions`, `uploadPaused`, `videoRequests`), extended scan fields (Task 1).

- [ ] **Step 1: Failing tests** (extend `VaultRoutesTest`):
  - `webPageHasTabsSortAndStatusBar`: seed scan (extended fields) + one photo + one video entry → page contains `tab=videos`, `sort=size`, the sticky bar markup with device counts, a pause/resume button posting to `/api/vault/uploads/pause`; videos tab shows the video with duration formatted `m:ss` and size, photos tab hides it.
  - `webVideoCardStates`: video entry without request → badge `Thumbnail` + request button posting `/api/vault/videos/<id>/request`; with request → badge `angefordert`; with open session → `lädt 42 %` (seed `receivedParts` 42 of 100 via store); with `hasVideo` → link to `/vault/vid/<id>` labeled `geladen ▶`.
  - `webVideoStreamingServesRanges`: complete a session via store → `GET /vault/vid/<id>?token=…` 200 with video mime; same with header `Range: bytes=0-3` → 206 + `Content-Range`; unknown id → 404; no token → 401; entry with unknown/blank `videoMimeType` → `application/octet-stream`.
  - `webPageBulkDeleteMarkup`: page renders per-card checkboxes `name="sel" value="<id>"` and a delete-selected button; JS posts JSON to `/api/vault/images/bulk-delete`.
  - Update `vaultStateReportsImageAndScanFingerprint` to the extended fingerprint (new fields present, `paused=false`).
- [ ] **Step 2: Run** `:server:test --tests "de.wanderlogplus.server.VaultRoutesTest"` → FAIL.

- [ ] **Step 3: Implement**: install `PartialContent` in `Application.kt`'s module; add `/vault/vid/{id}`; thread `tab`/`sort`/pause/requests/sessions through `vaultPage`; restructure the page: sticky status bar (device counts from the extended scan report + pending, open-session progress lines, pause/resume buttons that fetch then refresh the bar from `/vault/state`), tab links, sort links, video badges/actions, checkbox bulk delete (vanilla JS like the existing `del()`), grid per tab; extend `/vault/state`. Keep the existing polling-JS pattern and German copy.

- [ ] **Step 4: Run** → PASS (full `:server:test`).

- [ ] **Step 5: Commit** `feat(server): rework vault web UI with tabs, video states, bulk delete and sticky status`

---

### Task 5: Client API

**Files:**
- Modify: `client/src/main/kotlin/de/wanderlogplus/client/SyncApi.kt`
- Test: `client/src/test/kotlin/de/wanderlogplus/client/SyncApiTest.kt` (extend)

**Interfaces:**
- Produces:
  ```kotlin
  suspend fun vaultUploadTrigger(): VaultUploadTrigger // replaces vaultUploadRequested() (old servers answering {"requested":…} still decode)
  suspend fun uploadVaultImage(file, fileName, mimeType, score, kind: VaultKind = VaultKind.PHOTO,
      videoRef: String? = null, durationMs: Long? = null, videoBytes: Long? = null): VaultImageInfo // extra multipart fields only when set
  suspend fun startVaultVideoSession(videoRef: String, fileName: String, size: Long, sha256: String, videoMimeType: String): VaultVideoSession
  suspend fun vaultVideoSession(id: String): VaultVideoSession? // 404 → null
  suspend fun uploadVaultVideoPart(sessionId: String, index: Int, bytes: ByteArray, partSha256: String) // PUT raw + header; 409 → ApiException(BAD_REQUEST, code "part_conflict"); 404 → ApiException(BAD_REQUEST, "not_found")
  suspend fun completeVaultVideoSession(id: String): VaultImageInfo
  suspend fun requestVaultVideo(id: String); suspend fun unrequestVaultVideo(id: String) // raw 204 pattern like clearVaultUploadRequest
  suspend fun setVaultUploadsPaused(paused: Boolean) // POST pause|resume
  suspend fun deleteVaultImages(ids: List<String>): Int // returns "deleted" count
  ```
- Consumes: Task 1 DTOs; HTTP surface of Task 3.

- [ ] **Step 1: Failing tests** — extend `SyncApiTest` (existing `api {}`/`json()` helpers): trigger parses `{requested, paused, videoRefs}` and the legacy `{requested}` shape; session init posts all fields; part upload sends `X-Part-Sha256` and raw bytes (assert body via `req.body.toByteArray()`), maps 409→`code == "part_conflict"`; 404 → null on `vaultVideoSession`; complete returns entry; pause posts to `/api/vault/uploads/pause`; bulk delete posts ids and returns count; video metadata multipart fields present when set, absent for photos.

- [ ] **Step 2: Run** `:client:test --tests "de.wanderlogplus.client.SyncApiTest"` → FAIL.

- [ ] **Step 3: Implement** (raw PUT/POST-no-body calls follow the `clearVaultUploadRequest` pattern; JSON calls use `call {}`; `uploadVaultImage` appends optional fields conditionally).

- [ ] **Step 4: Run** → PASS (full `:client:test`).

- [ ] **Step 5: Commit** `feat(client): vault video session, pause and bulk-delete API calls`

---

### Task 6: App Room migration 4→5

**Files:**
- Modify: `app/src/main/kotlin/de/wanderlogplus/app/data/VaultEntities.kt`, `app/src/main/kotlin/de/wanderlogplus/app/data/AppDatabase.kt` (version 5, register migration), `app/src/main/kotlin/de/wanderlogplus/app/data/Migrations.kt`, `app/src/main/kotlin/de/wanderlogplus/app/AppContainer.kt` (`addMigrations(…, MIGRATION_4_5)`)
- Test: `app/src/test/kotlin/de/wanderlogplus/app/data/MigrationTest.kt` (extend)

**Interfaces:**
- Produces:
  ```kotlin
  // VaultFoundEntity gains: val kind: String = "photo", val durationMs: Long? = null, val sizeBytes: Long? = null
  @Entity(tableName = "vault_video_requests") data class VaultVideoRequestEntity(@PrimaryKey val ref: String, val requestedAt: Long)
  // VaultDao gains:
  @Insert(onConflict = OnConflictStrategy.IGNORE) fun insertVideoRequest(req: VaultVideoRequestEntity): Long
  @Query("DELETE FROM vault_video_requests WHERE ref = :ref") fun deleteVideoRequest(ref: String)
  @Query("SELECT ref FROM vault_video_requests") fun videoRequestRefs(): List<String>
  @Query("SELECT f.* FROM vault_found f JOIN vault_video_requests r ON r.ref = f.uri") fun requestedVideos(): List<VaultFoundEntity>
  ```
- Consumes: nothing (Task 5's methods get used in Tasks 7–9).

- [ ] **Step 1: Failing test** — `MigrationTest.migrate4To5KeepsRowsAndAddsVideoColumns`: create v4 with one `vault_found` row → migrate → row intact with `kind == "photo"`, `durationMs`/`sizeBytes` null; `vault_video_requests` exists. (Schema JSON `app/schemas/…/5.json` is generated by the build; `MigrationTestHelper` needs it — run once, it's produced by ksp.)

- [ ] **Step 2: Run** `:app:testDebugUnitTest --tests "de.wanderlogplus.app.data.MigrationTest"` → FAIL.

- [ ] **Step 3: Implement** entity/DAO/migration (`ALTER TABLE vault_found ADD COLUMN kind TEXT NOT NULL DEFAULT 'photo'` etc.; `CREATE TABLE vault_video_requests …`); bump `@Database(version = 5)`; register in `AppContainer`.

- [ ] **Step 4: Run** → PASS (plus `RoomEntityStoreTest` etc. stay green).

- [ ] **Step 5: Commit** `feat(app): room v5 with video kinds and video-request queue`

---

### Task 7: App scan — video enumeration + thumbnail drain path

**Files:**
- Create: `app/src/main/kotlin/de/wanderlogplus/app/vault/VideoThumbnails.kt`
- Modify: `app/src/main/kotlin/de/wanderlogplus/app/vault/VaultScanner.kt`
- Test: `app/src/test/kotlin/de/wanderlogplus/app/vault/VaultScannerDrainTest.kt` (extend), `app/src/test/kotlin/de/wanderlogplus/app/vault/VideoThumbnailsTest.kt` (create)

**Interfaces:**
- Produces:
  ```kotlin
  fun interface VideoThumbnails { fun thumbnail(uri: Uri): ByteArray? } // JPEG, ≤1280 px longest side
  class MediaStoreVideoThumbnails(private val appContext: Context) : VideoThumbnails // MediaMetadataRetriever first frame → scale → JPEG q80; null when undecodable
  // VaultScanner constructor gains: private val thumbnails: VideoThumbnails = MediaStoreVideoThumbnails(appContext)
  // drainUploads: for pending.kind == "video" the staged bytes are thumbnails.thumbnail(uri) (null → drop like a vanished source), hashed identically; upload call passes kind=VIDEO, videoRef=uri, durationMs, videoBytes
  // report()/publish() send extended VaultScanUpdate (photosOnDevice, videosOnDevice, pendingUploads = dao.pendingUploadCount())
  ```
- Consumes: Task 5 `uploadVaultImage` extension, Task 6 entity fields.

- [ ] **Step 1: Failing tests** — extend `VaultScannerDrainTest`: seed a `kind="video"` row; fake `thumbnails` returns `byteArrayOf(1,2,3)`; MockEngine asserts multipart contains `kind=video` + `videoRef` + `durationMs`; by-hash pre-check uses the thumbnail bytes' SHA-256 (`sha256(010203)`); null thumbnail → row dropped without any POST. `VideoThumbnailsTest` (Robolectric): generate a tiny video? — NO: `MediaMetadataRetriever` on Robolectric can't decode real videos reliably; instead pin the scaling/JPEG path via a test-visible `internal fun frameToJpeg(frame: Bitmap, maxSide: Int, quality: Int): ByteArray` helper (create a 2000×1000 bitmap → longest side 1280, decodable JPEG, aspect preserved).

- [ ] **Step 2: Run** → FAIL.

- [ ] **Step 3: Implement**: `VideoThumbnails.kt` (interface + impl + `frameToJpeg`); `VaultScanner`: `queryVideos()` on `MediaStore.Video` (same projection style + `DURATION`, `SIZE`; collection `MediaStore.Video.Media.getContentUri(VOLUME_EXTERNAL)`/legacy), inserts rows with `kind="video"`, `durationMs`, `sizeBytes`; scan `total`/`found` counting includes videos; drain branches per kind; `publish`/`report` extended (device counts come from the two queries; `pendingUploads` after the photo queue snapshot). Permission note: READ_MEDIA_IMAGES covers images only on API 33+ — extend `hasMediaPermission` to also check `READ_MEDIA_VIDEO`, but treat videos as best-effort (images-only permission must not block the photo backup: query videos only when video permission granted).

- [ ] **Step 4: Run** → PASS (full `:app:testDebugUnitTest` minus the known-flaky `SyncSchedulerTest`).

- [ ] **Step 5: Commit** `feat(app): enumerate videos and upload their thumbnails`

---

### Task 8: App drain — chunked video uploads with pause and resume

**Files:**
- Modify: `app/src/main/kotlin/de/wanderlogplus/app/vault/VaultScanner.kt`
- Test: `app/src/test/kotlin/de/wanderlogplus/app/vault/VaultScannerDrainTest.kt` (extend)

**Interfaces:**
- Consumes: Task 5 session API, Task 6 `requestedVideos()`, Task 7. Produces: extended `DrainOutcome` unchanged in shape (video part uploads count into `uploaded` only at `complete`).
- Behavior contract pinned by tests:
  - Session `size` comes from `openAssetFileDescriptor(uri, "r").length` (inject via the existing `openStream`-style seam: add `fileLength: (Uri) -> Long?` defaulting to the descriptor; **never** MediaStore SIZE) — Review Focus 1.
  - Whole-file SHA-256 computed in one streaming pass before session init (no upload when `complete == true` comes back).
  - Parts loop: missing indices ascending; before each part `GET /uploads/request` → `paused == true` ⇒ stop cleanly (not an error); time-box deadline ⇒ stop; part 409 → retry same part once, then `stoppedOnError`; part/session 404 → re-`startVaultVideoSession` (janitor case) and continue; all parts → `completeVaultVideoSession` → `dao.deleteVideoRequest(ref)`; `uploaded++`.
  - Source vanished mid-video (openStream null at hashing or part read): `unrequestVaultVideo`-best-effort + `deleteVideoRequest`, continue with next.
  - At most one video per pass (time-box realism); photos always drain first.

- [ ] **Step 1: Failing tests** (extend `VaultScannerDrainTest`, MockEngine scripted by path):
  - `videoUploadsInPartsAndCompletes`: fake `fileLength`/stream of 2.5 parts → init, parts 0,1,2 in order with correct part hashes, complete → request row deleted, `uploaded == 1`.
  - `videoResumeSkipsReceivedParts`: `GET sessions/{id}`… — simpler: init answers existing session with `receivedParts=[0]` → only parts 1+ are PUT.
  - `pauseBetweenPartsStopsCleanly`: trigger GET answers paused=false first, true after part 0 → exactly one part PUT, outcome not `stoppedOnError`, request row kept — Review Focus 2.
  - `staleSessionIsReinitialized`: first part PUT → 404 `not_found`, init called again, parts resume → completes — Review Focus 3.
  - `partConflictRetriesOnce`: 409 on part 1 → same part PUT again → succeeds; persistent 409 → `stoppedOnError`.
  - `initDedupSkipsUpload`: init returns `complete=true` → zero part PUTs, request deleted, `uploaded == 1`.
  - `declaredSizeComesFromStreamLength`: init body `size` equals the injected stream length, not the cursor/MediaStore value — Review Focus 1.
  - `vanishedVideoIsUnrequestedAndSkipped`: stream null → `unrequest` POST fired, no init.

- [ ] **Step 2: Run** → FAIL.

- [ ] **Step 3: Implement** in `VaultScanner.drainUploads` (photo loop unchanged; afterwards `for (video in dao.requestedVideos())` with the contract above; `sha256Streaming(uri)` helper reusing the `copyHashing` buffer pattern without writing a tmp file; part reads via a reopening stream per part — `openStream(uri)` + `skip(index * VAULT_PART_SIZE)`… prefer `openFileDescriptor` + `FileInputStream.channel.position(...)` for exact seeking, injected the same way for tests).

- [ ] **Step 4: Run** → PASS.

- [ ] **Step 5: Commit** `feat(app): chunked resumable vault video uploads with pause support`

---

### Task 9: App trigger — pause, video queue mirroring, scheduling

**Files:**
- Modify: `app/src/main/kotlin/de/wanderlogplus/app/vault/VaultUploadTrigger.kt`, `app/src/main/kotlin/de/wanderlogplus/app/AppContainer.kt` (wire dao into trigger), `app/src/main/kotlin/de/wanderlogplus/app/sync/SyncRunner.kt` (call site unchanged), `app/src/main/kotlin/de/wanderlogplus/app/vault/VaultUploadWorker.kt` (KDoc only)
- Test: `app/src/test/kotlin/de/wanderlogplus/app/vault/VaultUploadTriggerTest.kt` (rewrite)

**Interfaces:**
- Produces: `VaultUploadTrigger(api, dao: VaultDao, onRequested: () -> Unit)` — `check()`:
  1. `t = client.vaultUploadTrigger()` (fetch failure → leave everything for next sync, as today).
  2. Mirror `t.videoRefs` into `vault_video_requests` (insert-ignore; delete local rows the server no longer lists).
  3. If `t.paused` → stop here (still clears nothing, schedules nothing).
  4. If `t.requested || dao.videoRequestRefs().isNotEmpty()` → `onRequested()`; if `t.requested` → `clearVaultUploadRequest()`.
- Consumes: Task 5 trigger DTO, Task 6 DAO.

- [ ] **Step 1: Failing tests** — rewrite `VaultUploadTriggerTest` (Robolectric + in-memory Room + MockEngine): paused trigger mirrors refs but schedules nothing and clears nothing; requested=true schedules and clears; refs-only (requested=false) schedules; stale local refs are pruned to the server list; old-server body `{"requested":true}` still works.

- [ ] **Step 2: Run** → FAIL.

- [ ] **Step 3: Implement** per contract; `AppContainer` passes `db.vault()`; keep the at-least-once comment block honest (update it).

- [ ] **Step 4: Run** → PASS.

- [ ] **Step 5: Commit** `feat(app): honor server pause and mirror video upload requests after each sync`

---

### Task 10: Whole-suite verification + version bump

**Files:**
- Modify: `app/build.gradle.kts` (versionCode 43 → 44, versionName "0.35.0" → "0.36.0")

- [ ] **Step 1:** Run all suites: `:shared:test :client:test :server:test :app:testDebugUnitTest --console=plain` → all green (the known-flaky `SyncSchedulerTest.requestNowReplacesWaitingWork` may be re-run individually if it trips).

- [ ] **Step 2:** Commit the version bump `chore(app): version 0.36.0` — only after suites are green.
