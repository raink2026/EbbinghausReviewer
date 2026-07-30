## 1. Freeze Repository Protocol

- [x] 1.1 Add v1 `.ebbinghaus/profile.json`, note JSON Schema, and event JSON Schema definitions with stable repository/profile identity, fixed timezone, versioned review-algorithm parameters, typed payloads, and compatibility rules.
- [x] 1.2 Create canonical valid and invalid repository fixtures covering UTF-8/LF Markdown hashing, daily paths, relative images, UUIDs, and schema-version failures.
- [x] 1.3 Create revision-graph fixtures for create, restart, divergent leaves, merge, malformed cycles, and missing parents.
- [x] 1.4 Create causal event fixtures for linear review, concurrent review, review merge, delete, restore, lifecycle conflict, stale events, and reused UUID corruption.
- [ ] 1.5 Run a Gitee sandbox contract spike for multi-file commits, commit pagination, branch advancement, binary Base64 payloads, error responses, rate limits, and effective request size; record bounded transport constants.
- [x] 1.6 Add a standalone schema/fixture conformance validator that runs without Android or desktop parsers and produces canonical pass/fail results for later client suites.

## 2. Build Desktop Bash Tooling

- [x] 2.1 Add `scripts/review-sync.sh` with a mutation-free Bash/Git/Python/IANA-timezone-data/yq/jq/hash version and configured-timezone preflight for Linux, modern macOS Bash, and Windows Git Bash.
- [x] 2.2 Implement `new` to create a valid current-date untracked Markdown draft with note/revision/device UUIDs and day-1 metadata.
- [x] 2.3 Implement `revise` to create a complete child draft, copy and verify all referenced assets, and leave the committed source unchanged.
- [x] 2.4 Implement `validate` with structured YAML/JSON parsing, schema checks, path checks, body and asset hashes, graph checks, asset limits, and dependency checks.
- [x] 2.5 Add Git diff guards that reject modified/deleted tracked data files, unresolved conflicts, path reuse, unknown staged paths, and history-rewriting workflows.
- [x] 2.6 Implement `pull` with clean-worktree enforcement and post-pull repository validation.
- [x] 2.7 Implement `publish` with pull, revalidation, exact diff display, user confirmation, approved-file staging, batch UUID commit trailer, and non-force push.
- [x] 2.8 Add ShellCheck and Bats coverage that runs the standalone conformance fixtures and covers success, Python/IANA timezone behavior, dependency failure, unsupported shells, mutation-free preflight failure, dirty worktree, invalid schemas, missing assets, remote advancement, declined confirmation, multiple same-day commits, and proof that PAT values never appear in arguments, output, files, or Git history.
- [x] 2.9 Document the Git/Typora workflow, supported shells, dependency installation, authentication, conflict recovery, and the required revise-before-edit rule.

## 3. Introduce Profile-Scoped Room Model

- [x] 3.1 Add UUID-based Profile, RemoteRepository, Note, NoteRevision, ReviewEvent, Asset, SyncOutbox, and SyncCheckpoint entities with foreign keys and profile indexes.
- [x] 3.2 Add a non-destructive Room migration that creates the new tables while retaining all legacy ReviewItem, ReviewLog, PlanItem, User, and image data.
- [x] 3.3 Replace unscoped business DAO contracts with profile-required queries and add transaction helpers for profile switching and projection updates.
- [x] 3.4 Implement repository/profile identity binding rules that prevent one repository ID from creating multiple independent local profiles or retargeting old outbox entries.
- [x] 3.5 Add a Keystore-backed credential store whose Room representation contains only a credential alias and whose logging interface redacts secrets.
- [x] 3.6 Implement the immutable local file cache with temp-file hashing, atomic rename, hash-to-path lookup, reference tracking, and orphan cleanup.
- [x] 3.7 Implement a profile-time service and inject it into repository date paths, review day boundaries, calendar projections, migration date mapping, and desktop-facing profile metadata instead of reading the device timezone directly.
- [ ] 3.8 Add migration, DAO-isolation, credential-rotation, cache-crash, concurrent-profile, and device-timezone-change tests.

