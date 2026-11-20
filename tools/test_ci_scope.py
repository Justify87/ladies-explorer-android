import json
import subprocess
import tempfile
from pathlib import Path
import unittest
from unittest.mock import patch

from ci_scope import DOCS, classify, docs_only, verified_main_run

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

    def test_explicit_documentation_policy(self):
        expected = {
            b"README.md",
            b"docs/ROADMAP.md",
            b"docs/M05-PLAN.md",
            b"docs/M05-TASKS.md",
            b"docs/M05-PARITY.md",
            b"docs/ARCHITECTURE.md",
            b"docs/SOURCE.md",
            b"docs/DATA-IMPORT.md",
            b"docs/DIAGNOSTICS.md",
            b"docs/MAP.md",
            b"tools/M05-REFERENCE.md",
        }
        self.assertEqual(expected, DOCS)
        for path in expected:
            with self.subTest(path=path):
                self.assertTrue(docs_only(diff(path)))
        self.assertTrue(docs_only(b"".join(diff(path) for path in sorted(expected))))
        for path in (b"AGENTS.md", b"docs/PROVENANCE.md", b".gitignore",
                     b"LICENSE", b"NOTICE", b"docs/unknown.md", b"tools/m05-reference.py",
                     b"app/src/main/README.md", b"rust/README.md", b"tools/other.md"):
            with self.subTest(path=path):
                self.assertFalse(docs_only(diff(path)))
                self.assertFalse(docs_only(diff(b"README.md") + diff(path)))

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



class RealGitPolicyTest(unittest.TestCase):
    """Use Git's actual NUL-delimited raw diff, not just synthetic headers."""

    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name)
        self.git("init", "-q", "-b", "main")
        self.git("config", "user.email", "ci@example.invalid")
        self.git("config", "user.name", "CI scope test")
        self.git("config", "core.filemode", "true")
        self.git("config", "commit.gpgsign", "false")
        self.git("config", "core.hooksPath", str(self.root / "no-hooks"))
        self.write("README.md", "baseline\n")
        self.write("docs/ARCHITECTURE.md", "baseline\n")
        self.write("app/build.gradle.kts", "baseline\n")
        self.git("add", ".")
        self.git("commit", "-qm", "base")
        self.base = self.git("rev-parse", "HEAD").decode().strip()

    def git(self, *args):
        return subprocess.check_output(["git", *args], cwd=self.root, timeout=30)

    def write(self, path, contents):
        file = self.root / path
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(contents, encoding="utf-8")

    def commit(self, message):
        self.git("add", "-A")
        self.git("commit", "-qm", message)

    def raw_diff(self):
        return self.git("diff", "--raw", "-z", "--no-renames", self.base, "HEAD")

    def test_real_merge_diff_allows_docs(self):
        self.git("checkout", "-qb", "feature")
        self.write("README.md", "updated\n")
        self.write("docs/ARCHITECTURE.md", "updated\n")
        self.commit("docs")
        self.git("checkout", "-q", "main")
        self.git("merge", "--no-ff", "-qm", "PR merge", "feature")
        parents = self.git("rev-list", "--parents", "-n", "1", "HEAD").decode().split()
        self.assertEqual(3, len(parents))
        self.assertEqual(self.base, parents[1])
        self.assertTrue(docs_only(self.raw_diff()))

    def test_last_docs_commit_does_not_hide_earlier_code(self):
        self.git("checkout", "-qb", "feature")
        self.write("app/build.gradle.kts", "changed code\n")
        self.commit("code")
        self.write("README.md", "updated\n")
        self.commit("docs")
        self.git("checkout", "-q", "main")
        self.git("merge", "--no-ff", "-qm", "PR merge", "feature")
        self.assertFalse(docs_only(self.raw_diff()))

    def test_real_non_content_changes_require_full_ci(self):
        path = self.root / "README.md"

        def symlink():
            path.unlink()
            path.symlink_to("docs/ARCHITECTURE.md")

        changes = {
            "add": lambda: self.write("docs/MAP.md", "new\n"),
            "delete": path.unlink,
            "rename": lambda: self.git("mv", "README.md", "docs/MAP.md"),
            "executable": lambda: path.chmod(0o755),
            "symlink": symlink,
        }
        for name, change in changes.items():
            with self.subTest(change=name):
                self.git("reset", "--hard", self.base)
                change()
                self.commit(name)
                self.assertFalse(docs_only(self.raw_diff()))

    def test_gitignore_protects_exports_without_hiding_generic_fixtures(self):
        ignore = Path(__file__).resolve().parents[1] / ".gitignore"
        self.write(".gitignore", ignore.read_text(encoding="utf-8"))
        for path in ("tools/__pycache__/ci_scope.cpython-312.pyc",
                     "tools/cache.pyc", "exports/ladies-debug-123.txt",
                     "exports/ladies-http-123.json", "exports/capture.har",
                     "exports/capture.har.json", "tmp/m05-synthetic.zip",
                     "tmp/ladies-export.zip", "tmp/legacy.sqlite3"):
            with self.subTest(ignored=path):
                self.assertEqual(0, subprocess.run(
                    ["git", "check-ignore", "--no-index", "-q", "--", path],
                    cwd=self.root, timeout=30).returncode)
        for path in ("fixtures/sample.txt", "fixtures/sample.json",
                     "fixtures/sample.zip", "fixtures/sample.html",
                     "fixtures/sample.png", "docs/MAP.md", "tools/ci_scope.py"):
            with self.subTest(visible=path):
                self.assertEqual(1, subprocess.run(
                    ["git", "check-ignore", "--no-index", "-q", "--", path],
                    cwd=self.root, timeout=30).returncode)


if __name__ == "__main__":
    unittest.main()
