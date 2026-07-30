## ADDED Requirements

### Requirement: Profile-scoped data isolation
The system SHALL scope every note, revision, event, asset reference, synchronization outbox entry, and synchronization checkpoint to exactly one profile.

#### Scenario: Switch active profile
- **WHEN** the user switches from profile A to profile B
- **THEN** the App SHALL stop observing profile A, preserve profile A's pending outbox, and display only profile B's projected data and synchronization state

#### Scenario: Background synchronization after profile switch
- **WHEN** profile A has pending changes and profile B becomes active
- **THEN** profile A's synchronization job MAY continue under profile A's repository binding but SHALL NOT read or write profile B's Room rows or credentials

### Requirement: One repository identity per profile
The system SHALL bind a profile to one repository identity declared by `.ebbinghaus/profile.json`, including stable repository and profile UUIDs, schema major version, profile timezone, and immutable review-algorithm ID, version, and parameters.

#### Scenario: Bind an existing initialized repository
- **WHEN** the user enters a repository whose `.ebbinghaus/profile.json` is valid
- **THEN** the App SHALL import or select the matching profile identity rather than generate a different local profile for that repository

#### Scenario: Reject mismatched profile binding
- **WHEN** a local profile ID differs from the profile ID declared by the selected initialized repository
- **THEN** the App SHALL reject silent rebinding and SHALL offer to switch to or import the repository's declared profile

#### Scenario: Initialize an empty repository
- **WHEN** the user binds an empty writable repository to a new profile
- **THEN** the App SHALL create the repository control files with new stable repository and profile UUIDs before publishing note data

### Requirement: Repository configuration page
The App SHALL provide a profile-level repository configuration page containing repository owner/name or URL, target branch, fixed timezone, personal access token management, automatic synchronization, Wi-Fi-only synchronization, connection testing, manual synchronization, and repository switching.

#### Scenario: Display synchronization health
- **WHEN** the user opens repository configuration
- **THEN** the page SHALL show the last successful pull and push times, current remote commit SHA, pending outbox count, active sync state, and the latest actionable error

#### Scenario: Test repository connection
- **WHEN** the user requests a connection test
- **THEN** the App SHALL validate repository existence, target branch access, schema compatibility, and required read/write permissions without leaving a permanent test file

### Requirement: Secure personal access token handling
The App MUST store the Gitee personal access token in Android Keystore-backed secure storage and MUST keep only a credential alias in ordinary profile data.

#### Scenario: Persist a token
- **WHEN** the user saves a valid personal access token
- **THEN** the App SHALL store the secret outside ordinary Room columns and SHALL mask it in subsequent UI rendering

#### Scenario: Replace or clear a token
- **WHEN** the user replaces or clears a token while synchronization is running
- **THEN** the App SHALL cancel that profile's in-flight requests, preserve its outbox, and resume only after replacement credentials validate successfully

#### Scenario: Emit diagnostic information
- **WHEN** networking, crash reporting, or synchronization logging records a request failure
- **THEN** the system MUST redact the personal access token from URLs, headers, bodies, logs, and user-visible diagnostics

### Requirement: Repository switching preserves pending data
The system SHALL treat changing repository identity as importing or selecting the different profile bound to that repository and SHALL NOT mutate the current profile's repository identity or retarget its pending operations.

#### Scenario: Switch with pending changes
- **WHEN** the user switches away from a profile that has pending outbox entries
- **THEN** the entries SHALL remain associated with the old profile and repository until that profile is synchronized again

#### Scenario: Unbind while retaining local data
- **WHEN** the user chooses to unbind a repository without deleting the profile
- **THEN** the App SHALL retain local notes and pending operations, stop remote synchronization, and require an explicit migration workflow before binding those operations to a different repository

### Requirement: Fixed profile timezone
The system SHALL calculate repository date directories and review calendar dates using the profile's configured timezone rather than the current device timezone.

#### Scenario: Device timezone changes
- **WHEN** the device timezone changes while the profile timezone remains unchanged
- **THEN** new repository files and schedule day boundaries SHALL continue to use the profile timezone
