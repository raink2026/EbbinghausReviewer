## ADDED Requirements

### Requirement: Bash desktop entry point
Each initialized profile repository SHALL provide `scripts/review-sync.sh` as the supported desktop entry point for repository pull, draft creation, revision creation, validation, commit, and push. The Bash entry point SHALL use Python 3.9+ only as a non-interactive helper for IANA timezone conversion, UUID generation, and exact byte hashing. IANA timezone data SHALL come from the platform database or the Python `tzdata` package.

#### Scenario: Run on a supported environment
- **WHEN** the script runs under Bash 4+ with Git 2.30+, Python 3.9+, usable IANA timezone data, `yq` v4, `jq` 1.6+, and a supported SHA-256 command
- **THEN** it SHALL expose `pull`, `new`, `revise`, `validate`, and `publish` commands

#### Scenario: Required dependency is missing
- **WHEN** any command requires a missing or incompatible dependency
- **THEN** the script SHALL exit nonzero with the dependency name and SHALL not modify Git state

#### Scenario: Configured IANA timezone cannot be loaded
- **WHEN** preflight cannot resolve the profile's configured timezone because the runtime has no usable IANA database or the timezone key is invalid
- **THEN** the script SHALL exit nonzero before repository mutation, identify the configured timezone, and direct database-less Python installations to install `tzdata`

### Requirement: Safe pull requires a clean worktree
The `pull` command SHALL refuse to integrate remote changes into a worktree with uncommitted user changes or unresolved conflicts.

#### Scenario: Pull a clean repository
- **WHEN** the worktree is clean and the configured branch can fast-forward or rebase safely
- **THEN** `pull` SHALL retrieve remote commits and SHALL run repository validation before reporting success

#### Scenario: Pull a dirty repository
- **WHEN** the worktree contains uncommitted changes
- **THEN** `pull` SHALL exit nonzero without stashing, discarding, committing, or overwriting those changes

### Requirement: New command creates a valid daily draft
The `new` command SHALL create an untracked Markdown draft under the current profile-date directory with new note and revision UUIDs and valid initial front matter.

#### Scenario: Create a desktop note
- **WHEN** the user runs `new`
- **THEN** the script SHALL create the required date and notes directories, initialize a day-1 schedule revision, and print the draft path for opening in Typora

### Requirement: Revise command never edits history
The `revise` command SHALL create a complete untracked child revision under the current profile-date directory and SHALL leave the source committed revision unchanged.

#### Scenario: Revise a historical note
- **WHEN** the user passes a valid committed revision to `revise`
- **THEN** the script SHALL preserve its logical note UUID, create a new revision UUID, name the source as parent, copy all referenced assets into the current date directory, reset the learning start time, and print the new draft path

#### Scenario: Revise an invalid or conflicted source
- **WHEN** the source revision is missing, invalid, deleted, or not the uniquely active leaf
- **THEN** the script SHALL exit nonzero and SHALL require explicit conflict or restore handling

### Requirement: Structured repository validation
The `validate` command MUST parse YAML with `yq`, parse JSON with `jq`, and verify control schemas, date paths, UUIDs, revision and event graphs, UTF-8/LF rules, Markdown body hashes, relative image references, asset existence, asset hashes, and asset limits.

#### Scenario: Repository is valid
- **WHEN** all tracked data and untracked drafts satisfy the supported schemas and graph invariants
- **THEN** `validate` SHALL exit zero and SHALL report the set of publishable new files

#### Scenario: Validation fails
- **WHEN** any file violates a schema, hash, path, graph, immutability, or dependency rule
- **THEN** `validate` SHALL exit nonzero with every detected file path and reason and SHALL not stage, commit, or push

### Requirement: Published data changes are append-only
The desktop tool SHALL reject publication if any tracked note, asset, or event is modified or deleted.

#### Scenario: User edits a committed Markdown file directly
- **WHEN** `validate` or `publish` detects the modification
- **THEN** it SHALL instruct the user to restore the tracked file and run `revise` to create a new draft

#### Scenario: New files share an existing path
- **WHEN** a publishable path already exists in Git history
- **THEN** publication SHALL stop unless the bytes are identical and no new commit action is required

### Requirement: Publish pulls, revalidates, confirms, commits, and pushes
The `publish` command SHALL perform a safe pull, rerun complete validation, show the exact diff and file set, request user confirmation, commit only approved new files, and push without force.

#### Scenario: Successful publication
- **WHEN** the worktree has valid new data, pull succeeds, revalidation succeeds, and the user confirms
- **THEN** `publish` SHALL create one ordinary commit with a date/device message and batch UUID trailer and SHALL push it to the configured branch

#### Scenario: User declines confirmation
- **WHEN** the user declines the displayed publication set
- **THEN** `publish` SHALL not stage, commit, or push any file

#### Scenario: Remote advances before push
- **WHEN** the push is rejected because another client advanced the branch
- **THEN** the script SHALL preserve the local commit, stop without force-pushing, and require pull, validation, and retry

### Requirement: Multiple daily commits are valid
The desktop tool SHALL permit multiple valid ordinary commits to the same date directory and SHALL not enforce a one-commit-per-day rule.

#### Scenario: Publish another revision on the same date
- **WHEN** earlier commits already added files under the current date directory
- **THEN** a later `publish` SHALL add only the new immutable files in another ordinary commit

### Requirement: Desktop credentials remain outside the script
The Bash tool MUST use the user's configured Git credential helper or SSH configuration and MUST NOT accept, print, persist, or commit a Gitee personal access token.

#### Scenario: Git authentication is unavailable
- **WHEN** pull or push cannot authenticate through the user's Git configuration
- **THEN** the script SHALL exit nonzero with a sanitized Git error and SHALL not request an App token as a command-line argument

### Requirement: Supported desktop platforms are explicit
Version 1 of the desktop workflow SHALL support Linux, macOS with Bash 4+, and Windows through Git for Windows Git Bash, with Python 3.9+ plus the platform timezone database or Python `tzdata` providing consistent IANA timezone behavior on every platform.

#### Scenario: Run from an unsupported shell
- **WHEN** the entry point detects PowerShell, `cmd.exe`, an older Bash, or incompatible utility versions
- **THEN** it SHALL stop before repository mutation and SHALL identify a supported execution environment
