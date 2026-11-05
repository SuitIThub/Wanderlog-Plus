# Vault: Video support + web management — design

Date: 2026-11-05
Status: approved (design sections 1+2 confirmed in chat)

## Context and intent

The vault is a full backup: the app enumerates every image on the device and uploads it
(no classification since 2026-11-05). This change extends the vault to videos and turns
the spartan web overview into a management UI.

User decisions that shape the design:

1. **Videos are noticed, but only a thumbnail uploads automatically.** The full video
   uploads only when explicitly queued from the server's web page (per video).
2. **No size limit for videos.** A single request must fit a ~10-minute WorkManager
   window and survive the slow/flaky tunnel (~1 MB/s on a good day), so the upload is
   **chunked and resumable** (approach A: upload sessions with per-part idempotency).
3. **Web management:** tabs Photos/Videos, per-video "Upload anfordern" with status,
   multi-select + bulk delete, sortable (date/size/name), browser playback of uploaded
   videos, **always-visible device status** (files on device / not yet uploaded) and
   **pause/resume buttons** — pause is sticky until manually resumed.

## Data model (shared, `VaultProtocol.kt`)

All new fields have defaults: old app/server combinations keep working.

- `VaultImageInfo` + `kind: VaultKind = PHOTO` (`PHOTO`/`VIDEO`),
  `videoRef: String? = null` (device-local MediaStore URI, videos only),
  `durationMs: Long? = null`, `videoBytes: Long? = null` (size per device),
  `hasVideo: Boolean = false` (full video stored on the server).
