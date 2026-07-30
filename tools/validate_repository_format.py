#!/usr/bin/env python3
"""Dependency-free validator for the Ebbinghaus repository format v1."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import uuid
from dataclasses import dataclass, asdict
from datetime import date, datetime
from pathlib import Path, PurePosixPath
from typing import Any
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError


DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
ASSET_NAME_RE = re.compile(r"^([0-9a-f]{64})\.([a-z0-9]{1,10})$")
ASSET_REF_RE = re.compile(r"!?\[[^]]*]\((\.\./assets/([0-9a-f]{64})(\.[a-z0-9]{1,10}))\)")
PROFILE_REQUIRED = {"schema", "repository_id", "profile_id", "timezone", "review_algorithm"}
NOTE_REQUIRED = {
    "schema",
    "note_id",
    "revision_id",
    "parent_revision_ids",
    "revision_kind",
    "authored_at",
    "learning_started_at",
    "source_device_id",
    "content_sha256",
}
EVENT_REQUIRED = {
    "schema",
    "event_id",
    "stream_id",
    "parent_event_ids",
    "event_type",
    "occurred_at",
    "source_device_id",
    "algorithm",
    "payload",
}


@dataclass(frozen=True)
class Issue:
    code: str
    path: str
    message: str


def issue(issues: list[Issue], code: str, path: Path | str, message: str) -> None:
    issues.append(Issue(code=code, path=str(path).replace("\\", "/"), message=message))


def is_uuid(value: Any) -> bool:
    try:
        return str(uuid.UUID(str(value))) == str(value).lower()
    except (ValueError, TypeError, AttributeError):
        return False


def has_offset_datetime(value: Any) -> bool:
    if not isinstance(value, str):
        return False
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        return parsed.tzinfo is not None
    except ValueError:
        return False


def is_calendar_date(value: str) -> bool:
    if not DATE_RE.fullmatch(value):
        return False
    try:
        return date.fromisoformat(value).isoformat() == value
    except ValueError:
        return False


def profile_date(value: Any, timezone: ZoneInfo) -> str | None:
    if not has_offset_datetime(value):
        return None
    return (
        datetime.fromisoformat(str(value).replace("Z", "+00:00"))
        .astimezone(timezone)
        .date()
        .isoformat()
    )


def load_json(path: Path, issues: list[Issue], code: str) -> dict[str, Any] | None:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        issue(issues, code, path, f"invalid JSON: {exc}")
        return None
    if not isinstance(data, dict):
        issue(issues, code, path, "document must be an object")
        return None
    return data


def validate_profile(root: Path, issues: list[Issue]) -> dict[str, Any] | None:
    path = root / ".ebbinghaus" / "profile.json"
    data = load_json(path, issues, "PROFILE_SCHEMA")
    if data is None:
        return None
    if set(data) != PROFILE_REQUIRED:
        issue(issues, "PROFILE_SCHEMA", path, "profile keys do not match v1 schema")
    if data.get("schema") != "ebbinghaus-profile/v1":
        issue(issues, "PROFILE_SCHEMA", path, "unsupported profile schema")
    for key in ("repository_id", "profile_id"):
        if not is_uuid(data.get(key)):
            issue(issues, "PROFILE_SCHEMA", path, f"{key} must be a canonical UUID")
    if not isinstance(data.get("timezone"), str) or not data.get("timezone"):
        issue(issues, "PROFILE_SCHEMA", path, "timezone is required")
    else:
        try:
            ZoneInfo(data["timezone"])
        except (ZoneInfoNotFoundError, ValueError):
            issue(issues, "PROFILE_SCHEMA", path, "timezone must be a valid IANA timezone")
    algorithm = data.get("review_algorithm")
    expected_algorithm_keys = {"id", "version", "parameters"}
    if not isinstance(algorithm, dict) or set(algorithm) != expected_algorithm_keys:
        issue(issues, "PROFILE_SCHEMA", path, "review_algorithm does not match v1 schema")
        return data
    parameters = algorithm.get("parameters")
    intervals = parameters.get("interval_days") if isinstance(parameters, dict) else None
    if (
        algorithm.get("id") != "ebbinghaus-8-stage"
        or algorithm.get("version") != 1
        or not isinstance(parameters, dict)
        or set(parameters) != {"interval_days"}
        or not isinstance(intervals, list)
        or len(intervals) != 8
        or any(not isinstance(value, int) or value < 1 for value in intervals)
    ):
        issue(issues, "PROFILE_SCHEMA", path, "review_algorithm parameters are invalid")
    return data


def parse_scalar(value: str) -> Any:
    value = value.strip()
    if value == "[]":
        return []
    if value.startswith("[") and value.endswith("]"):
        inner = value[1:-1].strip()
        return [] if not inner else [item.strip() for item in inner.split(",")]
    if value in {"true", "false"}:
        return value == "true"
    if re.fullmatch(r"-?\d+", value):
        return int(value)
    if len(value) >= 2 and value[0] == value[-1] and value[0] in {"'", '"'}:
        return value[1:-1]
    return value


def parse_markdown(path: Path, issues: list[Issue]) -> tuple[dict[str, Any], bytes] | None:
    try:
        raw = path.read_bytes()
    except OSError as exc:
        issue(issues, "NOTE_SCHEMA", path, str(exc))
        return None
    if raw.startswith(b"\xef\xbb\xbf") or b"\r\n" in raw:
        issue(issues, "NOTE_SCHEMA", path, "Markdown must be UTF-8 without BOM and use LF")
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError as exc:
        issue(issues, "NOTE_SCHEMA", path, f"invalid UTF-8: {exc}")
        return None
    if not text.startswith("---\n"):
        issue(issues, "NOTE_SCHEMA", path, "missing YAML front matter")
        return None
    separator = text.find("\n---\n", 4)
    if separator < 0:
        issue(issues, "NOTE_SCHEMA", path, "unterminated YAML front matter")
        return None
    front_matter: dict[str, Any] = {}
    for line in text[4:separator].split("\n"):
        if not line.strip():
            continue
        if ":" not in line:
            issue(issues, "NOTE_SCHEMA", path, f"invalid front matter line: {line}")
            continue
        key, value = line.split(":", 1)
        front_matter[key.strip()] = parse_scalar(value)
    body = text[separator + 5 :].encode("utf-8")
    return front_matter, body


def validate_note_metadata(data: dict[str, Any], path: Path, issues: list[Issue]) -> None:
    if set(data) != NOTE_REQUIRED:
        issue(issues, "NOTE_SCHEMA", path, "front matter keys do not match v1 schema")
    if data.get("schema") != "ebbinghaus-note/v1":
        issue(issues, "NOTE_SCHEMA", path, "unsupported note schema")
    for key in ("note_id", "revision_id", "source_device_id"):
        if not is_uuid(data.get(key)):
            issue(issues, "NOTE_SCHEMA", path, f"{key} must be a canonical UUID")
    parents = data.get("parent_revision_ids")
    if not isinstance(parents, list) or any(not is_uuid(parent) for parent in parents) or len(parents) != len(set(parents or [])):
        issue(issues, "NOTE_SCHEMA", path, "parent_revision_ids must contain unique UUIDs")
    if data.get("revision_kind") not in {"create", "restart", "merge"}:
        issue(issues, "NOTE_SCHEMA", path, "invalid revision_kind")
    for key in ("authored_at", "learning_started_at"):
        if not has_offset_datetime(data.get(key)):
            issue(issues, "NOTE_SCHEMA", path, f"{key} must include a UTC offset")
    if not isinstance(data.get("content_sha256"), str) or not SHA256_RE.fullmatch(data["content_sha256"]):
        issue(issues, "NOTE_SCHEMA", path, "content_sha256 is invalid")


def validate_notes(
    root: Path,
    issues: list[Issue],
    timezone: ZoneInfo,
) -> dict[str, tuple[dict[str, Any], Path]]:
    revisions: dict[str, tuple[dict[str, Any], Path]] = {}
    for path in sorted(root.glob("*/notes/*.md")):
        day = path.parents[1].name
        if not is_calendar_date(day):
            issue(issues, "NOTE_PATH", path, "note must be beneath YYYY-MM-DD/notes")
        parsed = parse_markdown(path, issues)
        if parsed is None:
            continue
        data, body = parsed
        validate_note_metadata(data, path, issues)
        authored_day = profile_date(data.get("authored_at"), timezone)
        if authored_day is not None and authored_day != day:
            issue(issues, "NOTE_PATH", path, "note date must match authored_at in profile timezone")
        revision_id = data.get("revision_id")
        if isinstance(revision_id, str):
            if path.stem != revision_id:
                issue(issues, "NOTE_PATH", path, "filename must equal revision_id")
            if revision_id in revisions:
                issue(issues, "REVISION_ID_REUSE", path, "revision_id appears more than once")
            else:
                revisions[revision_id] = (data, path)
        expected_hash = data.get("content_sha256")
        if isinstance(expected_hash, str) and hashlib.sha256(body).hexdigest() != expected_hash:
            issue(issues, "NOTE_BODY_HASH", path, "Markdown body hash does not match content_sha256")
        body_text = body.decode("utf-8", errors="replace")
        for relative, asset_hash, extension in ASSET_REF_RE.findall(body_text):
            asset = path.parent.parent / "assets" / f"{asset_hash}{extension}"
            if not asset.is_file():
                issue(issues, "ASSET_REFERENCE", path, f"missing asset: {relative}")
                continue
            content = asset.read_bytes()
            if len(content) > 10 * 1024 * 1024 or hashlib.sha256(content).hexdigest() != asset_hash:
                issue(issues, "ASSET_REFERENCE", asset, "asset size or hash is invalid")
        for match in re.finditer(r"!?\[[^]]*]\(([^)]+)\)", body_text):
            target = match.group(1)
            if target.startswith("../assets/") and not ASSET_REF_RE.fullmatch(match.group(0)):
                issue(issues, "ASSET_REFERENCE", path, f"invalid asset reference: {target}")
    return revisions


def validate_revision_graph(revisions: dict[str, tuple[dict[str, Any], Path]], issues: list[Issue]) -> None:
    for revision_id, (data, path) in revisions.items():
        for parent in data.get("parent_revision_ids", []):
            if parent not in revisions:
                issue(issues, "REVISION_MISSING_PARENT", path, f"missing parent revision: {parent}")
            elif revisions[parent][0].get("note_id") != data.get("note_id"):
                issue(issues, "REVISION_MISSING_PARENT", path, f"parent belongs to another note: {parent}")

    visiting: set[str] = set()
    visited: set[str] = set()

    def walk(revision_id: str) -> bool:
        if revision_id in visiting:
            return True
        if revision_id in visited:
            return False
        visiting.add(revision_id)
        data, _ = revisions[revision_id]
        cyclic = any(parent in revisions and walk(parent) for parent in data.get("parent_revision_ids", []))
        visiting.remove(revision_id)
        visited.add(revision_id)
        return cyclic

    for revision_id, (_, path) in revisions.items():
        if revision_id not in visited and walk(revision_id):
            issue(issues, "REVISION_CYCLE", path, "revision graph contains a cycle")
            break


def validate_event_metadata(data: dict[str, Any], path: Path, issues: list[Issue]) -> None:
    if set(data) != EVENT_REQUIRED:
        issue(issues, "EVENT_SCHEMA", path, "event keys do not match v1 schema")
    if data.get("schema") != "ebbinghaus-event/v1":
        issue(issues, "EVENT_SCHEMA", path, "unsupported event schema")
    for key in ("event_id", "source_device_id"):
        if not is_uuid(data.get(key)):
            issue(issues, "EVENT_SCHEMA", path, f"{key} must be a canonical UUID")
    if not isinstance(data.get("stream_id"), str) or not re.fullmatch(r"(review|lifecycle):[0-9a-f-]{36}", data["stream_id"]):
        issue(issues, "EVENT_SCHEMA", path, "stream_id is invalid")
    parents = data.get("parent_event_ids")
    if not isinstance(parents, list) or any(not is_uuid(parent) for parent in parents) or len(parents) != len(set(parents or [])):
        issue(issues, "EVENT_SCHEMA", path, "parent_event_ids must contain unique UUIDs")
    if data.get("event_type") not in {"review", "review_merge", "delete", "restore", "lifecycle_resolve"}:
        issue(issues, "EVENT_SCHEMA", path, "invalid event_type")
    if not has_offset_datetime(data.get("occurred_at")):
        issue(issues, "EVENT_SCHEMA", path, "occurred_at must include a UTC offset")
    algorithm = data.get("algorithm")
    if not isinstance(algorithm, dict) or set(algorithm) != {"id", "version"} or algorithm.get("id") != "ebbinghaus-8-stage" or algorithm.get("version") != 1:
        issue(issues, "EVENT_SCHEMA", path, "unsupported algorithm")
    if not isinstance(data.get("payload"), dict):
        issue(issues, "EVENT_SCHEMA", path, "payload must be an object")


def validate_event_graph(
    events: dict[str, tuple[dict[str, Any], Path, str]],
    issues: list[Issue],
) -> None:
    for event_id, (data, path, _) in events.items():
        parents = data.get("parent_event_ids")
        if not isinstance(parents, list):
            continue
        for parent in parents:
            if parent not in events:
                issue(issues, "EVENT_MISSING_PARENT", path, f"missing parent event: {parent}")
            elif events[parent][0].get("stream_id") != data.get("stream_id"):
                issue(issues, "EVENT_MISSING_PARENT", path, f"parent belongs to another stream: {parent}")

    states: dict[str, int] = {}
    for start_id in events:
        if states.get(start_id) == 2:
            continue
        stack: list[tuple[str, int]] = [(start_id, 0)]
        while stack:
            event_id, parent_index = stack[-1]
            states.setdefault(event_id, 1)
            data, path, _ = events[event_id]
            parents = data.get("parent_event_ids")
            parent_values = parents if isinstance(parents, list) else []
            valid_parents = [
                parent
                for parent in parent_values
                if isinstance(parent, str)
                and parent in events
                and events[parent][0].get("stream_id") == data.get("stream_id")
            ]
            if parent_index >= len(valid_parents):
                states[event_id] = 2
                stack.pop()
                continue
            parent = valid_parents[parent_index]
            stack[-1] = (event_id, parent_index + 1)
            parent_state = states.get(parent, 0)
            if parent_state == 0:
                stack.append((parent, 0))
            elif parent_state == 1:
                issue(
                    issues,
                    "EVENT_CYCLE",
                    path,
                    f"event graph contains a cycle through {parent}",
                )


def validate_events(root: Path, issues: list[Issue], timezone: ZoneInfo) -> None:
    events: dict[str, tuple[dict[str, Any], Path, str]] = {}
    for path in sorted(root.glob("*/events/*.json")):
        day = path.parents[1].name
        if not is_calendar_date(day):
            issue(issues, "EVENT_PATH", path, "event must be beneath YYYY-MM-DD/events")
        data = load_json(path, issues, "EVENT_SCHEMA")
        if data is None:
            continue
        validate_event_metadata(data, path, issues)
        occurred_day = profile_date(data.get("occurred_at"), timezone)
        if occurred_day is not None and occurred_day != day:
            issue(issues, "EVENT_PATH", path, "event date must match occurred_at in profile timezone")
        event_id = data.get("event_id")
        if not isinstance(event_id, str):
            continue
        if path.stem != event_id:
            issue(issues, "EVENT_PATH", path, "filename must equal event_id")
        canonical = json.dumps(data, sort_keys=True, separators=(",", ":"))
        if event_id in events:
            if events[event_id][2] != canonical:
                issue(issues, "EVENT_ID_REUSE", path, "event_id is reused with different content")
        else:
            events[event_id] = (data, path, canonical)
    validate_event_graph(events, issues)


def validate_assets(root: Path, issues: list[Issue]) -> None:
    for path in sorted(root.glob("*/assets/*")):
        day = path.parents[1].name
        if not is_calendar_date(day):
            issue(issues, "ASSET_PATH", path, "asset must be beneath YYYY-MM-DD/assets")
        match = ASSET_NAME_RE.fullmatch(path.name)
        if match is None or not path.is_file():
            issue(issues, "ASSET_PATH", path, "asset filename must be lowercase SHA-256 plus extension")
            continue
        content = path.read_bytes()
        if len(content) > 10 * 1024 * 1024:
            issue(issues, "ASSET_SIZE", path, "asset exceeds 10 MiB")
        if hashlib.sha256(content).hexdigest() != match.group(1):
            issue(issues, "ASSET_HASH", path, "asset hash does not match filename")


def validate_repository(root: Path) -> list[Issue]:
    issues: list[Issue] = []
    if not root.is_dir():
        issue(issues, "REPOSITORY_PATH", root, "repository directory does not exist")
        return issues
    profile = validate_profile(root, issues)
    try:
        timezone = ZoneInfo(profile["timezone"]) if profile is not None else ZoneInfo("Etc/UTC")
    except (KeyError, TypeError, ZoneInfoNotFoundError, ValueError):
        timezone = ZoneInfo("Etc/UTC")
    revisions = validate_notes(root, issues, timezone)
    validate_revision_graph(revisions, issues)
    validate_events(root, issues, timezone)
    validate_assets(root, issues)
    return sorted(issues, key=lambda item: (item.path, item.code, item.message))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("repository", type=Path)
    parser.add_argument("--json", action="store_true", dest="as_json")
    args = parser.parse_args(argv)
    root = args.repository.resolve()
    issues = validate_repository(root)
    report = {"repository": str(root), "valid": not issues, "issues": [asdict(item) for item in issues]}
    if args.as_json:
        print(json.dumps(report, indent=2, ensure_ascii=False))
    elif issues:
        for item in issues:
            print(f"{item.code}: {item.path}: {item.message}", file=sys.stderr)
    else:
        print(f"Repository is valid: {root}")
    return 0 if not issues else 1


if __name__ == "__main__":
    raise SystemExit(main())
