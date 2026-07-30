# Desktop Git and Typora Workflow

This document describes the supported desktop workflow for one profile repository. The
repository is an append-only Markdown ledger. Typora is the reader/editor, while
`scripts/review-sync.sh` is the only supported publish entry point.

## Prerequisites

Version 1 supports:

- Linux with Bash 4 or newer;
- macOS with Bash 4 or newer (install a modern Bash if the system Bash is older); and
- Windows through Git for Windows Git Bash.

Install these commands before using the script:

- Git 2.30 or newer;
- Python 3.9 or newer;
- usable IANA timezone data, either from the platform or the Python `tzdata` package;
- `yq` version 4;
- `jq` version 1.6 or newer; and
- a SHA-256 command (`sha256sum` or `shasum --algorithm 256`).

The Python dependency is kept in `tools/requirements-desktop.txt`:

```bash
python3 -m pip install -r tools/requirements-desktop.txt
```

On Windows, run the command from Git Bash and use `python` when `python3` is not an
available command. Do not run the entry point from PowerShell or `cmd.exe`; start Git
Bash first. A failed preflight must leave the worktree and Git index unchanged.

## Clone and line endings

Clone with Git's automatic line-ending conversion disabled, then set the repository-local
value explicitly:

```bash
git -c core.autocrlf=false clone <repository-url> profile-repository
cd profile-repository
git config core.autocrlf false
```

All committed Markdown and JSON files use UTF-8 without a BOM and LF line endings. Do
not enable a global or local filter that rewrites repository data. Run `./scripts/review-sync.sh
preflight` before editing when setting up a new machine.

## Read, create, and revise notes

Any committed Markdown file can be opened in Typora for reading. A committed snapshot is
immutable and must not be edited in place. To change an existing note:

```bash
./scripts/review-sync.sh revise 2026-07-20/notes/<committed-revision-uuid>.md
```

The command verifies the source, creates a complete child snapshot under the current
profile date, copies and verifies referenced assets, and resets the learning schedule to
day 1. Open the printed path in Typora and edit that new untracked file. Repeated saves
to this draft are local only until publication.

To create a new note, use:

```bash
./scripts/review-sync.sh new
```

Images in Markdown must use the repository-relative form
`../assets/<sha256>.<extension>`. Do not replace a content-addressed asset or manually
copy an image into a different date directory.

## Validate and publish

Validate before asking Git to stage anything:

```bash
./scripts/review-sync.sh validate
```

Validation checks profile and file schemas, real calendar dates in the profile timezone,
UUIDs, revision/event graphs, every asset path/hash/size (including unreferenced assets),
UTF-8/LF encoding, body hashes, append-only history, and the Git worktree state.

Publish is an explicit, interactive operation:

```bash
./scripts/review-sync.sh publish
```

The command pulls with `--ff-only`, validates again, prints the exact new-file diffs,
asks for confirmation, stages only approved immutable paths, creates one ordinary commit,
and pushes without force. The commit includes the date/device subject and an
`Ebbinghaus-Batch-Id` trailer. A date directory may have any number of ordinary commits;
there is still only one directory per profile-local date.

If the user declines, no file is staged and no commit or push is performed. If push is
rejected because the remote advanced, the local commit is retained. Run `pull`, resolve
any validation or content conflict, then run `publish` again; never force-push.

## Authentication and token handling

Desktop Git authentication is provided by the user's Git credential helper or SSH
configuration. The desktop script does not accept a Gitee personal access token, does not
write one to a file, and does not print one. Configure or rotate the token in the App's
profile configuration page, not in a shell command or repository file. Inspect remote URLs
and Git trace output before sharing diagnostics, because Git helpers can expose their own
configuration errors.

Before pull or publish, the script requires HTTPS without URL userinfo/token parameters or
SSH. Credential-bearing HTTPS remotes are rejected. Configure credentials through a Git
credential helper instead of embedding them in the remote URL.

## Conflict and recovery rules

- A dirty worktree is a hard stop. Save or discard the user's own draft deliberately,
  then rerun `pull` or `validate`; the script never stashes or overwrites files.
- `publish` accepts only untracked note, event, and asset paths from the repository
  protocol. Any other untracked file is a hard stop and must be handled explicitly.
- A modified or deleted committed note, asset, or event is invalid. Restore the tracked
  bytes from Git and run `revise` to create a new snapshot.
- A divergent revision, review-event, or lifecycle leaf remains in history and pauses
  automatic review. Resolve it in the App by creating the appropriate merge/resolution
  revision or event, then pull and validate on the desktop.
- An unresolved Git merge, rebase, cherry-pick, or revert stops every command that could
  publish. Finish or abort that operation using normal Git commands, verify the worktree,
  and rerun `validate`.
- A missing or mismatched asset is repaired by restoring the exact SHA-addressed bytes;
  do not rename a different image to satisfy the reference.
- If preflight reports missing IANA data, install `tzdata` and rerun it. If it reports an
  invalid profile timezone, correct the profile configuration through the supported App
  workflow before creating a new draft.

## Quality gate

When Bats and ShellCheck are installed, run the complete desktop quality gate from the
repository root:

```bash
./scripts/test-desktop-sync.sh
```

This runs ShellCheck on the Bash entry points, executes the standalone v1 repository
fixtures, and runs the integration cases for dependency failures, timezone behavior,
dirty worktrees, schemas/assets, remote advancement, declined publication, multiple
same-day commits, and PAT non-disclosure.