- `VaultScanUpdate` + optional `photosOnDevice: Int?`, `videosOnDevice: Int?`,
  `pendingUploads: Int?` (feeds the web status bar; old apps simply don't send them).
- `VaultUploadTrigger` becomes `{requested: Boolean, paused: Boolean = false, videoRefs: List<String> = emptyList()}` —
  one post-sync check covers resume-flag, pause state and requested videos.
- New `VaultVideoSession(id, videoRef, fileName, size, sha256, partSize, receivedParts: List<Int>, complete: Boolean)`.

## Server storage (`VaultStore`, per user)

- `images.json` / `files/<id>` — as today; entries carry the new metadata.
- `videos/<id>` — full videos, named by the vault entry id.
- `sessions/<sessionId>.json` + `sessions/<sessionId>.parts/<n>` — in-flight uploads.
  Session key is `videoRef` + whole-file SHA-256: re-init with the same key resumes the
  existing session. Parts are idempotent by index + part hash (`X-Part-Sha256`);
  conflicting content at the same index → 409.
- On `complete`: all parts present → stream-assemble, verify whole-file SHA-256 → move
  to `videos/<id>`, set `hasVideo=true` on the entry, delete the session.
- Janitor: sessions older than 7 days are deleted on next session access.
- `video-requests.json` (set of videoRefs), `uploads-paused` flag file — same pattern
  as the existing `upload-request` flag.

## Endpoints

JSON API (bearer + protocol header, per-user namespace):

- `POST /api/vault/images` — unchanged multipart plus new form fields
  (`kind`, `videoRef`, `durationMs`, `videoBytes`). Idempotent by content hash as today.
- `POST /api/vault/videos/sessions` — init/resume; if the whole-file hash is already
  stored, responds with `complete=true` immediately (zero bytes transferred).
- `GET /api/vault/videos/sessions/{id}` — resume source (`receivedParts`).
- `PUT /api/vault/videos/sessions/{id}/parts/{n}` — raw bytes + `X-Part-Sha256`;
  own size limit (~20 MB), not `maxFileBytes`.
- `POST /api/vault/videos/sessions/{id}/complete` → updated `VaultImageInfo`.
- `POST /api/vault/videos/{id}/request` / `unrequest` — full-video queue (idempotent).
  Queue lifecycle: a ref leaves `video-requests.json` when (a) its session completes
  (server clears it in the `complete` handler) or (b) the app calls `unrequest`
  (e.g. the source vanished from the device — otherwise the app would re-mirror the
  ref after every sync and loop).
- `POST /api/vault/uploads/pause` / `resume`; `GET /api/vault/uploads/request`
  → `{requested, paused, videoRefs}`.
- `POST /api/vault/images/bulk-delete` `{ids: [...]}` → per-id best-effort delete.

Web (query-token like `/vault/img`):

- `GET /vault/vid/{id}?token=…` — video streaming with HTTP Range support
  (Ktor `PartialContent`) for browser playback.

**Part size: 8 MB** (~8 s/part through the tunnel, ~500 MB per 10-minute worker window,
fine-grained resume/pause).

## App flow

- **Enumeration** (`VaultScanner.run`): beside `MediaStore.Images` also queries
  `MediaStore.Video` (name, mime, duration, size). Each new video: on-device thumbnail
  (`MediaMetadataRetriever`, first frame, ~1280 px JPEG) behind an injectable
  `VideoThumbnails` interface; queued in `vault_found` with `kind=VIDEO`, `videoRef`,
  `durationMs`, `sizeBytes` (Room migration 4→5, defaults for old rows; no behaviour
  change for photos). The thumbnail rides the existing drain (staging hash,
  by-hash pre-check, idempotent server store) with the extra multipart fields.
- **Full-video upload:** after each sync the app mirrors the server's requested
  `videoRefs` into a small Room table (`vault_video_requests`). The drain (same worker
  chain, same time-box) processes photos first, then video requests: stream-hash the
  file (single pass), init/resume the session, upload missing parts until the time-box
  ends or pause arrives, then `complete`. At most one active video per pass; chained
  workers continue. `VaultUploadTrigger.check()` parses `{requested, paused, videoRefs}`
  and schedules upload passes only when not paused.
- **Pause:** the server flag is the truth. Checked via `GET /api/vault/uploads/request`
  at pass start and between parts (one tiny GET per 8 MB — negligible); pause stops
  the pass cleanly, resume lets the next sync/trigger pick up again. No auto-resume.

## Web UI (`/vault`)

- **Sticky status bar:** "Gerät: X Fotos + Y Videos · nicht übertragen: Z" (from the
  extended scan report), pause/resume buttons, progress of open video sessions
  ("Urlaub.mp4 — 43 %"). The bar refreshes itself from the extended
  `/vault/state` fingerprint without a page reload; the page still reloads only when
  content actually changed (fingerprint gains videoRequests/openSessions/paused).
- **Tabs** Photos | Videos, **sort links** (Datum/Größe/Name; default date desc),
  **checkboxes + "Ausgewählte löschen (n)"** (bulk endpoint; single delete stays).
- Video cards show duration, original size and a status badge ("Thumbnail" /
  "angefordert" / "lädt n %" / "geladen ▶"). Uploaded videos link to
  `/vault/vid/{id}` (native browser player).

## Error handling

- Part hash mismatch → 409; the app retries the part twice, then aborts the pass
  (next pass resumes from `receivedParts`).
- Whole-file hash mismatch on `complete` → 4xx; session discarded, video stays
  requested; lack of progress is visible in the web status.
- Orphaned sessions (device never returns) → 7-day janitor.
- Requested video deleted on device → dropped from the local queue; the server entry
  stays a thumbnail.
- Two devices, same `videoRef`, different content: session key is ref+hash; first
  `complete` wins (known limitation, single-device reality).

## Testing (TDD per layer)

- **Server:** session lifecycle (init/resume/parts/complete, dedup-by-hash, 409 part
  conflict, janitor), queue/pause endpoints, bulk delete, Range streaming, extended
  `/vault/state` fingerprint, web HTML (tabs/badges/sort/status bar).
- **Client:** every new `SyncApi` call against MockEngine (404/409 paths included).
- **App:** Room migration 4→5; video enumeration + thumbnail via injected
  `VideoThumbnails` fake; drain (pause between parts, resume from `receivedParts`,
  init dedup); trigger parsing.
- **Shared:** DTO defaults against old JSON (no new fields present).

## Out of scope / known limits

- HEVC videos may not play in every browser (stored and downloadable regardless).
- No video transcoding/compression anywhere.
- Multi-device `videoRef` collisions are accepted (see error handling).
- The 25 MB `maxFileBytes` limit is untouched (parts have their own limit);
  photo uploads are unchanged.
