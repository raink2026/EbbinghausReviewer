## Why

The current Room-and-ZIP storage model cannot provide incremental multi-device synchronization, Git-readable notes, or safe conflict handling. A repository-backed Markdown format is needed so Android remains offline-capable while Gitee and desktop Git/Typora workflows share the same auditable data.

## What Changes

- Bind each user profile to one Gitee repository and expose repository, branch, credential, sync-status, and repository-switching controls in the App; switching repositories selects or imports the corresponding profile binding and never retargets an existing profile's pending outbox.
- Store review content as immutable Markdown revision snapshots with relative, content-addressed image assets under one `YYYY-MM-DD/` directory per day.
- Archive an old revision when it is replaced, create a complete new revision on the modification date, and restart that revision's Ebbinghaus schedule from day 1.
- Represent review, delete, restore, and conflict-resolution actions as immutable causal events that can be replayed across devices.
- Add local-first synchronization with a transactional Room outbox, WorkManager retries, SHA-based pull checkpoints, idempotent Gitee batch commits, and explicit conflict states.
- Allow multiple ordinary commits to the same daily directory; never force-push or rewrite shared history.
- Provide a Bash-based desktop workflow for Git pull, draft creation, historical-note revision, schema and asset validation, commit, and push while Typora remains the editor and reader.
- Add migration and recovery paths from the existing Room entities, `file://` image paths, and review logs into UUID-based repository data.
- **BREAKING** Replace auto-increment database IDs and mutable ReviewItem content as synchronization identities with profile-scoped UUID notes, immutable revisions, assets, causal events, and checkpoints.
- **BREAKING** Stop treating direct edits or deletions of committed repository data files as valid updates; all published changes become new immutable files.

## Capabilities

### New Capabilities

- `profile-repository-binding`: Profile-level data isolation, one-profile/one-repository binding, Gitee token configuration, binding-aware repository/profile switching, and sync-status controls.
- `markdown-note-revisions`: Markdown snapshots, daily repository layout, relative content-addressed assets, immutable revision graphs, archive semantics, and day-1 schedule restart.
- `review-event-ledger`: Immutable causal review and lifecycle events, deterministic replay, delete/restore tombstones, and conflict states.
- `gitee-multidevice-sync`: Local-first outbox synchronization, incremental pull checkpoints, Gitee batch commits, idempotency, retries, migration, and recovery.
- `desktop-sync-tooling`: Bash and Git workflow for Typora-compatible drafting, revision creation, validation, safe commits, and pushes.

### Modified Capabilities

None. The project does not yet contain main OpenSpec capability specifications; the change establishes the initial contracts for the affected behavior.

## Impact

- **Data layer:** Room entities, migrations, DAOs, repositories, image storage, and backup/import boundaries.
- **Domain layer:** Note identity, revision and archive rules, Ebbinghaus schedule derivation, review history, deletion, restoration, and conflict resolution.
- **Android UI:** Markdown editing/rendering, profile and repository configuration, repository switching, sync status, manual sync, and conflict handling.
- **Background work:** WorkManager scheduling, Gitee REST access, secure credential lookup, retry policy, and local/remote checkpoints.
- **Repository format:** Versioned Markdown/front matter, JSON events, SHA-256 assets, profile/schema control files, and daily directories.
- **Desktop tooling:** `scripts/review-sync.sh` plus documented Bash, Git, Python 3.9+, IANA timezone data (the platform database or the Python `tzdata` package), `yq`, and `jq` prerequisites. Python provides portable IANA timezone conversion, UUID generation, and byte hashing while Bash remains the user-facing workflow.
- **External system:** Gitee private repositories and the Gitee v5 REST API, including multi-file commit operations.
- **Security:** Android Keystore-backed token storage, credential redaction, HTTPS-only access, and desktop Git credential management.
