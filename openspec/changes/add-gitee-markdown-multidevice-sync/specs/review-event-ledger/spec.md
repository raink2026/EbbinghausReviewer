## ADDED Requirements

### Requirement: Immutable causal event format
Every review or lifecycle action SHALL be stored as a new immutable JSON event with schema version, event UUID, stream ID, parent event UUIDs, source device UUID, occurrence time with offset, algorithm ID/version, and a typed payload. Review payloads SHALL contain result, stage-before, stage-after, and next-review values; lifecycle payloads SHALL contain target note/revision IDs and the delete/restore ancestry needed to validate the transition.

#### Scenario: Publish a review action
- **WHEN** a user records a remember or forget result
- **THEN** the system SHALL create a uniquely named event file in the action date's `events/` directory without modifying earlier events

#### Scenario: Receive the same event repeatedly
- **WHEN** synchronization imports an event UUID already stored locally with identical bytes
- **THEN** the system SHALL treat the event as already applied and SHALL NOT duplicate review history or schedule transitions

#### Scenario: Receive a reused event UUID with different bytes
- **WHEN** synchronization finds an existing event UUID whose content differs from the incoming event
- **THEN** the system SHALL report repository corruption and SHALL NOT apply the incoming event

### Requirement: Causal review replay
The system SHALL derive review state from `parent_event_ids` in the `review:<revision-id>` stream and SHALL NOT use wall-clock order to resolve concurrent events.

#### Scenario: Apply one causal successor
- **WHEN** a valid review event references the current event leaf as its only parent
- **THEN** the system SHALL validate the stage transition and make the new event the current schedule leaf

#### Scenario: Ignore timestamp ordering for causality
- **WHEN** an event has a later `occurred_at` value but is not causally descended from another event
- **THEN** the system SHALL NOT overwrite the other event solely because of the timestamp

### Requirement: Review event conflicts pause scheduling
The system SHALL mark a revision `REVIEW_CONFLICT` when its valid review event graph has multiple leaves.

#### Scenario: Two devices review from the same state
- **WHEN** two review events reference the same prior event and neither descends from the other
- **THEN** both events SHALL be preserved and the revision SHALL produce no automatic review task until resolved

#### Scenario: Resolve a review conflict
- **WHEN** the user creates a `review_merge` event naming every conflicting event leaf as a parent and selecting a valid resulting schedule state
- **THEN** that merge event SHALL become the single schedule leaf and automatic review scheduling MAY resume

### Requirement: Review transition validation
The repository profile SHALL declare an immutable Ebbinghaus algorithm ID, version, parameters, and timezone, and the system SHALL use that definition to recalculate and validate every event's declared stage-before, stage-after, and next-review values.

#### Scenario: Receive an invalid transition
- **WHEN** an event declares a stage transition or next-review value that the algorithm cannot produce from its parent state
- **THEN** the event SHALL be quarantined, the prior valid state SHALL remain active, and synchronization SHALL expose an actionable error

#### Scenario: Receive an unsupported algorithm version
- **WHEN** an event or repository profile references a review algorithm version the client cannot execute
- **THEN** the client SHALL preserve and quarantine the affected stream and SHALL NOT infer a schedule with a different algorithm

### Requirement: Delete tombstones prevent resurrection
Deleting a logical note SHALL append a `delete` event to `lifecycle:<note-id>` and SHALL retain all note revisions and prior events for audit.

#### Scenario: Apply a valid delete
- **WHEN** a delete event descends from the current lifecycle leaf and identifies the currently visible revision leaves
- **THEN** the note SHALL be hidden from active lists and SHALL produce no automatic review tasks

#### Scenario: Receive an old revision after delete
- **WHEN** a previously offline device later publishes a revision that does not causally restore the delete event
- **THEN** the revision SHALL be retained but SHALL NOT reactivate the note

### Requirement: Restore explicitly references deletion
A restore action SHALL append a lifecycle event that names the delete event it resolves and the revision that becomes visible.

#### Scenario: Restore a deleted note
- **WHEN** the user restores a note from the current delete leaf
- **THEN** the restore event SHALL preserve the tombstone in history and SHALL reactivate only the explicitly selected revision

#### Scenario: Reject stale restore
- **WHEN** a restore event does not descend from the current delete leaf
- **THEN** the event SHALL NOT reactivate the note and SHALL participate in lifecycle conflict detection

### Requirement: Lifecycle conflicts use a conservative state
The system SHALL mark a note `DELETION_CONFLICT` when its lifecycle event graph has multiple unresolved leaves and SHALL default the note to hidden and unscheduled.

#### Scenario: Delete conflicts with revision or restore
- **WHEN** a delete event is concurrent with a revision or restore action from another device
- **THEN** the system SHALL retain every input, keep the note out of automatic review, and require an explicit lifecycle resolution event

### Requirement: Repository replay reconstructs review state
The system SHALL be able to rebuild archived, active, deleted, conflicted, review-stage, and next-review projections from validated revision and event graphs without relying on device-local auto-increment IDs.

#### Scenario: Restore on an empty device
- **WHEN** an empty compatible client imports a valid profile repository
- **THEN** its projected note lifecycle and review schedules SHALL match another client that has applied the same repository commit