## 4. Implement Markdown Revision and Event Domain

- [x] 4.1 Implement note/event serializers and validators that execute the standalone v1 conformance fixtures without reserializing committed Markdown bodies.
- [x] 4.2 Implement Markdown creation, profile-time date selection, body hashing, title projection, relative asset insertion, and local cache resolution.
- [x] 4.3 Implement note create, restart revision, and multi-parent merge operations with new UUID files and day-1 schedule reset.
- [x] 4.4 Implement revision graph projection for active, archived, content-conflicted, malformed-cycle, and missing-parent states.
- [x] 4.5 Implement causal review-event validation and projection using the repository's immutable algorithm ID/version/parameters and profile timezone, including unsupported-version quarantine and `REVIEW_CONFLICT` detection/resolution.
- [x] 4.6 Implement lifecycle delete tombstones, explicit restore, stale-event rejection, and conservative `DELETION_CONFLICT` projection.
- [x] 4.7 Ensure late review events for archived revisions remain visible in history without changing the active revision schedule.
- [ ] 4.8 Add deterministic replay tests proving that repeated and differently ordered file ingestion yields the same projection.

## 5. Add Markdown App Experience

- [x] 5.1 Replace plain review-content entry with a Markdown source editor and preview mode while preserving accessible title/list projections.
- [x] 5.2 Integrate photo selection with immediate hash/copy and insertion of relative Markdown image references at the editing position.
- [x] 5.3 Render Markdown and cached repository images in detail and review screens, including non-crashing missing-asset and repair states.
- [x] 5.4 Update list, due-review, calendar, history, trash, widget, and notification queries to use the active profile's projected revision and conflict/lifecycle state.
- [ ] 5.5 Add UI tests for text-only notes, mixed text/images, preview, missing assets, revision restart, and archived revisions excluded from due tasks.

## 6. Add Repository Configuration and Switching

- [x] 6.1 Add profile/repository management UI for creating, selecting, switching, unbinding, and importing repository-backed profiles.
- [x] 6.2 Add fields for owner/repository or URL, branch, fixed timezone, PAT input/replacement/clear, auto-sync, and Wi-Fi-only sync.
- [x] 6.3 Implement connection testing for repository/branch existence, profile/schema identity, and minimum read/write access without leaving a test file.
- [x] 6.4 Display last pull/push, remote commit SHA, pending outbox count, current sync state, sanitized errors, and manual-sync action.
- [x] 6.5 Cancel old profile observations and in-flight credential-bound requests during switching while retaining each profile's outbox and local projection.
- [ ] 6.6 Add UI and integration tests for mismatched profile binding, empty repository initialization, token rotation, unbinding with pending data, and profile isolation.

## 7. Implement Gitee Pull and Remote Validation

- [x] 7.1 Add an isolated Gitee transport client for repository identity, commits, contents, trees/blobs, and multi-file commits with HTTPS enforcement and credential redaction.
- [x] 7.2 Implement branch-head lookup and paginated commit traversal from head back to the exact SyncCheckpoint SHA.
- [x] 7.3 Download remote commit files into quarantine and validate identity, schemas, paths, hashes, graphs, and asset dependencies before projection changes.
- [x] 7.4 Apply each valid commit oldest-first in one Room transaction with its checkpoint advance and UUID/hash deduplication.
- [x] 7.5 Stop incremental pull when the checkpoint is unreachable and implement a full-tree validation/rebuild preview requiring user confirmation.
- [x] 7.6 Surface actionable sanitized errors for invalid files, missing assets, incompatible schema, permission failure, branch failure, and repository corruption.
- [ ] 7.7 Add fake-server/fixture tests for pagination, process restart during apply, duplicate files, invalid commits, force-pushed history, 401/403/404/409/429/5xx, and empty-device rebuild.

## 8. Implement Outbox Push and WorkManager

