## ADDED Requirements

### Requirement: Daily repository layout
The repository SHALL store synchronized data under exactly one top-level `YYYY-MM-DD/` directory for each profile-local date, with `notes/`, `assets/`, and `events/` subdirectories as needed.

#### Scenario: Multiple changes on one date
- **WHEN** multiple App or desktop changes occur on the same profile-local date
- **THEN** all files SHALL be added beneath the same date directory and MAY be published through multiple ordinary commits

#### Scenario: Late offline publication
- **WHEN** a device publishes a previously unsynchronized revision created on an earlier date
- **THEN** the device SHALL append uniquely named files to that revision's original date directory without modifying existing files

### Requirement: Immutable published data files
The system MUST treat every committed note, asset, and event file as immutable and MUST represent later changes as newly named files.

#### Scenario: Edit a committed note
- **WHEN** a user changes content from a committed Markdown revision
- **THEN** the system SHALL create a new revision file and SHALL leave the committed source file byte-for-byte unchanged

#### Scenario: Detect an in-place repository edit
- **WHEN** validation finds a tracked note, asset, or event file modified or deleted
- **THEN** validation SHALL fail and SHALL require restoration of the tracked file before publication

### Requirement: Versioned Markdown snapshot format
Each note revision SHALL be a complete UTF-8, LF-terminated Markdown document with YAML front matter conforming to the repository's supported note schema.

#### Scenario: Validate required revision metadata
- **WHEN** a note revision is imported or published
- **THEN** validation SHALL require canonical note UUID, revision UUID, parent revision UUIDs, revision kind, authored time with offset, learning start time with offset, source device UUID, and body SHA-256

#### Scenario: Reject a hash mismatch
- **WHEN** the SHA-256 of the bytes after the front matter delimiter differs from `content_sha256`
- **THEN** the revision SHALL be quarantined and SHALL NOT update the active Room projection

#### Scenario: Encounter a newer incompatible schema
- **WHEN** a note declares an unsupported schema major version
- **THEN** the system SHALL preserve the file, stop applying it, and report a schema compatibility error

### Requirement: Stable note and revision identities
The system SHALL use globally unique UUIDs for logical notes and immutable revisions and SHALL NOT use Room auto-increment IDs or file paths as synchronization identities.

#### Scenario: Import revisions from separate devices
- **WHEN** two devices create different revisions without coordination
- **THEN** their revision file paths SHALL remain distinct and both revisions SHALL be retained

### Requirement: Content revision restarts review
Changing a note's content SHALL create a complete child revision on the modification date and SHALL restart that new revision's Ebbinghaus schedule from day 1.

#### Scenario: Revise an active note
- **WHEN** the user revises an active note
- **THEN** the new revision SHALL keep the logical note UUID, use a new revision UUID, name the prior revision as its parent, set `learning_started_at` to the revision time, and start at the initial review stage

#### Scenario: Review old revision after replacement
- **WHEN** a prior revision has a valid child revision
- **THEN** the prior revision SHALL be archived and SHALL NOT appear in automatic review tasks

### Requirement: Revision graph determines current state
The system SHALL derive active, archived, and conflicted revision state from the revision parent graph.

#### Scenario: One revision leaf
- **WHEN** a logical note's valid revision graph has exactly one leaf and its lifecycle is active
- **THEN** that leaf SHALL be the active revision

#### Scenario: Divergent revision leaves
- **WHEN** two revisions name the same parent and neither has a valid child that resolves both
- **THEN** the logical note SHALL enter content-conflict state, retain both leaves, and produce no automatic review task

#### Scenario: Merge divergent revisions
- **WHEN** the user creates a complete merge revision naming every conflicting leaf as a parent
- **THEN** the merge revision SHALL become the only active leaf and SHALL restart review from day 1

### Requirement: Relative content-addressed assets
Every synchronized Markdown image SHALL use a relative reference to a SHA-256-named file in the same date directory's `assets/` directory.

#### Scenario: Add an image in the App
- **WHEN** the user inserts an image into a Markdown note
- **THEN** the App SHALL hash the original bytes, store an immutable local cache object, create the date asset path, and insert a relative Markdown reference

#### Scenario: Create a new revision with old images
- **WHEN** a new revision continues to reference images from an earlier revision
- **THEN** every referenced image SHALL also be available under the new revision date's `assets/` directory

#### Scenario: Validate a remote asset
- **WHEN** an asset is imported or reused from the remote repository
- **THEN** the system SHALL verify that its bytes match the SHA-256 in its filename before making a dependent revision active

#### Scenario: Reject an oversized asset
- **WHEN** an individual image exceeds the repository's 10 MiB v1 asset limit
- **THEN** publication SHALL stop and SHALL ask the user to compress or replace the image without silently changing its quality

### Requirement: Markdown editing and rendering
The App SHALL edit Markdown source, render Markdown for review, and resolve repository image references through the local content-addressed cache.

#### Scenario: Insert a selected image
- **WHEN** the user selects an image while editing Markdown
- **THEN** the App SHALL copy and hash the image immediately and insert the generated relative reference at the current editing position

#### Scenario: Render with a missing local asset
- **WHEN** Markdown references a valid remote asset that is not yet cached locally
- **THEN** the review UI SHALL show a non-crashing missing-asset state and SHALL allow synchronization to repair the cache
