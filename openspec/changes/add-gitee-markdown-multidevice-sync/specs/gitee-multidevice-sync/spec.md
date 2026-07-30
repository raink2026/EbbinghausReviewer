## ADDED Requirements

### Requirement: Local-first transactional change capture
The App SHALL complete user-visible note and review operations using local storage without waiting for Gitee and SHALL insert the corresponding profile-scoped SyncOutbox records in the same Room transaction as the domain change.

#### Scenario: Save while offline
- **WHEN** the user saves a valid change without network connectivity
- **THEN** the App SHALL commit the local domain state and outbox entry, update the UI, and leave the operation pending for later synchronization

#### Scenario: Room transaction fails
- **WHEN** the Room transaction that writes a domain change and outbox entry fails
- **THEN** neither the domain change nor its outbox entry SHALL become visible

#### Scenario: Cache write succeeds but transaction fails
- **WHEN** immutable cache files are installed but their Room transaction fails
- **THEN** startup cleanup SHALL be able to remove those unreferenced files without losing a visible domain change

### Requirement: Prompt serialized background synchronization
The App SHALL enqueue or wake one unique WorkManager synchronization job for the affected profile after a local change and SHALL serialize all synchronization execution for that profile.

#### Scenario: Multiple rapid local changes
- **WHEN** several changes are saved before the synchronization worker starts
- **THEN** the App SHALL use one profile job that MAY batch compatible outbox entries rather than run concurrent profile synchronizers

#### Scenario: Connectivity returns
- **WHEN** pending outbox entries exist and WorkManager network constraints become satisfied
- **THEN** the profile synchronization job SHALL attempt pull, merge, push, remote commit verification, and local outbox acknowledgement without requiring user interaction

#### Scenario: Manually request synchronization
- **WHEN** the user taps manual synchronization
- **THEN** the App SHALL wake the same serialized profile synchronization pipeline and SHALL display its progress and result

### Requirement: SHA-based incremental pull
The system SHALL use the last fully applied remote commit SHA as the profile's pull checkpoint and SHALL enumerate paginated branch history until that checkpoint is found.

#### Scenario: Pull several remote commits
- **WHEN** the remote branch contains multiple commits after the local checkpoint
- **THEN** the App SHALL download, validate, and apply them from oldest to newest before advancing the checkpoint

#### Scenario: Commit history spans pages
- **WHEN** the checkpoint is older than the first API response page
- **THEN** the App SHALL continue pagination until the checkpoint or repository root is reached

#### Scenario: Checkpoint is unreachable
- **WHEN** remote history was rewritten and the stored checkpoint cannot be found
- **THEN** incremental synchronization SHALL stop and the App SHALL require a full-tree validation and rebuild preview before changing the active projection

### Requirement: Remote data is quarantined before application
The App SHALL download remote files into an isolated area and SHALL validate repository identity, schema versions, paths, hashes, revision graphs, event graphs, and asset dependencies before updating active Room state.

#### Scenario: Commit contains an invalid file
- **WHEN** any required file in a remote commit fails validation
- **THEN** the App SHALL not advance the checkpoint past that commit, SHALL keep the last valid projection active, and SHALL report the exact invalid path and reason

#### Scenario: Commit applies successfully
- **WHEN** every file in a commit validates
- **THEN** the App SHALL apply the files and advance the commit checkpoint atomically and SHALL be safe to repeat that application after a process restart

### Requirement: Atomic Gitee multi-file publication
The App SHALL publish compatible pending files through the Gitee v5 multi-file commit endpoint using one normal commit per synchronization batch and SHALL allow multiple commits in one date directory.

#### Scenario: Publish a note package
- **WHEN** a pending Markdown revision and all referenced assets fit one configured request batch
- **THEN** the App SHALL submit create actions for the revision and assets in one commit

#### Scenario: Assets exceed one request batch
- **WHEN** a note package exceeds the configured request batch size
- **THEN** the App SHALL publish and verify asset-only batches first and SHALL publish the Markdown revision only after all referenced assets exist remotely

#### Scenario: Remote path already exists
- **WHEN** a create action's target path already exists remotely
- **THEN** the App SHALL compare content hashes, treat identical content as already present, and stop with a corruption or collision error if the bytes differ

### Requirement: Push batches are idempotent
The system SHALL persist a unique batch UUID before sending a Gitee commit request, include it in an `Ebbinghaus-Batch-Id` commit trailer, and acknowledge outbox entries only after verifying the remote commit SHA, paths, and hashes.

#### Scenario: Response is lost after remote commit
- **WHEN** Gitee creates the commit but the App times out or terminates before local acknowledgement
- **THEN** the next synchronization SHALL find and verify the existing batch commit and SHALL not create a duplicate commit

#### Scenario: Batch ID content differs
- **WHEN** remote history contains the pending batch UUID with paths or hashes different from the local batch
- **THEN** the App SHALL stop publication, retain the outbox, and report repository corruption

### Requirement: Remote advancement is merged before retry
The App SHALL pull and apply remote advancement before retrying a push that conflicts with the current branch state.

#### Scenario: Desktop pushes during App synchronization
- **WHEN** a desktop commit advances the branch after the App's pull but before its push
- **THEN** the App SHALL fetch the new head, validate and merge append-only files, detect domain conflicts, and retry only safe local additions

### Requirement: Retry policy distinguishes transient and persistent failures
The synchronization worker SHALL use exponential backoff for network failures, Gitee rate limiting, and server errors, and SHALL stop automatic retries for credential, repository, branch, schema, or corruption errors that require user action.

#### Scenario: Rate limited by Gitee
- **WHEN** Gitee returns a rate-limit response
- **THEN** the worker SHALL preserve the outbox, honor server retry information when present, and retry with bounded exponential backoff

#### Scenario: Token is invalid
- **WHEN** Gitee rejects the configured personal access token
- **THEN** automatic retries SHALL pause for that profile and the configuration page SHALL request credential repair

### Requirement: Synchronization never rewrites shared history
The App MUST NOT force-push, amend, or delete commits already shared through the profile repository.

#### Scenario: An operation would require history rewriting
- **WHEN** synchronization cannot publish a local change without replacing shared history
- **THEN** the App SHALL preserve the local change, report a conflict or recovery requirement, and SHALL not perform the rewrite

### Requirement: Legacy migration is explicit and reversible
The system SHALL migrate legacy ReviewItem, image, and ReviewLog data only after the user selects one destination profile and SHALL retain read-only legacy data until a repository restore rehearsal succeeds.

#### Scenario: Legacy data has no user ownership
- **WHEN** upgrade finds global review data that cannot be attributed to existing UI users
- **THEN** the App SHALL ask the user to choose one destination profile and SHALL not infer ownership automatically

#### Scenario: Migration validation fails
- **WHEN** converted Markdown, assets, events, or replayed schedules fail validation
- **THEN** the App SHALL not queue remote publication and SHALL keep the legacy database and images available for rollback

#### Scenario: Restore rehearsal succeeds
- **WHEN** an empty compatible projection rebuilt from the published repository matches migrated notes, assets, lifecycle, and schedules
- **THEN** the App MAY mark migration complete but SHALL defer destructive legacy cleanup to a separately reversible change
