import json
import subprocess
import unittest
from unittest.mock import patch

from ci_scope import classify, docs_only, verified_main_run

BASE = "a" * 40
HEAD = "b" * 40
MERGE = "c" * 40
ENV = {
    "GITHUB_EVENT_NAME": "pull_request", "BASE_REF": "main",
    "BASE_SHA": BASE, "GITHUB_REPOSITORY": "owner/repo",
}


def diff(path, status=b"M", old=b"100644", new=b"100644"):
    return b":" + old + b" " + new + b" abcdef0 1234567 " + status + b"\0" + path + b"\0"


def run(**changes):
    return dict({"head_sha": BASE, "head_branch": "main", "event": "push",
                 "status": "completed", "conclusion": "success", "id": 42}, **changes)


class CiScopeTest(unittest.TestCase):
    def test_allowlist_only(self):
        docs = diff(b"README.md") + diff(b"docs/M05-TASKS.md")
        self.assertTrue(docs_only(docs))
        for raw in (b"", docs[:-1], docs + diff(b"app/src/main/Main.kt"),
                    diff(b"docs/new.md"), diff(b"README.md", b"A"),
                    diff(b"README.md", b"D"), diff(b"README.md", new=b"100755"),
                    diff(b"README.md", b"T", new=b"120000"),
                    docs + diff(b".github/workflows/android.yml"),
                    docs + diff(b"AGENTS.md"), docs + diff(b"rust/Cargo.lock")):
            with self.subTest(raw=raw):
                self.assertFalse(docs_only(raw))

    def test_exact_latest_success(self):
        self.assertEqual(42, verified_main_run({"workflow_runs": [run()]}, BASE))
        for changes in ({"head_sha": HEAD}, {"head_branch": "dev"},
                        {"event": "pull_request"}, {"status": "in_progress"},
                        {"conclusion": "failure"}, {"conclusion": "cancelled"}):
            with self.subTest(changes=changes):
                self.assertIsNone(verified_main_run({"workflow_runs": [run(**changes), run()]}, BASE))
        self.assertIsNone(verified_main_run({"workflow_runs": []}, BASE))

    @patch("ci_scope.subprocess.check_output")
    def test_full_merge_diff_and_verified_base(self, command):
        command.side_effect = [
            f"{MERGE} {BASE} {HEAD}\n".encode(),
            diff(b"README.md"),
            json.dumps({"workflow_runs": [run()]}).encode(),
        ]
        self.assertEqual("docs", classify(ENV)[0])
        self.assertEqual(("git", "diff", "--raw", "-z", "--no-renames", BASE, "HEAD"),
                         tuple(command.call_args_list[1].args[0]))
        self.assertIn(f"head_sha={BASE}", command.call_args_list[2].args[0])

    @patch("ci_scope.subprocess.check_output")
    def test_mixed_history_never_skips(self, command):
        command.side_effect = [f"{MERGE} {BASE} {HEAD}\n".encode(),
                               diff(b"README.md") + diff(b"app/build.gradle.kts")]
        self.assertEqual("full", classify(ENV)[0])
        self.assertEqual(2, command.call_count)

    @patch("ci_scope.subprocess.check_output")
    def test_missing_evidence_defaults_full(self, command):
        for outputs in ([f"{MERGE} {HEAD}\n".encode()],
                        [f"{MERGE} {HEAD} {BASE}\n".encode()],
                        [f"{MERGE} {BASE} {HEAD}\n".encode(), diff(b"README.md"),
                         subprocess.CalledProcessError(1, "gh")],
                        [f"{MERGE} {BASE} {HEAD}\n".encode(), diff(b"README.md"), b"not json"],
                        [f"{MERGE} {BASE} {HEAD}\n".encode(), diff(b"README.md"),
                         json.dumps({"workflow_runs": [run(conclusion="failure")]}).encode()]):
            command.side_effect = outputs
            self.assertEqual("full", classify(ENV)[0])

    @patch("ci_scope.subprocess.check_output")
    def test_main_manual_and_other_base_are_full(self, command):
        for env in (dict(ENV, GITHUB_EVENT_NAME="push"),
                    dict(ENV, GITHUB_EVENT_NAME="workflow_dispatch"),
                    dict(ENV, BASE_REF="dev")):
            self.assertEqual("full", classify(env)[0])
        command.assert_not_called()


if __name__ == "__main__":
    unittest.main()
