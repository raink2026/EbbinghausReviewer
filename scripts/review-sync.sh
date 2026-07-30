#!/usr/bin/env bash

if [ -z "${BASH_VERSION:-}" ]; then
  printf 'Error: Unsupported shell; use Bash 4 or newer\n' >&2
  exit 1
fi

set -euo pipefail

readonly MIN_BASH_MAJOR=4
readonly MIN_GIT_MAJOR=2
readonly MIN_GIT_MINOR=30
PYTHON_BIN=

fail() {
  printf 'Error: %s\n' "$*" >&2
  exit 1
}

require_command() {
  local name=$1
  command -v "$name" >/dev/null 2>&1 || fail "Missing required command: $name"
}

version_at_least() {
  local major=$1
  local minor=$2
  local required_major=$3
  local required_minor=$4
  (( major > required_major || (major == required_major && minor >= required_minor) ))
}

require_supported_bash() {
  local major=$1
  (( major >= MIN_BASH_MAJOR )) || fail "Unsupported shell; Bash $MIN_BASH_MAJOR or newer is required"
}

check_python_timezone() {
  local profile_path=
  if [[ -f .ebbinghaus/profile.json ]]; then
    profile_path=.ebbinghaus/profile.json
  fi

  if ! "$PYTHON_BIN" - "$profile_path" <<'PY'
import json
import sys
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

profile_path = sys.argv[1]
timezone = "Etc/UTC"
if profile_path:
    try:
        with open(profile_path, encoding="utf-8") as profile_file:
            profile = json.load(profile_file)
    except (OSError, json.JSONDecodeError) as exc:
        raise SystemExit(f"Unable to read profile timezone from {profile_path}: {exc}") from exc
    timezone = profile.get("timezone")

if not isinstance(timezone, str) or not timezone:
    raise SystemExit(f"Invalid IANA timezone in profile: {timezone}")

try:
    ZoneInfo("Etc/UTC")
except ZoneInfoNotFoundError as exc:
    raise SystemExit(
        f"Python IANA timezone data is unavailable for {timezone}; "
        "install the tzdata package"
    ) from exc

try:
    ZoneInfo(timezone)
except ZoneInfoNotFoundError as exc:
    raise SystemExit(f"Invalid IANA timezone in profile: {timezone}") from exc
PY
  then
    fail "Python IANA timezone preflight failed"
  fi
}

preflight() {
  require_supported_bash "${BASH_VERSINFO[0]}"

  require_command git
  require_command yq
  require_command jq
  if command -v python3 >/dev/null 2>&1; then
    PYTHON_BIN=python3
  elif command -v python >/dev/null 2>&1; then
    PYTHON_BIN=python
  else
    fail "Missing required command: python3 or python"
  fi

  local git_version git_major git_minor
  git_version=$(git --version)
  if [[ ! $git_version =~ ([0-9]+)\.([0-9]+) ]]; then
    fail "Unable to determine Git version"
  fi
  git_major=${BASH_REMATCH[1]}
  git_minor=${BASH_REMATCH[2]}
  version_at_least "$git_major" "$git_minor" "$MIN_GIT_MAJOR" "$MIN_GIT_MINOR" || fail "Git 2.30 or newer is required"

  local yq_version
  yq_version=$(yq --version 2>&1)
  [[ $yq_version =~ version[[:space:]]+v?4\. ]] || fail "yq v4 is required"

  local jq_version
  jq_version=$(jq --version 2>&1)
  [[ $jq_version =~ jq-1\.([6-9]|[1-9][0-9]+) ]] || fail "jq 1.6 or newer is required"

  local python_version python_major python_minor
  python_version=$($PYTHON_BIN --version 2>&1)
  if [[ ! $python_version =~ Python[[:space:]]+([0-9]+)\.([0-9]+) ]]; then
    fail "Unable to determine Python version"
  fi
  python_major=${BASH_REMATCH[1]}
  python_minor=${BASH_REMATCH[2]}
  version_at_least "$python_major" "$python_minor" 3 9 || fail "Python 3.9 or newer is required"
  check_python_timezone

  if command -v sha256sum >/dev/null 2>&1; then
    :
  elif command -v shasum >/dev/null 2>&1; then
    shasum --algorithm 256 </dev/null >/dev/null 2>&1 || fail "shasum must support SHA-256"
  else
    fail "Missing required command: sha256sum or shasum"
  fi

  printf 'Preflight passed: Bash %s, Git %s, Python %s.%s, yq v4, jq >= 1.6\n' "$BASH_VERSION" "${git_major}.${git_minor}" "$python_major" "$python_minor"
}

