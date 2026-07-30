import os
import sys
import subprocess
import tempfile
import unittest
import re
import hashlib
import shutil
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "review-sync.sh"
BASH = (
    Path(r"C:\Program Files\Git\bin\bash.exe")
    if os.name == "nt"
    else Path(shutil.which("bash") or "bash")
)


def to_msys_path(path: Path) -> str:
    resolved = path.resolve()
    if os.name != "nt":
        return resolved.as_posix()
    drive = resolved.drive.rstrip(":").lower()
    tail = resolved.as_posix().split(":", 1)[1]
    return f"/{drive}{tail}"


class ReviewSyncPreflightTest(unittest.TestCase):
    def snapshot(self, directory: Path) -> dict[str, bytes]:
        return {
            str(path.relative_to(directory)): path.read_bytes()
            for path in directory.rglob("*")
            if path.is_file()
        }

    def write_fake_command(self, directory: Path, name: str, output: str) -> None:
        path = directory / name
        path.write_text(f"#!/usr/bin/env bash\nprintf '%s\\n' '{output}'\n", encoding="utf-8", newline="\n")
        path.chmod(0o755)

    def write_fake_yq(self, directory: Path) -> None:
        path = directory / "yq"
        path.write_text(
            "#!/usr/bin/env python\n"
            "import json\n"
            "import sys\n"
            "if sys.argv[1:] == ['--version']:\n"
            "    print('yq version v4.40.0')\n"
            "    raise SystemExit(0)\n"
            "expression = sys.argv[-1]\n"
            "data = {}\n"
            "for line in sys.stdin.read().splitlines():\n"
            "    if ':' not in line:\n"
            "        continue\n"
            "    key, value = line.split(':', 1)\n"
            "    value = value.strip()\n"
            "    if value.startswith('[') and value.endswith(']'):\n"
            "        inner = value[1:-1].strip()\n"
            "        data[key.strip()] = [] if not inner else [item.strip() for item in inner.split(',')]\n"
            "    else:\n"
            "        data[key.strip()] = value.strip('\\\"\\\'')\n"
            "key = expression.removeprefix('.').removesuffix('[]')\n"
            "value = data.get(key)\n"
            "if expression.endswith('[]'):\n"
            "    for item in value or []:\n"
            "        print(item)\n"
            "elif isinstance(value, list):\n"
            "    print(json.dumps(value, separators=(',', ':')))\n"
            "elif value is None:\n"
            "    print('null')\n"
            "else:\n"
            "    print(value)\n",
            encoding="utf-8",
            newline="\n",
        )
        path.chmod(0o755)

    def script_environment(self, fake_bin: Path) -> dict[str, str]:
        environment = os.environ.copy()
        environment["PATH"] = (
            f"{to_msys_path(fake_bin)}:{to_msys_path(Path(sys.executable).parent)}"
            ":/mingw64/bin:/usr/bin:/bin"
        )
        environment["SOURCE_DATE_EPOCH"] = "1784731200"
        return environment

    def write_revision(
        self,
        path: Path,
        note_id: str,
        revision_id: str,
        body: bytes,
        *,
        parents: list[str] | None = None,
        kind: str = "create",
    ) -> None:
        parent_yaml = ", ".join(parents or [])
        content_hash = hashlib.sha256(body).hexdigest()
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(
            (
                "---\n"
                "schema: ebbinghaus-note/v1\n"
                f"note_id: {note_id}\n"
                f"revision_id: {revision_id}\n"
                f"parent_revision_ids: [{parent_yaml}]\n"
                f"revision_kind: {kind}\n"
                "authored_at: 2026-07-20T10:00:00+08:00\n"
                "learning_started_at: 2026-07-20T10:00:00+08:00\n"
                "source_device_id: 018f0000-0000-7000-8000-000000000003\n"
                f"content_sha256: {content_hash}\n"
                "---\n"
            ).encode("utf-8")
            + body
        )

    def write_valid_profile(self, worktree: Path) -> None:
        profile_dir = worktree / ".ebbinghaus"
        profile_dir.mkdir(exist_ok=True)
        profile_dir.joinpath("profile.json").write_text(
            "{\n"
            '  "schema": "ebbinghaus-profile/v1",\n'
            '  "repository_id": "018f0000-0000-7000-8000-000000000101",\n'
            '  "profile_id": "018f0000-0000-7000-8000-000000000102",\n'
            '  "timezone": "Asia/Shanghai",\n'
            '  "review_algorithm": {\n'
            '    "id": "ebbinghaus-8-stage",\n'
            '    "version": 1,\n'
            '    "parameters": {"interval_days": [1, 2, 4, 7, 15, 30, 90, 180]}\n'
            "  }\n"
            "}\n",
            encoding="utf-8",
        )

    def write_fake_python_without_timezone_data(self, directory: Path) -> None:
        path = directory / "python"
        path.write_text(
            "#!/usr/bin/env bash\n"
            "if [[ ${1:-} == --version ]]; then\n"
            "  echo 'Python 3.9.0'\n"
            "  exit 0\n"
            "fi\n"
            "if [[ ${2:-} == .ebbinghaus/profile.json ]]; then\n"
            "  echo 'Python IANA timezone data is unavailable for Asia/Shanghai; install the tzdata package' >&2\n"
            "  exit 1\n"
            "fi\n"
            "exit 0\n",
            encoding="utf-8",
            newline="\n",
        )
        path.chmod(0o755)

    def write_fake_jq(self, directory: Path) -> None:
        path = directory / "jq"
        path.write_text(
            "#!/usr/bin/env python\n"
            "import json\n"
            "import sys\n"
            "if sys.argv[1:] == ['--version']:\n"
            "    print('jq-1.6')\n"
            "    raise SystemExit(0)\n"
            "expression = sys.argv[-1]\n"
            "data = json.load(sys.stdin)\n"
            "key = expression.split('//', 1)[0].strip().removeprefix('.').removesuffix('[]?')\n"
            "value = data.get(key)\n"
            "if expression.endswith('[]?'):\n"
            "    for item in value or []:\n"
            "        print(item)\n"
            "elif value is not None:\n"
            "    print(value)\n",
            encoding="utf-8",
            newline="\n",
        )
        path.chmod(0o755)

    def run_preflight(self, cwd: Path, path_value: str) -> subprocess.CompletedProcess[str]:
        environment = os.environ.copy()
        environment["PATH"] = path_value
        return subprocess.run(
            [str(BASH), to_msys_path(SCRIPT), "preflight"],
            cwd=cwd,
            env=environment,
            text=True,
            capture_output=True,
            check=False,
        )

    def initialize_publishing_repository(
        self, root: Path
    ) -> tuple[Path, Path, Path]:
        remote = root / "remote.git"
        client = root / "client"
        fake_bin = root / "test-bin"
        subprocess.run(["git", "init", "--bare", "-q", str(remote)], check=True)
        subprocess.run(["git", "init", "-q", str(client)], check=True)
        subprocess.run(
            ["git", "-C", str(client), "config", "user.email", "test@example.com"],
            check=True,
        )
        subprocess.run(
            ["git", "-C", str(client), "config", "user.name", "Test User"], check=True
        )
        subprocess.run(
            ["git", "-C", str(client), "config", "core.autocrlf", "false"], check=True
        )
        subprocess.run(
            ["git", "-C", str(client), "remote", "add", "origin", str(remote)],
            check=True,
        )
        subprocess.run(
            [
                "git",
                "-C",
                str(client),
                "config",
                "ebbinghaus.deviceId",
                "018f0000-0000-7000-8000-000000000193",
            ],
            check=True,
        )
        self.write_valid_profile(client)
        subprocess.run(
            ["git", "-C", str(client), "add", ".ebbinghaus/profile.json"], check=True
        )
        subprocess.run(
            ["git", "-C", str(client), "commit", "-qm", "initialize"], check=True
        )
        subprocess.run(
            ["git", "-C", str(client), "push", "-qu", "origin", "HEAD"], check=True
        )
        fake_bin.mkdir()
        self.write_fake_yq(fake_bin)
        self.write_fake_jq(fake_bin)
        return remote, client, fake_bin

    def test_missing_dependencies_fail_without_mutating_worktree(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            worktree = Path(temporary)
            marker = worktree / "note.md"
            marker.write_text("unchanged\n", encoding="utf-8")
            before = self.snapshot(worktree)

            result = self.run_preflight(worktree, "/usr/bin:/bin")

            self.assertNotEqual(0, result.returncode)
            self.assertIn("Missing required command: yq", result.stderr)
            self.assertEqual(before, self.snapshot(worktree))

    def test_unsupported_bash_version_reports_supported_environment_without_mutation(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            worktree = Path(temporary)
            marker = worktree / "note.md"
            marker.write_text("unchanged\n", encoding="utf-8")
            before = self.snapshot(worktree)

            result = subprocess.run(
                [
                    str(BASH),
                    "-c",
                    f"source '{to_msys_path(SCRIPT)}'; require_supported_bash 3",
                ],
                cwd=worktree,
                text=True,
                capture_output=True,
                check=False,
            )

            self.assertNotEqual(0, result.returncode)
            self.assertIn("Unsupported shell", result.stderr)
            self.assertIn("Bash 4", result.stderr)
            self.assertEqual(before, self.snapshot(worktree))

    def test_supported_dependencies_pass_without_mutating_worktree(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            worktree = Path(temporary)
            fake_bin = worktree / "bin"
            fake_bin.mkdir()
            self.write_fake_command(fake_bin, "git", "git version 2.30.0")
            self.write_fake_command(fake_bin, "yq", "yq (https://github.com/mikefarah/yq/) version v4.40.0")
            self.write_fake_command(fake_bin, "jq", "jq-1.6")
            self.write_fake_command(fake_bin, "python", "Python 3.9.0")
            self.write_fake_command(fake_bin, "sha256sum", "fake-sha256sum")
            marker = worktree / "note.md"
            marker.write_text("unchanged\n", encoding="utf-8")
            before = self.snapshot(worktree)
            bash_path = f"{to_msys_path(fake_bin)}:/usr/bin:/bin"

            result = self.run_preflight(worktree, bash_path)

            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn("Preflight passed", result.stdout)
            self.assertEqual(before, self.snapshot(worktree))

    def test_missing_iana_timezone_data_fails_without_mutating_worktree(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            worktree = Path(temporary)
            profile_dir = worktree / ".ebbinghaus"
            profile_dir.mkdir()
            profile_dir.joinpath("profile.json").write_text(
                '{"timezone":"Asia/Shanghai"}\n', encoding="utf-8"
            )
            fake_bin = worktree / "bin"
            fake_bin.mkdir()
            self.write_fake_command(fake_bin, "git", "git version 2.30.0")
            self.write_fake_command(fake_bin, "yq", "yq version v4.40.0")
            self.write_fake_command(fake_bin, "jq", "jq-1.6")
            self.write_fake_python_without_timezone_data(fake_bin)
            self.write_fake_command(fake_bin, "sha256sum", "fake-sha256sum")
            before = self.snapshot(worktree)
            bash_path = f"{to_msys_path(fake_bin)}:/usr/bin:/bin"

            result = self.run_preflight(worktree, bash_path)

            self.assertNotEqual(0, result.returncode)
            self.assertIn("Asia/Shanghai", result.stderr)
            self.assertIn("tzdata", result.stderr)
            self.assertEqual(before, self.snapshot(worktree))

    def test_invalid_profile_timezone_fails_without_tzdata_install_advice(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            worktree = Path(temporary)
            profile_dir = worktree / ".ebbinghaus"
            profile_dir.mkdir()
            profile_dir.joinpath("profile.json").write_text(
                '{"timezone":"Mars/Olympus"}\n', encoding="utf-8"
            )
            fake_bin = worktree / "bin"
            fake_bin.mkdir()
            self.write_fake_command(fake_bin, "git", "git version 2.30.0")
            self.write_fake_command(fake_bin, "yq", "yq version v4.40.0")
            self.write_fake_command(fake_bin, "jq", "jq-1.6")
            self.write_fake_command(fake_bin, "sha256sum", "fake-sha256sum")
            before = self.snapshot(worktree)
            bash_path = (
                f"{to_msys_path(fake_bin)}:{to_msys_path(Path(sys.executable).parent)}"
                ":/usr/bin:/bin"
            )

            result = self.run_preflight(worktree, bash_path)

            self.assertNotEqual(0, result.returncode)
            self.assertIn("Invalid IANA timezone in profile: Mars/Olympus", result.stderr)
            self.assertNotIn("install the tzdata package", result.stderr)
            self.assertEqual(before, self.snapshot(worktree))

    def test_new_creates_current_profile_date_markdown_draft(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            worktree = Path(temporary)
            subprocess.run(["git", "init", "-q", str(worktree)], check=True)
            profile_dir = worktree / ".ebbinghaus"
            profile_dir.mkdir()
            profile_dir.joinpath("profile.json").write_text(
                '{"timezone":"Asia/Shanghai"}\n', encoding="utf-8"
            )
            fake_bin = worktree / "test-bin"
            fake_bin.mkdir()
            self.write_fake_yq(fake_bin)
            self.write_fake_command(fake_bin, "jq", "jq-1.6")
            environment = self.script_environment(fake_bin)

            result = subprocess.run(
                [str(BASH), to_msys_path(SCRIPT), "new"],
                cwd=worktree,
                env=environment,
                text=True,
                capture_output=True,
                check=False,
            )

            self.assertEqual(0, result.returncode, result.stderr)
            relative = result.stdout.strip().splitlines()[-1]
            self.assertRegex(relative, r"^2026-07-22/notes/[0-9a-f-]{36}\.md$")
            draft = worktree / Path(relative)
            self.assertTrue(draft.is_file())
            content = draft.read_text(encoding="utf-8")
            self.assertIn("schema: ebbinghaus-note/v1", content)
            self.assertIn("parent_revision_ids: []", content)
            self.assertIn("revision_kind: create", content)
            self.assertRegex(content, r"note_id: [0-9a-f-]{36}")
            self.assertRegex(content, r"source_device_id: [0-9a-f-]{36}")
            self.assertIn("# New note\n", content)

    def test_revise_creates_complete_child_and_copies_referenced_assets(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            worktree = Path(temporary)
            subprocess.run(["git", "init", "-q", str(worktree)], check=True)
            subprocess.run(
                ["git", "-C", str(worktree), "config", "user.email", "test@example.com"],
                check=True,
            )
            subprocess.run(
                ["git", "-C", str(worktree), "config", "user.name", "Test User"],
                check=True,
            )
            profile_dir = worktree / ".ebbinghaus"
            profile_dir.mkdir()
            profile_dir.joinpath("profile.json").write_text(
                '{"timezone":"Asia/Shanghai"}\n', encoding="utf-8"
            )
            note_id = "018f0000-0000-7000-8000-000000000001"
            source_revision_id = "018f0000-0000-7000-8000-000000000002"
            asset_bytes = b"valid-image-bytes"
            asset_hash = hashlib.sha256(asset_bytes).hexdigest()
            source_asset = worktree / "2026-07-20" / "assets" / f"{asset_hash}.png"
            source_asset.parent.mkdir(parents=True)
            source_asset.write_bytes(asset_bytes)
            body = f"# Source note\n\n![diagram](../assets/{asset_hash}.png)\n".encode()
            source = worktree / "2026-07-20" / "notes" / f"{source_revision_id}.md"
            self.write_revision(source, note_id, source_revision_id, body)
            subprocess.run(["git", "-C", str(worktree), "add", "."], check=True)
            subprocess.run(
                ["git", "-C", str(worktree), "commit", "-qm", "source revision"],
                check=True,
            )
            source_before = source.read_bytes()
            fake_bin = worktree / "test-bin"
            fake_bin.mkdir()
            self.write_fake_yq(fake_bin)
            self.write_fake_command(fake_bin, "jq", "jq-1.6")

            result = subprocess.run(
                [
                    str(BASH),
                    to_msys_path(SCRIPT),
                    "revise",
                    "2026-07-20/notes/018f0000-0000-7000-8000-000000000002.md",
                ],
                cwd=worktree,
                env=self.script_environment(fake_bin),
                text=True,
                capture_output=True,
                check=False,
            )

            self.assertEqual(0, result.returncode, result.stderr)
            relative = result.stdout.strip().splitlines()[-1]
            self.assertRegex(relative, r"^2026-07-22/notes/[0-9a-f-]{36}\.md$")
            revised = worktree / Path(relative)
            revised_bytes = revised.read_bytes()
            revised_revision_id = revised.stem
            self.assertEqual(source_before, source.read_bytes())
            self.assertIn(f"note_id: {note_id}\n".encode(), revised_bytes)
            self.assertIn(f"revision_id: {revised_revision_id}\n".encode(), revised_bytes)
            self.assertIn(
                f"parent_revision_ids: [{source_revision_id}]\n".encode(), revised_bytes
            )
            self.assertIn(b"revision_kind: restart\n", revised_bytes)
            self.assertIn(b"authored_at: 2026-07-22T22:40:00+08:00\n", revised_bytes)
            self.assertIn(
                b"learning_started_at: 2026-07-22T22:40:00+08:00\n", revised_bytes
            )
            self.assertTrue(revised_bytes.endswith(body))
            copied_asset = worktree / "2026-07-22" / "assets" / source_asset.name
            self.assertEqual(asset_bytes, copied_asset.read_bytes())

    def test_revise_rejects_invalid_source_before_mutation(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            worktree = Path(temporary)
            subprocess.run(["git", "init", "-q", str(worktree)], check=True)
            subprocess.run(
                ["git", "-C", str(worktree), "config", "user.email", "test@example.com"],
                check=True,
            )
            subprocess.run(
                ["git", "-C", str(worktree), "config", "user.name", "Test User"],
                check=True,
            )
            profile_dir = worktree / ".ebbinghaus"
            profile_dir.mkdir()
            profile_dir.joinpath("profile.json").write_text(
                '{"timezone":"Asia/Shanghai"}\n', encoding="utf-8"
            )
            note_id = "018f0000-0000-7000-8000-000000000011"
            revision_id = "018f0000-0000-7000-8000-000000000012"
            source = worktree / "2026-07-20" / "notes" / f"{revision_id}.md"
            self.write_revision(source, note_id, revision_id, b"# Invalid source\n", kind="invalid")
            subprocess.run(["git", "-C", str(worktree), "add", "."], check=True)
            subprocess.run(
                ["git", "-C", str(worktree), "commit", "-qm", "invalid source"],
                check=True,
            )
            fake_bin = worktree / "test-bin"
            fake_bin.mkdir()
            self.write_fake_yq(fake_bin)
            self.write_fake_command(fake_bin, "jq", "jq-1.6")
            before = self.snapshot(worktree)

            result = subprocess.run(
                [str(BASH), to_msys_path(SCRIPT), "revise", source.relative_to(worktree).as_posix()],
                cwd=worktree,
                env=self.script_environment(fake_bin),
                text=True,
                capture_output=True,
                check=False,
            )

            self.assertNotEqual(0, result.returncode)
            self.assertIn("Invalid revision_kind", result.stderr)
            self.assertEqual(before, self.snapshot(worktree))

    def test_revise_rejects_deleted_note_before_mutation(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            worktree = Path(temporary)
            subprocess.run(["git", "init", "-q", str(worktree)], check=True)
            subprocess.run(
                ["git", "-C", str(worktree), "config", "user.email", "test@example.com"],
                check=True,
            )
            subprocess.run(
                ["git", "-C", str(worktree), "config", "user.name", "Test User"],
                check=True,
            )
            profile_dir = worktree / ".ebbinghaus"
            profile_dir.mkdir()
            profile_dir.joinpath("profile.json").write_text(
                '{"timezone":"Asia/Shanghai"}\n', encoding="utf-8"
            )
            note_id = "018f0000-0000-7000-8000-000000000021"
            revision_id = "018f0000-0000-7000-8000-000000000022"
            source = worktree / "2026-07-20" / "notes" / f"{revision_id}.md"
            self.write_revision(source, note_id, revision_id, b"# Deleted source\n")
            event_dir = worktree / "2026-07-21" / "events"
            event_dir.mkdir(parents=True)
            event_dir.joinpath("018f0000-0000-7000-8000-000000000023.json").write_text(
                "{\n"
                '  "event_id": "018f0000-0000-7000-8000-000000000023",\n'
                f'  "stream_id": "lifecycle:{note_id}",\n'
                '  "parent_event_ids": [],\n'
                '  "event_type": "delete"\n'
                "}\n",
                encoding="utf-8",
            )
            subprocess.run(["git", "-C", str(worktree), "add", "."], check=True)
            subprocess.run(
                ["git", "-C", str(worktree), "commit", "-qm", "deleted source"],
                check=True,
            )
            fake_bin = worktree / "test-bin"
            fake_bin.mkdir()
            self.write_fake_yq(fake_bin)
            self.write_fake_jq(fake_bin)
            before = self.snapshot(worktree)

            result = subprocess.run(
                [str(BASH), to_msys_path(SCRIPT), "revise", source.relative_to(worktree).as_posix()],
                cwd=worktree,
                env=self.script_environment(fake_bin),
                text=True,
                capture_output=True,
                check=False,
            )

            self.assertNotEqual(0, result.returncode)
            self.assertIn("belongs to a deleted note", result.stderr)
            self.assertEqual(before, self.snapshot(worktree))

    def test_revise_allows_note_restored_after_delete(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            worktree = Path(temporary)
            subprocess.run(["git", "init", "-q", str(worktree)], check=True)
            subprocess.run(
                ["git", "-C", str(worktree), "config", "user.email", "test@example.com"],
                check=True,
            )
            subprocess.run(
                ["git", "-C", str(worktree), "config", "user.name", "Test User"],
                check=True,
            )
            profile_dir = worktree / ".ebbinghaus"
            profile_dir.mkdir()
            profile_dir.joinpath("profile.json").write_text(
                '{"timezone":"Asia/Shanghai"}\n', encoding="utf-8"
            )
            note_id = "018f0000-0000-7000-8000-000000000031"
            revision_id = "018f0000-0000-7000-8000-000000000032"
            delete_id = "018f0000-0000-7000-8000-000000000033"
            restore_id = "018f0000-0000-7000-8000-000000000034"
            source = worktree / "2026-07-20" / "notes" / f"{revision_id}.md"
            self.write_revision(source, note_id, revision_id, b"# Restored source\n")
            event_dir = worktree / "2026-07-21" / "events"
            event_dir.mkdir(parents=True)
            event_dir.joinpath(f"{delete_id}.json").write_text(
                "{\n"
                f'  "event_id": "{delete_id}",\n'
                f'  "stream_id": "lifecycle:{note_id}",\n'
                '  "parent_event_ids": [],\n'
                '  "event_type": "delete"\n'
                "}\n",
                encoding="utf-8",
            )
            event_dir.joinpath(f"{restore_id}.json").write_text(
                "{\n"
                f'  "event_id": "{restore_id}",\n'
                f'  "stream_id": "lifecycle:{note_id}",\n'
                f'  "parent_event_ids": ["{delete_id}"],\n'
                '  "event_type": "restore"\n'
                "}\n",
                encoding="utf-8",
            )
            subprocess.run(["git", "-C", str(worktree), "add", "."], check=True)
            subprocess.run(
                ["git", "-C", str(worktree), "commit", "-qm", "restored source"],
                check=True,
            )
            fake_bin = worktree / "test-bin"
            fake_bin.mkdir()
            self.write_fake_yq(fake_bin)
            self.write_fake_jq(fake_bin)

            result = subprocess.run(
                [str(BASH), to_msys_path(SCRIPT), "revise", source.relative_to(worktree).as_posix()],
                cwd=worktree,
                env=self.script_environment(fake_bin),
                text=True,
                capture_output=True,
                check=False,
            )

            self.assertEqual(0, result.returncode, result.stderr)
            self.assertRegex(
                result.stdout.strip().splitlines()[-1],
                r"^2026-07-22/notes/[0-9a-f-]{36}\.md$",
            )

    def test_revise_rejects_malformed_asset_reference_before_mutation(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            worktree = Path(temporary)
            subprocess.run(["git", "init", "-q", str(worktree)], check=True)
            subprocess.run(
                ["git", "-C", str(worktree), "config", "user.email", "test@example.com"],
                check=True,
            )
            subprocess.run(
                ["git", "-C", str(worktree), "config", "user.name", "Test User"],
                check=True,
            )
            profile_dir = worktree / ".ebbinghaus"
            profile_dir.mkdir()
            profile_dir.joinpath("profile.json").write_text(
                '{"timezone":"Asia/Shanghai"}\n', encoding="utf-8"
            )
            note_id = "018f0000-0000-7000-8000-000000000041"
            revision_id = "018f0000-0000-7000-8000-000000000042"
            source = worktree / "2026-07-20" / "notes" / f"{revision_id}.md"
            self.write_revision(
                source,
                note_id,
                revision_id,
                b"# Invalid asset\n\n![broken](../assets/not-a-hash.png)\n",
            )
            subprocess.run(["git", "-C", str(worktree), "add", "."], check=True)
            subprocess.run(
                ["git", "-C", str(worktree), "commit", "-qm", "invalid asset source"],
                check=True,
            )
            fake_bin = worktree / "test-bin"
            fake_bin.mkdir()
            self.write_fake_yq(fake_bin)
            self.write_fake_jq(fake_bin)
            before = self.snapshot(worktree)

            result = subprocess.run(
                [str(BASH), to_msys_path(SCRIPT), "revise", source.relative_to(worktree).as_posix()],
                cwd=worktree,
                env=self.script_environment(fake_bin),
                text=True,
                capture_output=True,
                check=False,
            )

            self.assertNotEqual(0, result.returncode)
            self.assertIn("Invalid asset reference", result.stderr)
            self.assertEqual(before, self.snapshot(worktree))

    def test_revise_rejects_revision_graph_with_missing_parent(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            worktree = Path(temporary)
            subprocess.run(["git", "init", "-q", str(worktree)], check=True)
            subprocess.run(
                ["git", "-C", str(worktree), "config", "user.email", "test@example.com"],
                check=True,
            )
            subprocess.run(
                ["git", "-C", str(worktree), "config", "user.name", "Test User"],
                check=True,
            )
            profile_dir = worktree / ".ebbinghaus"
            profile_dir.mkdir()
            profile_dir.joinpath("profile.json").write_text(
                '{"timezone":"Asia/Shanghai"}\n', encoding="utf-8"
            )
            note_id = "018f0000-0000-7000-8000-000000000051"
            revision_id = "018f0000-0000-7000-8000-000000000052"
            missing_parent = "018f0000-0000-7000-8000-000000000053"
            source = worktree / "2026-07-20" / "notes" / f"{revision_id}.md"
            self.write_revision(
                source,
                note_id,
                revision_id,
                b"# Missing parent\n",
                parents=[missing_parent],
                kind="restart",
            )
            subprocess.run(["git", "-C", str(worktree), "add", "."], check=True)
            subprocess.run(
                ["git", "-C", str(worktree), "commit", "-qm", "missing parent source"],
                check=True,
            )
            fake_bin = worktree / "test-bin"
            fake_bin.mkdir()
            self.write_fake_yq(fake_bin)
            self.write_fake_jq(fake_bin)
            before = self.snapshot(worktree)

            result = subprocess.run(
                [str(BASH), to_msys_path(SCRIPT), "revise", source.relative_to(worktree).as_posix()],
                cwd=worktree,
                env=self.script_environment(fake_bin),
                text=True,
                capture_output=True,
                check=False,
            )

            self.assertNotEqual(0, result.returncode)
            self.assertIn(f"Missing parent revision: {missing_parent}", result.stderr)
            self.assertEqual(before, self.snapshot(worktree))

    def test_revise_rejects_content_conflict_before_mutation(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            worktree = Path(temporary)
            subprocess.run(["git", "init", "-q", str(worktree)], check=True)
            subprocess.run(
                ["git", "-C", str(worktree), "config", "user.email", "test@example.com"],
                check=True,
            )
            subprocess.run(
                ["git", "-C", str(worktree), "config", "user.name", "Test User"],
                check=True,
            )
            profile_dir = worktree / ".ebbinghaus"
            profile_dir.mkdir()
            profile_dir.joinpath("profile.json").write_text(
                '{"timezone":"Asia/Shanghai"}\n', encoding="utf-8"
            )
            note_id = "018f0000-0000-7000-8000-000000000061"
            parent_id = "018f0000-0000-7000-8000-000000000062"
            left_id = "018f0000-0000-7000-8000-000000000063"
            right_id = "018f0000-0000-7000-8000-000000000064"
            notes = worktree / "2026-07-20" / "notes"
            parent = notes / f"{parent_id}.md"
            left = notes / f"{left_id}.md"
            right = notes / f"{right_id}.md"
            self.write_revision(parent, note_id, parent_id, b"# Parent\n")
            self.write_revision(
                left, note_id, left_id, b"# Left\n", parents=[parent_id], kind="restart"
            )
            self.write_revision(
                right, note_id, right_id, b"# Right\n", parents=[parent_id], kind="restart"
            )
            subprocess.run(["git", "-C", str(worktree), "add", "."], check=True)
            subprocess.run(
                ["git", "-C", str(worktree), "commit", "-qm", "conflicted source"],
                check=True,
            )
            fake_bin = worktree / "test-bin"
            fake_bin.mkdir()
            self.write_fake_yq(fake_bin)
            self.write_fake_jq(fake_bin)
            before = self.snapshot(worktree)

            result = subprocess.run(
                [str(BASH), to_msys_path(SCRIPT), "revise", left.relative_to(worktree).as_posix()],
                cwd=worktree,
                env=self.script_environment(fake_bin),
                text=True,
                capture_output=True,
                check=False,
            )

            self.assertNotEqual(0, result.returncode)
            self.assertIn("not the uniquely active leaf", result.stderr)
            self.assertEqual(before, self.snapshot(worktree))

    def test_validate_reports_publishable_new_files_without_mutation(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            worktree = Path(temporary)
            subprocess.run(["git", "init", "-q", str(worktree)], check=True)
            subprocess.run(
                ["git", "-C", str(worktree), "config", "user.email", "test@example.com"],
                check=True,
            )
            subprocess.run(
                ["git", "-C", str(worktree), "config", "user.name", "Test User"],
                check=True,
            )
            self.write_valid_profile(worktree)
            subprocess.run(["git", "-C", str(worktree), "add", ".ebbinghaus/profile.json"], check=True)
            subprocess.run(
                ["git", "-C", str(worktree), "commit", "-qm", "initialize profile"],
                check=True,
            )
            revision_id = "018f0000-0000-7000-8000-000000000112"
            draft = worktree / "2026-07-22" / "notes" / f"{revision_id}.md"
            self.write_revision(
                draft,
                "018f0000-0000-7000-8000-000000000111",
                revision_id,
                b"# Publishable draft\n",
            )
            fake_bin = worktree / "test-bin"
            fake_bin.mkdir()
            self.write_fake_yq(fake_bin)
            self.write_fake_jq(fake_bin)
            before = self.snapshot(worktree)

            result = subprocess.run(
                [str(BASH), to_msys_path(SCRIPT), "validate"],
                cwd=worktree,
                env=self.script_environment(fake_bin),
                text=True,
                capture_output=True,
                check=False,
            )

            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn("Repository is valid", result.stdout)
            self.assertIn(draft.relative_to(worktree).as_posix(), result.stdout)
            self.assertEqual(before, self.snapshot(worktree))

    def test_validate_reports_missing_asset_without_mutation(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            worktree = Path(temporary)
            subprocess.run(["git", "init", "-q", str(worktree)], check=True)
            self.write_valid_profile(worktree)
            missing_hash = "a" * 64
            revision_id = "018f0000-0000-7000-8000-000000000122"
            draft = worktree / "2026-07-22" / "notes" / f"{revision_id}.md"
            self.write_revision(
                draft,
                "018f0000-0000-7000-8000-000000000121",
                revision_id,
                f"# Missing asset\n\n![missing](../assets/{missing_hash}.png)\n".encode(),
            )
            fake_bin = worktree / "test-bin"
            fake_bin.mkdir()
            self.write_fake_yq(fake_bin)
            self.write_fake_jq(fake_bin)
            before = self.snapshot(worktree)

            result = subprocess.run(
                [str(BASH), to_msys_path(SCRIPT), "validate"],
                cwd=worktree,
                env=self.script_environment(fake_bin),
                text=True,
                capture_output=True,
                check=False,
            )

            self.assertNotEqual(0, result.returncode)
            self.assertIn("ASSET_REFERENCE", result.stderr)
            self.assertIn(draft.relative_to(worktree).as_posix(), result.stderr.replace("\\", "/"))
            self.assertEqual(before, self.snapshot(worktree))

    def test_validate_rejects_modified_tracked_note_and_requires_revise(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            worktree = Path(temporary)
            subprocess.run(["git", "init", "-q", str(worktree)], check=True)
            subprocess.run(
                ["git", "-C", str(worktree), "config", "user.email", "test@example.com"],
                check=True,
            )
            subprocess.run(
                ["git", "-C", str(worktree), "config", "user.name", "Test User"],
                check=True,
            )
            self.write_valid_profile(worktree)
            revision_id = "018f0000-0000-7000-8000-000000000132"
            note = worktree / "2026-07-20" / "notes" / f"{revision_id}.md"
            self.write_revision(
                note,
                "018f0000-0000-7000-8000-000000000131",
                revision_id,
                b"# Original body\n",
            )
            subprocess.run(["git", "-C", str(worktree), "add", "."], check=True)
            subprocess.run(
                ["git", "-C", str(worktree), "commit", "-qm", "tracked note"],
                check=True,
            )
            self.write_revision(
                note,
                "018f0000-0000-7000-8000-000000000131",
                revision_id,
                b"# Directly edited body\n",
            )
            fake_bin = worktree / "test-bin"
            fake_bin.mkdir()
            self.write_fake_yq(fake_bin)
            self.write_fake_jq(fake_bin)
            before = self.snapshot(worktree)

            result = subprocess.run(
                [str(BASH), to_msys_path(SCRIPT), "validate"],
                cwd=worktree,
                env=self.script_environment(fake_bin),
                text=True,
                capture_output=True,
                check=False,
            )

            self.assertNotEqual(0, result.returncode)
            self.assertIn("Tracked repository data was modified or deleted", result.stderr)
            self.assertIn("revise", result.stderr)
            self.assertEqual(before, self.snapshot(worktree))
            self.assertEqual([], subprocess.run(
                ["git", "-C", str(worktree), "diff", "--cached", "--name-only"],
                text=True,
                capture_output=True,
                check=True,
            ).stdout.splitlines())

    def test_validate_rejects_unknown_staged_path_without_changing_index(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            worktree = Path(temporary)
            subprocess.run(["git", "init", "-q", str(worktree)], check=True)
            self.write_valid_profile(worktree)
            unrelated = worktree / "private-token.txt"
            unrelated.write_text("not repository data\n", encoding="utf-8")
            subprocess.run(["git", "-C", str(worktree), "add", "private-token.txt"], check=True)
            fake_bin = worktree / "test-bin"
            fake_bin.mkdir()
            self.write_fake_yq(fake_bin)
            self.write_fake_jq(fake_bin)
            staged_before = subprocess.run(
                ["git", "-C", str(worktree), "diff", "--cached", "--name-only"],
                text=True,
                capture_output=True,
                check=True,
            ).stdout

            result = subprocess.run(
                [str(BASH), to_msys_path(SCRIPT), "validate"],
                cwd=worktree,
                env=self.script_environment(fake_bin),
                text=True,
                capture_output=True,
                check=False,
            )

            self.assertNotEqual(0, result.returncode)
            self.assertIn("Unknown staged path: private-token.txt", result.stderr)
            staged_after = subprocess.run(
                ["git", "-C", str(worktree), "diff", "--cached", "--name-only"],
                text=True,
                capture_output=True,
                check=True,
            ).stdout
            self.assertEqual(staged_before, staged_after)

    def test_validate_rejects_reuse_of_path_found_in_git_history(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            worktree = Path(temporary)
            subprocess.run(["git", "init", "-q", str(worktree)], check=True)
            subprocess.run(
                ["git", "-C", str(worktree), "config", "user.email", "test@example.com"],
                check=True,
            )
            subprocess.run(
                ["git", "-C", str(worktree), "config", "user.name", "Test User"],
                check=True,
            )
            self.write_valid_profile(worktree)
            revision_id = "018f0000-0000-7000-8000-000000000142"
            note = worktree / "2026-07-20" / "notes" / f"{revision_id}.md"
            self.write_revision(
                note,
                "018f0000-0000-7000-8000-000000000141",
                revision_id,
                b"# Historical path\n",
            )
            historical_bytes = note.read_bytes()
            subprocess.run(["git", "-C", str(worktree), "add", "."], check=True)
            subprocess.run(
                ["git", "-C", str(worktree), "commit", "-qm", "add historical path"],
                check=True,
            )
            subprocess.run(["git", "-C", str(worktree), "rm", "-q", note.relative_to(worktree)], check=True)
            subprocess.run(
                ["git", "-C", str(worktree), "commit", "-qm", "remove historical path"],
                check=True,
            )
            note.parent.mkdir(parents=True, exist_ok=True)
            note.write_bytes(historical_bytes)
            fake_bin = worktree / "test-bin"
            fake_bin.mkdir()
            self.write_fake_yq(fake_bin)
            self.write_fake_jq(fake_bin)
            before = self.snapshot(worktree)

            result = subprocess.run(
                [str(BASH), to_msys_path(SCRIPT), "validate"],
                cwd=worktree,
                env=self.script_environment(fake_bin),
                text=True,
                capture_output=True,
                check=False,
            )

            relative = note.relative_to(worktree).as_posix()
            self.assertNotEqual(0, result.returncode)
            self.assertIn(f"Path already exists in Git history: {relative}", result.stderr)
            self.assertEqual(before, self.snapshot(worktree))

    def test_validate_rejects_history_rewrite_state(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            worktree = Path(temporary)
            subprocess.run(["git", "init", "-q", str(worktree)], check=True)
            self.write_valid_profile(worktree)
            git_dir = Path(
                subprocess.run(
                    ["git", "-C", str(worktree), "rev-parse", "--absolute-git-dir"],
                    text=True,
                    capture_output=True,
                    check=True,
                ).stdout.strip()
            )
            (git_dir / "rebase-merge").mkdir()
            fake_bin = worktree / "test-bin"
            fake_bin.mkdir()
            self.write_fake_yq(fake_bin)
            self.write_fake_jq(fake_bin)

            result = subprocess.run(
                [str(BASH), to_msys_path(SCRIPT), "validate"],
                cwd=worktree,
                env=self.script_environment(fake_bin),
                text=True,
                capture_output=True,
                check=False,
            )

            self.assertNotEqual(0, result.returncode)
            self.assertIn("Git history operation is in progress", result.stderr)

    def test_pull_fast_forwards_clean_worktree_and_validates_repository(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            remote = root / "remote.git"
            seed = root / "seed"
            client = root / "client"
            subprocess.run(["git", "init", "--bare", "-q", str(remote)], check=True)
            subprocess.run(["git", "clone", "-q", str(remote), str(seed)], check=True)
            subprocess.run(["git", "-C", str(seed), "config", "user.email", "test@example.com"], check=True)
            subprocess.run(["git", "-C", str(seed), "config", "user.name", "Test User"], check=True)
            self.write_valid_profile(seed)
            subprocess.run(["git", "-C", str(seed), "add", "."], check=True)
            subprocess.run(["git", "-C", str(seed), "commit", "-qm", "initialize"], check=True)
            subprocess.run(["git", "-C", str(seed), "push", "-qu", "origin", "HEAD"], check=True)
            subprocess.run(
                ["git", "-c", "core.autocrlf=false", "clone", "-q", str(remote), str(client)],
                check=True,
            )
            subprocess.run(
                ["git", "-C", str(client), "config", "core.autocrlf", "false"], check=True
            )
            revision_id = "018f0000-0000-7000-8000-000000000152"
            remote_note = seed / "2026-07-22" / "notes" / f"{revision_id}.md"
            self.write_revision(
                remote_note,
                "018f0000-0000-7000-8000-000000000151",
                revision_id,
                b"# Pulled note\n",
            )
            subprocess.run(["git", "-C", str(seed), "add", "."], check=True)
            subprocess.run(["git", "-C", str(seed), "commit", "-qm", "remote note"], check=True)
            subprocess.run(["git", "-C", str(seed), "push", "-q"], check=True)
            fake_bin = root / "test-bin"
            fake_bin.mkdir()
            self.write_fake_yq(fake_bin)
            self.write_fake_jq(fake_bin)

            result = subprocess.run(
                [str(BASH), to_msys_path(SCRIPT), "pull"],
                cwd=client,
                env=self.script_environment(fake_bin),
                text=True,
                capture_output=True,
                check=False,
            )

            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn("Repository is valid", result.stdout)
            self.assertEqual(remote_note.read_bytes(), (client / remote_note.relative_to(seed)).read_bytes())

    def test_pull_rejects_dirty_worktree_without_stashing_or_overwriting(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            worktree = Path(temporary)
            subprocess.run(["git", "init", "-q", str(worktree)], check=True)
            self.write_valid_profile(worktree)
            dirty = worktree / "local-draft.md"
            dirty.write_text("keep me\n", encoding="utf-8")
            fake_bin = worktree / "test-bin"
            fake_bin.mkdir()
            self.write_fake_yq(fake_bin)
            self.write_fake_jq(fake_bin)
            before = self.snapshot(worktree)

            result = subprocess.run(
                [str(BASH), to_msys_path(SCRIPT), "pull"],
                cwd=worktree,
                env=self.script_environment(fake_bin),
                text=True,
                capture_output=True,
                check=False,
            )

            self.assertNotEqual(0, result.returncode)
            self.assertIn("worktree must be clean", result.stderr.lower())
            self.assertEqual(before, self.snapshot(worktree))

    def test_publish_confirms_commits_only_valid_new_files_and_pushes(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            remote = root / "remote.git"
            seed = root / "seed"
            client = root / "client"
            fake_bin = root / "test-bin"
            subprocess.run(["git", "init", "--bare", "-q", str(remote)], check=True)
            subprocess.run(["git", "clone", "-q", str(remote), str(seed)], check=True)
            subprocess.run(["git", "-C", str(seed), "config", "user.email", "test@example.com"], check=True)
            subprocess.run(["git", "-C", str(seed), "config", "user.name", "Test User"], check=True)
            self.write_valid_profile(seed)
            subprocess.run(["git", "-C", str(seed), "add", "."], check=True)
            subprocess.run(["git", "-C", str(seed), "commit", "-qm", "initialize"], check=True)
            subprocess.run(["git", "-C", str(seed), "push", "-qu", "origin", "HEAD"], check=True)
            subprocess.run(
                ["git", "-c", "core.autocrlf=false", "clone", "-q", str(remote), str(client)],
                check=True,
            )
            subprocess.run(["git", "-C", str(client), "config", "core.autocrlf", "false"], check=True)
            subprocess.run(["git", "-C", str(client), "config", "user.email", "test@example.com"], check=True)
            subprocess.run(["git", "-C", str(client), "config", "user.name", "Test User"], check=True)
            subprocess.run(
                [
                    "git",
                    "-C",
                    str(client),
                    "config",
                    "ebbinghaus.deviceId",
                    "018f0000-0000-7000-8000-000000000163",
                ],
                check=True,
            )
            revision_id = "018f0000-0000-7000-8000-000000000162"
            draft = client / "2026-07-22" / "notes" / f"{revision_id}.md"
            self.write_revision(
                draft,
                "018f0000-0000-7000-8000-000000000161",
                revision_id,
                b"# Published note\n",
            )
            fake_bin.mkdir()
            self.write_fake_yq(fake_bin)
            self.write_fake_jq(fake_bin)

            result = subprocess.run(
                [str(BASH), to_msys_path(SCRIPT), "publish"],
                cwd=client,
                env=self.script_environment(fake_bin),
                input="y\n",
                text=True,
                capture_output=True,
                check=False,
            )

            relative = draft.relative_to(client).as_posix()
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn(relative, result.stdout)
            self.assertEqual("", subprocess.run(
                ["git", "-C", str(client), "status", "--porcelain"],
                text=True,
                capture_output=True,
                check=True,
            ).stdout, result.stdout)
            message = subprocess.run(
                ["git", "-C", str(client), "log", "-1", "--format=%B"],
                text=True,
                capture_output=True,
                check=True,
            ).stdout
            self.assertIn("notes(2026-07-22): publish desktop revisions", message)
            self.assertRegex(message, r"Ebbinghaus-Batch-Id: [0-9a-f-]{36}")
            remote_paths = subprocess.run(
                ["git", "--git-dir", str(remote), "ls-tree", "-r", "--name-only", "HEAD"],
                text=True,
                capture_output=True,
                check=True,
            ).stdout.splitlines()
            self.assertIn(relative, remote_paths)

    def test_publish_decline_does_not_stage_commit_or_push(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            remote = root / "remote.git"
            client = root / "client"
            fake_bin = root / "test-bin"
            subprocess.run(["git", "init", "--bare", "-q", str(remote)], check=True)
            subprocess.run(["git", "init", "-q", str(client)], check=True)
            subprocess.run(["git", "-C", str(client), "config", "user.email", "test@example.com"], check=True)
            subprocess.run(["git", "-C", str(client), "config", "user.name", "Test User"], check=True)
            subprocess.run(["git", "-C", str(client), "config", "core.autocrlf", "false"], check=True)
            subprocess.run(["git", "-C", str(client), "remote", "add", "origin", str(remote)], check=True)
            self.write_valid_profile(client)
            subprocess.run(["git", "-C", str(client), "add", ".ebbinghaus/profile.json"], check=True)
            subprocess.run(["git", "-C", str(client), "commit", "-qm", "initialize"], check=True)
            subprocess.run(["git", "-C", str(client), "push", "-qu", "origin", "HEAD"], check=True)
            revision_id = "018f0000-0000-7000-8000-000000000172"
            draft = client / "2026-07-22" / "notes" / f"{revision_id}.md"
            self.write_revision(
                draft,
                "018f0000-0000-7000-8000-000000000171",
                revision_id,
                b"# Declined note\n",
            )
            fake_bin.mkdir()
            self.write_fake_yq(fake_bin)
            self.write_fake_jq(fake_bin)
            head_before = subprocess.run(
                ["git", "-C", str(client), "rev-parse", "HEAD"],
                text=True,
                capture_output=True,
                check=True,
            ).stdout.strip()

            result = subprocess.run(
                [str(BASH), to_msys_path(SCRIPT), "publish"],
                cwd=client,
                env=self.script_environment(fake_bin),
                input="n\n",
                text=True,
                capture_output=True,
                check=False,
            )

            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn("Publication cancelled", result.stdout)
            self.assertEqual("", subprocess.run(
                ["git", "-C", str(client), "diff", "--cached", "--name-only"],
                text=True,
                capture_output=True,
                check=True,
            ).stdout)
            self.assertEqual(head_before, subprocess.run(
                ["git", "-C", str(client), "rev-parse", "HEAD"],
                text=True,
                capture_output=True,
                check=True,
            ).stdout.strip())
            self.assertEqual(head_before, subprocess.run(
                ["git", "--git-dir", str(remote), "rev-parse", "HEAD"],
                text=True,
                capture_output=True,
                check=True,
            ).stdout.strip())

    def test_publish_push_rejection_preserves_local_commit_without_force(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            remote = root / "remote.git"
            client = root / "client"
            fake_bin = root / "test-bin"
            subprocess.run(["git", "init", "--bare", "-q", str(remote)], check=True)
            subprocess.run(["git", "init", "-q", str(client)], check=True)
            subprocess.run(["git", "-C", str(client), "config", "user.email", "test@example.com"], check=True)
            subprocess.run(["git", "-C", str(client), "config", "user.name", "Test User"], check=True)
            subprocess.run(["git", "-C", str(client), "config", "core.autocrlf", "false"], check=True)
            subprocess.run(["git", "-C", str(client), "remote", "add", "origin", str(remote)], check=True)
            self.write_valid_profile(client)
            subprocess.run(["git", "-C", str(client), "add", ".ebbinghaus/profile.json"], check=True)
            subprocess.run(["git", "-C", str(client), "commit", "-qm", "initialize"], check=True)
            subprocess.run(["git", "-C", str(client), "push", "-qu", "origin", "HEAD"], check=True)
            remote_head_before = subprocess.run(
                ["git", "--git-dir", str(remote), "rev-parse", "HEAD"],
                text=True,
                capture_output=True,
                check=True,
            ).stdout.strip()
            hook = remote / "hooks" / "pre-receive"
            hook.write_text(
                "#!/usr/bin/env bash\n"
                "while read -r old new ref; do\n"
                "  git update-ref \"$ref\" \"$old\"\n"
                "  echo 'simulated remote advancement' >&2\n"
                "  exit 1\n"
                "done\n",
                encoding="utf-8",
                newline="\n",
            )
            hook.chmod(0o755)
            revision_id = "018f0000-0000-7000-8000-000000000182"
            draft = client / "2026-07-22" / "notes" / f"{revision_id}.md"
            self.write_revision(
                draft,
                "018f0000-0000-7000-8000-000000000181",
                revision_id,
                b"# Rejected push note\n",
            )
            fake_bin.mkdir()
            self.write_fake_yq(fake_bin)
            self.write_fake_jq(fake_bin)

            result = subprocess.run(
                [str(BASH), to_msys_path(SCRIPT), "publish"],
                cwd=client,
                env=self.script_environment(fake_bin),
                input="y\n",
                text=True,
                capture_output=True,
                check=False,
            )

            self.assertNotEqual(0, result.returncode)
            self.assertIn("simulated remote advancement", result.stderr)
            local_head = subprocess.run(
                ["git", "-C", str(client), "rev-parse", "HEAD"],
                text=True,
                capture_output=True,
                check=True,
            ).stdout.strip()
            self.assertNotEqual(remote_head_before, local_head)
            remote_head_after = subprocess.run(
                ["git", "--git-dir", str(remote), "rev-parse", "HEAD"],
                text=True,
                capture_output=True,
                check=True,
            ).stdout.strip()
            self.assertEqual(remote_head_before, remote_head_after)

    def test_publish_allows_multiple_ordinary_commits_in_the_same_daily_directory(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            remote, client, fake_bin = self.initialize_publishing_repository(root)
            environment = self.script_environment(fake_bin)
            note_ids = [
                "018f0000-0000-7000-8000-000000000194",
                "018f0000-0000-7000-8000-000000000196",
            ]
            revision_ids = [
                "018f0000-0000-7000-8000-000000000195",
                "018f0000-0000-7000-8000-000000000197",
            ]

            for index, (note_id, revision_id) in enumerate(
                zip(note_ids, revision_ids, strict=True), start=1
            ):
                draft = client / "2026-07-22" / "notes" / f"{revision_id}.md"
                self.write_revision(
                    draft,
                    note_id,
                    revision_id,
                    f"# Same-day note {index}\n".encode(),
                )
                result = subprocess.run(
                    [str(BASH), to_msys_path(SCRIPT), "publish"],
                    cwd=client,
                    env=environment,
                    input="y\n",
                    text=True,
                    capture_output=True,
                    check=False,
                )
                self.assertEqual(0, result.returncode, result.stderr)

            subjects = subprocess.run(
                ["git", "-C", str(client), "log", "-2", "--format=%s"],
                text=True,
                capture_output=True,
                check=True,
            ).stdout.splitlines()
            self.assertEqual(2, len(subjects))
            self.assertTrue(
                all(subject.startswith("notes(2026-07-22):") for subject in subjects)
            )
            remote_paths = subprocess.run(
                ["git", "--git-dir", str(remote), "ls-tree", "-r", "--name-only", "HEAD"],
                text=True,
                capture_output=True,
                check=True,
            ).stdout.splitlines()
            for revision_id in revision_ids:
                self.assertIn(f"2026-07-22/notes/{revision_id}.md", remote_paths)

    def test_publish_never_leaks_pat_from_environment_to_output_files_or_history(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            _remote, client, fake_bin = self.initialize_publishing_repository(root)
            sentinel = "gitee_pat_DO_NOT_LEAK_018f000000000000"
            revision_id = "018f0000-0000-7000-8000-000000000199"
            draft = client / "2026-07-22" / "notes" / f"{revision_id}.md"
            self.write_revision(
                draft,
                "018f0000-0000-7000-8000-000000000198",
                revision_id,
                b"# Credential isolation\n",
            )
            environment = self.script_environment(fake_bin)
            environment["GITEE_PRIVATE_TOKEN"] = sentinel
            environment["GIT_TRACE"] = "1"

            result = subprocess.run(
                [str(BASH), to_msys_path(SCRIPT), "publish"],
                cwd=client,
                env=environment,
                input="y\n",
                text=True,
                capture_output=True,
                check=False,
            )

            self.assertEqual(0, result.returncode, result.stderr)
            self.assertNotIn(sentinel, result.stdout)
            self.assertNotIn(sentinel, result.stderr)
            history = subprocess.run(
                ["git", "-C", str(client), "log", "--all", "-p"],
                text=True,
                capture_output=True,
                check=True,
            ).stdout
            self.assertNotIn(sentinel, history)
            for path in client.rglob("*"):
                if path.is_file():
                    self.assertNotIn(sentinel.encode(), path.read_bytes(), str(path))


if __name__ == "__main__":
    unittest.main()
