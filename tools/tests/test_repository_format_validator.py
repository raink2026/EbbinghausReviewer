import json
import subprocess
import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
VALIDATOR = ROOT / "tools" / "validate_repository_format.py"
FIXTURES = ROOT / "repository-format" / "v1" / "fixtures"
SCHEMAS = ROOT / "repository-format" / "v1" / "schemas"


class RepositoryFormatValidatorTest(unittest.TestCase):
    def run_validator(self, fixture: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, str(VALIDATOR), str(FIXTURES / fixture), "--json"],
            cwd=ROOT,
            text=True,
            capture_output=True,
            check=False,
        )

    def test_v1_schemas_are_versioned_json_schema_documents(self) -> None:
        expected_ids = {
            "profile.schema.json": "https://ebbinghaus-review.local/schema/v1/profile.schema.json",
            "note.schema.json": "https://ebbinghaus-review.local/schema/v1/note.schema.json",
            "event.schema.json": "https://ebbinghaus-review.local/schema/v1/event.schema.json",
        }

        for filename, expected_id in expected_ids.items():
            with self.subTest(filename=filename):
                document = json.loads((SCHEMAS / filename).read_text(encoding="utf-8"))
                self.assertEqual("https://json-schema.org/draft/2020-12/schema", document["$schema"])
                self.assertEqual(expected_id, document["$id"])
                self.assertFalse(document["additionalProperties"])

    def test_valid_repository_fixture_passes(self) -> None:
        result = self.run_validator("valid/repository")

        self.assertEqual(0, result.returncode, result.stderr)
        report = json.loads(result.stdout)
        self.assertTrue(report["valid"])
        self.assertEqual([], report["issues"])

    def test_graph_fixture_catalog_covers_required_states(self) -> None:
        catalog = json.loads((FIXTURES / "graph-cases.json").read_text(encoding="utf-8"))

        self.assertEqual(
            {"create", "restart", "divergent", "merge", "cycle", "missing-parent"},
            {case["name"] for case in catalog["revision_cases"]},
        )
        self.assertEqual(
            {
                "linear-review",
                "concurrent-review",
                "review-merge",
                "delete",
                "restore",
                "lifecycle-conflict",
                "stale-event",
                "event-id-reuse",
            },
            {case["name"] for case in catalog["event_cases"]},
        )

    def test_invalid_repository_fixtures_report_expected_codes(self) -> None:
        cases = {
            "invalid/profile-schema/repository": "PROFILE_SCHEMA",
            "invalid/note-uuid/repository": "NOTE_SCHEMA",
            "invalid/note-date-path/repository": "NOTE_PATH",
            "invalid/note-body-hash/repository": "NOTE_BODY_HASH",
            "invalid/asset-path/repository": "ASSET_REFERENCE",
            "invalid/revision-cycle/repository": "REVISION_CYCLE",
            "invalid/revision-missing-parent/repository": "REVISION_MISSING_PARENT",
            "invalid/event-missing-parent/repository": "EVENT_MISSING_PARENT",
            "invalid/event-id-reuse/repository": "EVENT_ID_REUSE",
        }

        for fixture, expected_code in cases.items():
            with self.subTest(fixture=fixture):
                result = self.run_validator(fixture)
                self.assertEqual(1, result.returncode, result.stderr)
                report = json.loads(result.stdout)
                self.assertFalse(report["valid"])
                self.assertIn(expected_code, {issue["code"] for issue in report["issues"]})

    def test_graph_cases_contain_executable_nodes(self) -> None:
        catalog = json.loads((FIXTURES / "graph-cases.json").read_text(encoding="utf-8"))

        for section in ("revision_cases", "event_cases"):
            for case in catalog[section]:
                with self.subTest(section=section, case=case["name"]):
                    self.assertGreater(len(case["nodes"]), 0)
                    ids = {node["id"] for node in case["nodes"]}
                    self.assertEqual(len(case["nodes"]), len(ids))
                    if case["valid"]:
                        referenced = {parent for node in case["nodes"] for parent in node["parents"]}
                        self.assertTrue(referenced.issubset(ids))
                        self.assertEqual(set(case["leaves"]), ids - referenced)


if __name__ == "__main__":
    unittest.main()