python_bin() {
  if [[ -n ${PYTHON_BIN:-} ]]; then
    printf '%s\n' "$PYTHON_BIN"
  elif command -v python3 >/dev/null 2>&1; then
    printf '%s\n' python3
  else
    printf '%s\n' python
  fi
}

front_matter_yaml() {
  local path=$1
  awk '
    NR == 1 {
      if ($0 != "---") exit 2
      next
    }
    $0 == "---" {
      found = 1
      exit
    }
    { print }
    END {
      if (!found) exit 3
    }
  ' "$path"
}

note_value() {
  local path=$1
  local expression=$2
  front_matter_yaml "$path" | yq -r "$expression" | tr -d '\r'
}

event_value() {
  local path=$1
  local expression=$2
  jq -r "$expression" <"$path" | tr -d '\r'
}

front_matter_end_line() {
  local path=$1
  awk 'NR > 1 && $0 == "---" { print NR; found = 1; exit } END { if (!found) exit 1 }' "$path"
}

asset_sha256() {
  local path=$1
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$path" | awk '{print $1}'
  else
    shasum --algorithm 256 "$path" | awk '{print $1}'
  fi
}

require_calendar_day() {
  local day=$1
  "$PYTHON_BIN" - "$day" <<'PY' || fail "Invalid calendar date: $day"
import sys
from datetime import date

value = sys.argv[1]
try:
    parsed = date.fromisoformat(value)
except ValueError as exc:
    raise SystemExit(1) from exc
raise SystemExit(0 if parsed.isoformat() == value else 1)
PY
}