- [x] 8.1 Write domain changes and profile-scoped outbox operations in one Room transaction after immutable cache installation.
- [x] 8.2 Implement an outbox dependency planner that batches compatible create actions and confirms assets before publishing dependent Markdown when a batch must split.
- [x] 8.3 Persist batch UUIDs before network calls, call the Gitee multi-file commit endpoint, and verify returned commit SHA, paths, and hashes before acknowledgement.
- [x] 8.4 Implement ambiguous-success recovery by searching commit trailers for `Ebbinghaus-Batch-Id` and rejecting a reused ID with different contents.
- [x] 8.5 Implement remote-advancement recovery that pulls, validates, projects, detects domain conflicts, and retries only safe append-only actions.
- [x] 8.6 Add one unique serialized WorkManager job per profile with network/Wi-Fi constraints, bounded exponential backoff, and transient-versus-persistent failure classification.
- [x] 8.7 Trigger the same synchronization pipeline after local changes, App launch, profile switch, pull-to-refresh, and manual sync.
- [ ] 8.8 Add tests for offline capture, rapid batching, worker restart, commit-success/App-crash, duplicate retry, asset-first split, rate limiting, invalid token pause, and no force push.

## 9. Add Conflict Resolution Workflows

- [x] 9.1 Add App conflict states and review exclusion for divergent revision leaves, concurrent review-event leaves, and lifecycle-event leaves.
- [x] 9.2 Add Markdown comparison and multi-parent merge revision creation for content conflicts, restarting the merged revision at day 1.
- [x] 9.3 Add review-event conflict resolution that creates one `review_merge` event with all event leaves as parents and validates the selected resulting schedule.
- [x] 9.4 Add delete/restore conflict resolution that preserves tombstones, retains concurrent revisions, and creates an explicit lifecycle resolution event.
- [ ] 9.5 Add tests proving unresolved conflicts never create due-review tasks and resolutions preserve every branch in repository history.

## 10. Migrate and Rehearse Recovery

- [x] 10.1 Add an upgrade wizard that requires the user to choose one destination profile/repository for legacy global data.
- [x] 10.2 Convert each legacy ReviewItem to a stable note UUID and initial Markdown revision using its created time and selected profile timezone.
- [x] 10.3 Convert legacy `file://`, content URI, and imported-media images into verified SHA-256 assets and report unreadable files without deleting originals.
- [x] 10.4 Convert ReviewLog rows into UUID review events and map legacy action/stage data to the causal event schema.
- [x] 10.5 Compare replayed schedules with stored legacy stages/next-review values and require resolution of discrepancies before publication.
- [x] 10.6 Queue and publish migration data only after complete local conformance validation, then run an empty-projection repository restore rehearsal.
- [x] 10.7 Preserve legacy tables and images read-only through successful sync/restore; implement rollback to the untouched legacy projection before first publish.
- [ ] 10.8 Add migration tests for active, finished, trashed, image-rich, malformed-image, and review-history datasets plus interruption and idempotent rerun.

## 11. End-to-End Verification and Documentation

- [ ] 11.1 Run App-to-App synchronization tests for independent same-day additions, late offline additions, divergent note edits, concurrent reviews, and delete/revise races.
- [ ] 11.2 Run App-to-desktop tests where Bash/Typora commits are pulled into App and App REST commits are pulled and validated by the desktop tool.
- [ ] 11.3 Verify three repeated synchronization passes over the same commit range create no extra Room rows, outbox entries, or remote commits.
- [ ] 11.4 Verify a fresh device rebuild matches active revisions, archives, lifecycle, review stage, next-review time, event history, and asset hashes.
- [ ] 11.5 Run security checks proving PAT values are absent from Room data, repository files, Git history, command arguments, HTTP/debug logs, and crash diagnostics.
- [ ] 11.6 Measure large repository pull, Markdown render, image cache, and Room projection performance and document supported limits and cleanup behavior.
- [x] 11.7 Update project documentation for repository setup, profile switching, App synchronization, Bash/Typora use, conflict recovery, migration, rollback, and troubleshooting.
- [ ] 11.8 Run the complete Android unit/instrumentation suite, repository conformance suite, Bash ShellCheck/Bats suite, and Gitee sandbox contract suite before enabling migration for existing users.