require_safe_remote() {
  local branch remote url authority
  branch=$(git branch --show-current)
  [[ -n $branch ]] || fail "A named branch is required for synchronization"
  remote=$(git config --get "branch.$branch.remote" || true)
  [[ -n $remote && $remote != . ]] || fail "The current branch must track a remote"
  url=$(git remote get-url --push "$remote") || fail "Unable to resolve push URL for remote: $remote"
  case $url in
    https://*)
      authority=${url#https://}
      authority=${authority%%/*}
      [[ $authority != *@* ]] || fail "HTTPS remote URL must not embed credentials"
      [[ $url != *access_token=* && $url != *private_token=* && $url != *token=* ]] || fail "Remote URL must not contain a token"
      ;;
    ssh://*|git@*:*) ;;
    *) fail "Remote must use HTTPS without embedded credentials or SSH" ;;
  esac
}

reject_unknown_untracked_files() {
  local path
  while IFS= read -r path; do
    [[ -n $path ]] || continue
    [[ $path =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}/(notes/[0-9a-f-]{36}\.md|assets/[0-9a-f]{64}\.[a-z0-9]+|events/[0-9a-f-]{36}\.json)$ ]] || fail "Unknown untracked path: $path"
  done < <(git ls-files --others --exclude-standard)
}

new_note() {
  preflight
  [[ -f .ebbinghaus/profile.json ]] || fail "Not an initialized Ebbinghaus repository"
  git rev-parse --show-toplevel >/dev/null 2>&1 || fail "Not inside a Git worktree"

  local py metadata day authored_at note_id revision_id device_id body body_hash path
  py=$(python_bin)
  metadata=$($py - .ebbinghaus/profile.json <<'PY'
import json
import os
import sys
import time
from datetime import datetime
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

profile = json.load(open(sys.argv[1], encoding="utf-8"))
timezone = profile.get("timezone")
try:
    zone = ZoneInfo(timezone)
except (TypeError, ZoneInfoNotFoundError) as exc:
    raise SystemExit(f"Invalid IANA timezone in profile: {timezone}") from exc
epoch = int(os.environ.get("SOURCE_DATE_EPOCH", str(int(time.time()))))
current = datetime.fromtimestamp(epoch, zone).replace(microsecond=0)
print(current.date().isoformat())
print(current.isoformat())
PY
  ) || fail "Unable to calculate the profile date"
  day=$(printf '%s\n' "$metadata" | sed -n '1p')
  authored_at=$(printf '%s\n' "$metadata" | sed -n '2p')
  note_id=$($py -c 'import uuid; print(uuid.uuid4())')
  revision_id=$($py -c 'import uuid; print(uuid.uuid4())')
  device_id=$(git config --local --get ebbinghaus.deviceId || true)
  if [[ -z $device_id ]]; then
    device_id=$($py -c 'import uuid; print(uuid.uuid4())')
    git config --local ebbinghaus.deviceId "$device_id"
  fi

  body='# New note'
  body_hash=$(printf '%s\n' "$body" | $py -c 'import hashlib, sys; print(hashlib.sha256(sys.stdin.buffer.read()).hexdigest())')
  path="$day/notes/$revision_id.md"
  mkdir -p "${path%/*}"
  [[ ! -e $path ]] || fail "Draft path already exists: $path"
  {
    printf '%s\n' '---'
    printf 'schema: ebbinghaus-note/v1\n'
    printf 'note_id: %s\n' "$note_id"
    printf 'revision_id: %s\n' "$revision_id"
    printf 'parent_revision_ids: []\n'
    printf 'revision_kind: create\n'
    printf 'authored_at: %s\n' "$authored_at"
    printf 'learning_started_at: %s\n' "$authored_at"
    printf 'source_device_id: %s\n' "$device_id"
    printf 'content_sha256: %s\n' "$body_hash"
    printf '%s\n' '---'
    printf '%s\n' "$body"
  } >"$path"
  printf '%s\n' "$path"
}

revise_note() {
  local source=${1:-}
  [[ -n $source ]] || fail "Usage: ${0##*/} revise <committed-revision.md>"
  source=${source#./}

  preflight
  [[ -f .ebbinghaus/profile.json ]] || fail "Not an initialized Ebbinghaus repository"
  git rev-parse --show-toplevel >/dev/null 2>&1 || fail "Not inside a Git worktree"
  [[ $source =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}/notes/[0-9a-f-]{36}\.md$ ]] || fail "Revision path must be YYYY-MM-DD/notes/<revision-uuid>.md"
  require_calendar_day "${source%%/*}"
  [[ -f $source ]] || fail "Revision does not exist: $source"
  git ls-files --error-unmatch -- "$source" >/dev/null 2>&1 || fail "Revision must be committed before it can be revised: $source"
  git diff --quiet -- "$source" || fail "Committed revision has local modifications: $source"
  git diff --cached --quiet -- "$source" || fail "Committed revision has staged modifications: $source"

  local schema note_id source_revision_id source_device_id source_hash revision_kind body_start actual_hash
  schema=$(note_value "$source" '.schema') || fail "Invalid YAML front matter: $source"
  note_id=$(note_value "$source" '.note_id') || fail "Invalid YAML front matter: $source"
  source_revision_id=$(note_value "$source" '.revision_id') || fail "Invalid YAML front matter: $source"
  source_device_id=$(note_value "$source" '.source_device_id') || fail "Invalid YAML front matter: $source"
  source_hash=$(note_value "$source" '.content_sha256') || fail "Invalid YAML front matter: $source"
  revision_kind=$(note_value "$source" '.revision_kind') || fail "Invalid YAML front matter: $source"
  [[ $schema == ebbinghaus-note/v1 ]] || fail "Unsupported note schema in $source"
  [[ $note_id =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$ ]] || fail "Invalid note_id in $source"
  [[ $source_revision_id =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$ ]] || fail "Invalid revision_id in $source"
  [[ $source_device_id =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$ ]] || fail "Invalid source_device_id in $source"
  [[ ${source##*/} == "$source_revision_id.md" ]] || fail "Revision filename does not match revision_id: $source"
  [[ $source_hash =~ ^[0-9a-f]{64}$ ]] || fail "Invalid content_sha256 in $source"
  [[ $revision_kind == create || $revision_kind == restart || $revision_kind == merge ]] || fail "Invalid revision_kind in $source"
  body_start=$(front_matter_end_line "$source") || fail "Invalid YAML front matter: $source"
  body_start=$((body_start + 1))
  actual_hash=$(tail -n "+$body_start" "$source" | "$(python_bin)" -c 'import hashlib, sys; print(hashlib.sha256(sys.stdin.buffer.read()).hexdigest())')
  [[ $actual_hash == "$source_hash" ]] || fail "Markdown body hash does not match content_sha256: $source"

  local candidate candidate_note_id candidate_revision_id parent
  local -a revision_ids=()
  local -a revision_parents=()
  local -A revision_present=()
  local -A has_child=()
  for candidate in [0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]/notes/*.md; do
    [[ -f $candidate ]] || continue
    candidate_note_id=$(note_value "$candidate" '.note_id') || fail "Invalid YAML front matter: $candidate"
    [[ $candidate_note_id == "$note_id" ]] || continue
    candidate_revision_id=$(note_value "$candidate" '.revision_id') || fail "Invalid YAML front matter: $candidate"
    [[ $candidate_revision_id =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$ ]] || fail "Invalid revision_id in $candidate"
    revision_ids+=("$candidate_revision_id")
    revision_present["$candidate_revision_id"]=1
    while IFS= read -r parent; do
      [[ -n $parent && $parent != null ]] || continue
      revision_parents+=("$parent")
      has_child["$parent"]=1
    done < <(note_value "$candidate" '.parent_revision_ids[]')
  done

  for parent in "${revision_parents[@]}"; do
    [[ -n ${revision_present[$parent]:-} ]] || fail "Missing parent revision: $parent"
  done

  local leaf_count=0
  local active_leaf=
  for candidate_revision_id in "${revision_ids[@]}"; do
    if [[ -z ${has_child[$candidate_revision_id]:-} ]]; then
      leaf_count=$((leaf_count + 1))
      active_leaf=$candidate_revision_id
    fi
  done
  [[ $leaf_count -eq 1 && $active_leaf == "$source_revision_id" ]] || fail "Revision is not the uniquely active leaf: $source"

  local event event_stream event_type event_id event_parent lifecycle_leaf_type
  local -a lifecycle_ids=()
  local -A lifecycle_types=()
  local -A lifecycle_has_child=()
  for event in [0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]/events/*.json; do
    [[ -f $event ]] || continue
    event_stream=$(event_value "$event" '.stream_id') || fail "Invalid JSON event: $event"
    [[ $event_stream == "lifecycle:$note_id" ]] || continue
    event_id=$(event_value "$event" '.event_id') || fail "Invalid JSON event: $event"
    event_type=$(event_value "$event" '.event_type') || fail "Invalid JSON event: $event"
    [[ $event_id =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$ ]] || fail "Invalid lifecycle event_id: $event"
    lifecycle_ids+=("$event_id")
    lifecycle_types["$event_id"]=$event_type
    while IFS= read -r event_parent; do
      [[ -n $event_parent ]] || continue
      lifecycle_has_child["$event_parent"]=1
    done < <(event_value "$event" '.parent_event_ids[]?')
  done
  local lifecycle_leaf_count=0
  for event_id in "${lifecycle_ids[@]}"; do
    if [[ -z ${lifecycle_has_child[$event_id]:-} ]]; then
      lifecycle_leaf_count=$((lifecycle_leaf_count + 1))
      lifecycle_leaf_type=${lifecycle_types[$event_id]}
    fi
  done
  (( lifecycle_leaf_count <= 1 )) || fail "Revision belongs to a note with a lifecycle conflict: $source"
  [[ ${lifecycle_leaf_type:-} != delete ]] || fail "Revision belongs to a deleted note: $source"

  local relative_asset asset_name asset_hash source_asset asset_size
  local -a asset_names=()
  local -a asset_sources=()
  local asset_reference_count valid_asset_reference_count
  asset_reference_count=$({ tail -n "+$body_start" "$source" | grep -oE '\.\./assets/[^)[:space:]]+' || true; } | wc -l | tr -d '[:space:]')
  valid_asset_reference_count=$({ tail -n "+$body_start" "$source" | grep -oE '\.\./assets/[0-9a-f]{64}\.[A-Za-z0-9]+' || true; } | wc -l | tr -d '[:space:]')
  [[ $asset_reference_count == "$valid_asset_reference_count" ]] || fail "Invalid asset reference in $source"
  while IFS= read -r relative_asset; do
    [[ -n $relative_asset ]] || continue
    asset_name=${relative_asset#../assets/}
    asset_hash=${asset_name%%.*}
    source_asset="${source%/notes/*}/assets/$asset_name"
    [[ -f $source_asset ]] || fail "Missing referenced asset: $relative_asset"
    asset_size=$(wc -c <"$source_asset")
    (( asset_size <= 10 * 1024 * 1024 )) || fail "Referenced asset exceeds 10 MiB: $relative_asset"
    [[ $(asset_sha256 "$source_asset") == "$asset_hash" ]] || fail "Referenced asset hash does not match filename: $relative_asset"
    asset_names+=("$asset_name")
    asset_sources+=("$source_asset")
  done < <(tail -n "+$body_start" "$source" | grep -oE '\.\./assets/[0-9a-f]{64}\.[A-Za-z0-9]+' | sort -u || true)

  local py metadata day authored_at revision_id device_id path target_asset index
  py=$(python_bin)
  metadata=$($py - .ebbinghaus/profile.json <<'PY'
import json
import os
import sys
import time
from datetime import datetime
from zoneinfo import ZoneInfo

with open(sys.argv[1], encoding="utf-8") as profile_file:
    profile = json.load(profile_file)
zone = ZoneInfo(profile["timezone"])
epoch = int(os.environ.get("SOURCE_DATE_EPOCH", str(int(time.time()))))
current = datetime.fromtimestamp(epoch, zone).replace(microsecond=0)
print(current.date().isoformat())
print(current.isoformat())
PY
  ) || fail "Unable to calculate the profile date"
  day=$(printf '%s\n' "$metadata" | sed -n '1p')
  authored_at=$(printf '%s\n' "$metadata" | sed -n '2p')
  revision_id=$($py -c 'import uuid; print(uuid.uuid4())')
  device_id=$(git config --local --get ebbinghaus.deviceId || true)
  if [[ -z $device_id ]]; then
    device_id=$($py -c 'import uuid; print(uuid.uuid4())')
    git config --local ebbinghaus.deviceId "$device_id"
  fi
  path="$day/notes/$revision_id.md"
  [[ ! -e $path ]] || fail "Draft path already exists: $path"

  for ((index = 0; index < ${#asset_names[@]}; index++)); do
    target_asset="$day/assets/${asset_names[$index]}"
    if [[ -e $target_asset ]]; then
      [[ -f $target_asset && $(asset_sha256 "$target_asset") == "${asset_names[$index]%%.*}" ]] || fail "Asset path collision: $target_asset"
    fi
  done

  mkdir -p "${path%/*}"
  if (( ${#asset_names[@]} > 0 )); then
    mkdir -p "$day/assets"
    for ((index = 0; index < ${#asset_names[@]}; index++)); do
      target_asset="$day/assets/${asset_names[$index]}"
      [[ -e $target_asset ]] || cp -- "${asset_sources[$index]}" "$target_asset"
    done
  fi
  {
    printf '%s\n' '---'
    printf 'schema: ebbinghaus-note/v1\n'
    printf 'note_id: %s\n' "$note_id"
    printf 'revision_id: %s\n' "$revision_id"
    printf 'parent_revision_ids: [%s]\n' "$source_revision_id"
    printf 'revision_kind: restart\n'
    printf 'authored_at: %s\n' "$authored_at"
    printf 'learning_started_at: %s\n' "$authored_at"
    printf 'source_device_id: %s\n' "$device_id"
    printf 'content_sha256: %s\n' "$source_hash"
    printf '%s\n' '---'
    tail -n "+$body_start" "$source"
  } >"$path"
  printf '%s\n' "$path"
}

validate_repository() {
  preflight
  [[ -f .ebbinghaus/profile.json ]] || fail "Not an initialized Ebbinghaus repository"
  git rev-parse --show-toplevel >/dev/null 2>&1 || fail "Not inside a Git worktree"

  local git_dir
  git_dir=$(git rev-parse --absolute-git-dir)
  if [[ -d $git_dir/rebase-merge || -d $git_dir/rebase-apply || -f $git_dir/MERGE_HEAD || -f $git_dir/CHERRY_PICK_HEAD || -f $git_dir/REVERT_HEAD ]]; then
    fail "Git history operation is in progress"
  fi

  local tracked_change
  while IFS= read -r tracked_change; do
    [[ -n $tracked_change ]] || continue
    if [[ $tracked_change =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}/(notes/.*\.md|assets/.*|events/.*\.json)$ ]]; then
      printf 'Tracked repository data was modified or deleted: %s\n' "$tracked_change" >&2
      fail "Restore tracked data, then use revise to create a new immutable revision"
    fi
  done < <({ git diff --name-only --diff-filter=ACDMRTUXB; git diff --cached --name-only --diff-filter=ACDMRTUXB; } | sort -u)

  if git ls-files --unmerged | grep -q .; then
    fail "Unresolved Git conflicts must be resolved before validation"
  fi

  local staged_path
  while IFS= read -r staged_path; do
    [[ -n $staged_path ]] || continue
    [[ $staged_path =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}/(notes/[0-9a-f-]{36}\.md|assets/[0-9a-f]{64}\.[A-Za-z0-9]+|events/[0-9a-f-]{36}\.json)$ ]] || fail "Unknown staged path: $staged_path"
  done < <(git diff --cached --name-only --diff-filter=A)

  jq empty <.ebbinghaus/profile.json >/dev/null || fail "Invalid JSON profile: .ebbinghaus/profile.json"

  local note event script_dir validator publishable
  for note in [0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]/notes/*.md; do
    [[ -f $note ]] || continue
    front_matter_yaml "$note" | yq -r '.' >/dev/null || fail "Invalid YAML front matter: $note"
  done
  for event in [0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]/events/*.json; do
    [[ -f $event ]] || continue
    jq empty <"$event" >/dev/null || fail "Invalid JSON event: $event"
  done


  while IFS= read -r publishable; do
    [[ $publishable =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}/(notes/[0-9a-f-]{36}\.md|assets/[0-9a-f]{64}\.[A-Za-z0-9]+|events/[0-9a-f-]{36}\.json)$ ]] || continue
    if git log --all --format= --name-only -- "$publishable" | grep -Fxq "$publishable"; then
      fail "Path already exists in Git history: $publishable"
    fi
  done < <(git ls-files --others --exclude-standard)

  script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
  validator="$script_dir/../tools/validate_repository_format.py"
  [[ -f $validator ]] || fail "Missing repository validator: $validator"
  "$PYTHON_BIN" "$validator" .

  printf '%s\n' 'Publishable new files:'
  while IFS= read -r publishable; do
    [[ $publishable =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}/(notes/[0-9a-f-]{36}\.md|assets/[0-9a-f]{64}\.[A-Za-z0-9]+|events/[0-9a-f-]{36}\.json)$ ]] || continue
    printf '%s\n' "$publishable"
  done < <(git ls-files --others --exclude-standard)
}

pull_repository() {
  preflight
  git rev-parse --show-toplevel >/dev/null 2>&1 || fail "Not inside a Git worktree"
  [[ -z $(git status --porcelain=v1 --untracked-files=normal) ]] || fail "Worktree must be clean before pull"
  require_safe_remote
  git pull --ff-only
  validate_repository
}

list_publishable_files() {
  local publishable
  while IFS= read -r publishable; do
    [[ $publishable =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}/(notes/[0-9a-f-]{36}\.md|assets/[0-9a-f]{64}\.[A-Za-z0-9]+|events/[0-9a-f-]{36}\.json)$ ]] || continue
    printf '%s\n' "$publishable"
  done < <(git ls-files --others --exclude-standard)
}

publish_repository() {
  preflight
  git rev-parse --show-toplevel >/dev/null 2>&1 || fail "Not inside a Git worktree"
  [[ -z $(git diff --name-only) && -z $(git diff --cached --name-only) ]] || fail "Tracked or staged changes must be resolved before publish"
  require_safe_remote
  reject_unknown_untracked_files

  local before_files after_files publishable first_day device_id batch_id answer py
  before_files=$(list_publishable_files)
  [[ -n $before_files ]] || fail "No publishable new files"

  git pull --ff-only
  validate_repository
  after_files=$(list_publishable_files)
  [[ $before_files == "$after_files" ]] || fail "Publishable file set changed during pull; review and retry"

  printf '%s\n' 'Files to publish:'
  printf '%s\n' "$after_files"
  while IFS= read -r publishable; do
    git diff --no-index -- /dev/null "$publishable" || true
  done <<<"$after_files"
  printf 'Publish these files? [y/N] '
  IFS= read -r answer
  answer=${answer%$'\r'}
  [[ $answer == y || $answer == Y || $answer == yes || $answer == YES ]] || {
    printf '%s\n' 'Publication cancelled'
    return 0
  }

  while IFS= read -r publishable; do
    git add -- "$publishable"
  done <<<"$after_files"
  first_day=${after_files%%/*}
  device_id=$(git config --local --get ebbinghaus.deviceId || true)
  py=$(python_bin)
  if [[ -z $device_id ]]; then
    device_id=$($py -c 'import uuid; print(uuid.uuid4())')
    git config --local ebbinghaus.deviceId "$device_id"
  fi
  batch_id=$($py -c 'import uuid; print(uuid.uuid4())')
  git commit -m "notes($first_day): publish desktop revisions from $device_id" -m "Ebbinghaus-Batch-Id: $batch_id"
  git push
}

usage() {
  printf 'Usage: %s <preflight|new|revise|validate|pull|publish>\n' "${0##*/}"
}

main() {
  case ${1:-} in
    preflight) preflight ;;
    new) new_note ;;
    revise) revise_note "${2:-}" ;;
    validate) validate_repository ;;
    pull) pull_repository ;;
    publish) publish_repository ;;
    -h|--help) usage ;;
    *) usage >&2; exit 2 ;;
  esac
}

if [[ ${BASH_SOURCE[0]} == "$0" ]]; then
  main "$@"
fi
