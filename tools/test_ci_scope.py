import itertools
import os
import textwrap
from ci_reuse import verify_proof, merged_pr, verify_jobs, latest_run
import subprocess
import tempfile
from pathlib import Path
import unittest
from unittest.mock import patch

from ci_scope import classify, docs_only, parse_diff, main

BASE = "a" * 40
HEAD = "b" * 40
MERGE = "c" * 40
ENV = {
    "GITHUB_EVENT_NAME": "pull_request", "BASE_REF": "main",
    "BASE_SHA": BASE, "GITHUB_SHA": MERGE, "GITHUB_REPOSITORY": "owner/repo",
}


def diff(path, status=b"M", old=b"100644", new=b"100644"):
    return b":" + old + b" " + new + b" abcdef0 1234567 " + status + b"\0" + path + b"\0"


class CiScopeTest(unittest.TestCase):
    def test_documentation_paths_and_regular_modes(self):
        paths = (b"README.md", b"AGENTS.md", b"docs/PROVENANCE.md",
                 b"docs/new.md", b"docs/nested/guide.md", b"tools/M05-REFERENCE.md",
                 b"tools/other.md", b"CHANGELOG.md")
        for path in paths:
            for status, old, new in ((b"M", b"100644", b"100644"),
                                     (b"A", b"000000", b"100644"),
                                     (b"D", b"100644", b"000000")):
                with self.subTest(path=path, status=status):
                    self.assertTrue(docs_only(diff(path, status, old, new)))
        for path in (b"app/src/main/assets/help.md", b"rust/README.md",
                     b"gradle/README.md", b"tools/m05-reference.py",
                     b"docs/input.json", b"LICENSE", b"NOTICE", b".gitignore",
                     b".github/workflows/android.yml"):
            with self.subTest(path=path):
                self.assertFalse(docs_only(diff(path)))
                self.assertFalse(docs_only(diff(b"README.md") + diff(path)))
        for raw in (b"", diff(b"README.md")[:-1],
                    diff(b"README.md", new=b"100755"),
                    diff(b"README.md", b"T", new=b"120000"),
                    diff(b"docs/symlink.md", b"A", b"000000", b"120000"),
                    diff(b"docs/submodule.md", b"A", b"000000", b"160000")):
            self.assertFalse(docs_only(raw))

    @patch("ci_scope.subprocess.check_output")
    def test_full_merge_diff_docs_needs_no_remote_evidence(self, command):
        command.side_effect = [
            f"{MERGE} {BASE} {HEAD}\n".encode(),
            diff(b"README.md") + diff(b"AGENTS.md") + diff(b"docs/PROVENANCE.md"),
        ]
        self.assertEqual("docs", classify(ENV).mode)
        self.assertEqual(2, command.call_count)
        self.assertEqual(("git", "diff", "--raw", "-z", "--no-renames", BASE, "HEAD"),
                         tuple(command.call_args_list[1].args[0]))

    @patch("ci_scope.subprocess.check_output")
    def test_mixed_history_never_skips(self, command):
        command.side_effect = [f"{MERGE} {BASE} {HEAD}\n".encode(),
                               diff(b"README.md") + diff(b"app/build.gradle.kts")]
        self.assertEqual("full", classify(ENV)[0])
        self.assertEqual(2, command.call_count)

    @patch("ci_scope.subprocess.check_output")
    def test_unverified_diff_defaults_full(self, command):
        for outputs in ([f"{MERGE} {HEAD}\n".encode()],
                        [f"{MERGE} {HEAD} {BASE}\n".encode()],
                        [f"{MERGE} {BASE} {HEAD}\n".encode(), b"incomplete"],
                        [f"{MERGE} {BASE} {HEAD}\n".encode(),
                         subprocess.CalledProcessError(1, "git")]):
            command.side_effect = outputs
            self.assertEqual("full", classify(ENV).mode)

    @patch("ci_scope.subprocess.check_output")
    def test_main_manual_and_other_base_are_full(self, command):
        for env in (dict(ENV, GITHUB_EVENT_NAME="push"),
                    dict(ENV, GITHUB_EVENT_NAME="workflow_dispatch")):
            self.assertEqual("full", classify(env)[0])
        command.assert_not_called()


    def assert_policy(self, decision, mode, native, measure):
        self.assertEqual((mode, native, measure),
                         (decision.mode, decision.native_qualification, decision.performance_measurement))

    @patch("ci_scope.subprocess.check_output")
    def test_non_main_base_full_with_verified_paths(self, command):
        command.side_effect = [f"{MERGE} {BASE} {HEAD}\n".encode(), diff(b"README.md")]
        self.assert_policy(classify(dict(ENV, BASE_REF="dev")), "full", False, False)
        self.assertEqual(2, command.call_count)

    @patch("ci_scope.subprocess.check_output")
    def test_pr_never_automatically_selects_diagnostics(self, command):
        regular = (b"app/src/main/java/new.kt", b"app/src/main/res/layout/main.xml",
                   b"app/src/main/AndroidManifest.xml", b"app/src/test/java/X.java",
                   b"app/src/androidTest/java/X.kt", b"app/proguard-rules.pro",
                   b"rust/ladies-core/src/lib.rs", b"rust/ladies-core/src/source/filter_catalog.json",
                   b"tools/check_architecture.py", b"tools/test_check_architecture.py",
                   b"tools/export-legacy.sh", b"tools/m05-reference.py",
                   b"LICENSE", b"NOTICE", b".gitignore")
        native = (b"build.gradle.kts", b"app/build.gradle.kts", b"settings.gradle.kts",
                  b"gradle.properties", b"gradle/libs.versions.toml", b"gradlew", b"gradlew.bat",
                  b"rust/Cargo.lock", b"rust/ladies-core/Cargo.toml", b"rust/ladies-core/build.rs",
                  b"rust/ladies-core/uniffi.toml", b"rust/bindgen/src/main.rs",
                  b"rust/rust-toolchain.toml", b".cargo/config.toml", b"rust/.cargo/config",
                  b"app/src/main/jniLibs/arm64-v8a/lib.so", b".github/workflows/android.yml",
                  b"tools/ci_scope.py", b"tools/test_ci_scope.py", b"tools/verify-native-cache.sh",
                  b"unknown.bin", b"rust/ladies-core/src/unknown.sh")
        cases = [(p, False, False) for p in regular + native]
        cases += [(b"tools/measure-android-build.sh", False, False),
                  (b"tools/install-cargo-ndk.sh", False, False)]
        for path, n, m in cases:
            with self.subTest(path=path):
                command.side_effect = [f"{MERGE} {BASE} {HEAD}\n".encode(), diff(path)]
                self.assert_policy(classify(ENV), "full", n, m)
        command.side_effect = [f"{MERGE} {BASE} {HEAD}\n".encode(),
                               diff(b"README.md") + diff(b"rust/Cargo.lock") +
                               diff(b"tools/measure-android-build.sh")]
        self.assert_policy(classify(ENV), "full", False, False)

    @patch("ci_scope.subprocess.check_output")
    def test_automatic_events_ignore_manual_diagnostic_inputs(self, command):
        for path in (b".github/workflows/android.yml", b"gradle.properties",
                     b"rust/Cargo.lock", b"tools/verify-native-cache.sh",
                     b"tools/ci_reuse.py", b"unknown.bin", b"tools/measure-android-build.sh",
                     b"tools/install-cargo-ndk.sh"):
            with self.subTest(path=path):
                command.side_effect = [f"{MERGE} {BASE} {HEAD}\n".encode(), diff(path)]
                self.assert_policy(classify(dict(ENV, VERIFY_NATIVE_CACHE="true", MEASURE_ANDROID_BUILD="true")),
                                   "full", False, False)
                command.side_effect = [MERGE.encode(), b"", diff(path)]
                env = dict(ENV, GITHUB_EVENT_NAME="push", GITHUB_REF="refs/heads/main",
                           BEFORE_SHA=BASE, VERIFY_NATIVE_CACHE="true", MEASURE_ANDROID_BUILD="true")
                self.assert_policy(classify(env), "full", False, False)

    def test_manual_inputs_independent(self):
        for n in (True, False):
            for m in (True, False):
                env = dict(ENV, GITHUB_EVENT_NAME="workflow_dispatch",
                           VERIFY_NATIVE_CACHE=str(n).lower(), MEASURE_ANDROID_BUILD=str(m).lower())
                self.assert_policy(classify(env), "full", n, m)
        with self.assertRaises(ValueError):
            classify(dict(ENV, GITHUB_EVENT_NAME="workflow_dispatch", VERIFY_NATIVE_CACHE="yes"))

    def test_raw_diff_malformed_and_special_modes(self):
        for raw in (b"", diff(b"README.md")[:-1], diff(b"README.md") * 2,
                    diff(b"../README.md"), diff(b"README.md", b"R"),
                    diff(b"README.md", old=b"000000")):
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                parse_diff(raw)
        for path in (b"app/src/main/java/X.kt", b"README.md"):
            changes = parse_diff(diff(path, new=b"100755"))
            self.assertEqual(b"100755", changes[0].new_mode)
            self.assertFalse(docs_only(diff(path, new=b"100755")))

    @patch("ci_scope.subprocess.check_output")
    def test_push_head_and_ancestry_required(self, command):
        env = dict(ENV, GITHUB_EVENT_NAME="push", GITHUB_REF="refs/heads/main",
                   BEFORE_SHA=BASE)
        command.side_effect = [MERGE.encode(), subprocess.CalledProcessError(1, "git")]
        self.assert_policy(classify(env), "full", False, False)
        command.side_effect = [HEAD.encode()]
        self.assert_policy(classify(env), "full", False, False)
        command.side_effect = [MERGE.encode(), b"", diff(b"tools/measure-android-build.sh")]
        self.assert_policy(classify(env), "full", False, False)


    @patch("ci_scope.subprocess.check_output")
    def test_missing_invalid_event_evidence(self, command):
        for env in (dict(ENV, BASE_SHA=""), dict(ENV, BASE_SHA="0" * 40),
                    dict(ENV, GITHUB_SHA="not a commit"),
                    dict(ENV, GITHUB_EVENT_NAME="push", BEFORE_SHA=""),
                    dict(ENV, GITHUB_EVENT_NAME="push", BEFORE_SHA="0" * 40),
                    dict(ENV, GITHUB_EVENT_NAME="unrecognized")):
            with self.subTest(env=env):
                self.assert_policy(classify(env), "full", False, False)
        command.assert_not_called()

    def test_actual_manual_output_files(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "output"
            summary = Path(directory) / "summary"
            for native, measure in itertools.product(("true", "false"), repeat=2):
                output.write_text("")
                summary.write_text("")
                env = dict(ENV, GITHUB_EVENT_NAME="workflow_dispatch",
                           VERIFY_NATIVE_CACHE=native, MEASURE_ANDROID_BUILD=measure,
                           GITHUB_OUTPUT=str(output), GITHUB_STEP_SUMMARY=str(summary))
                with patch.dict(os.environ, env, clear=True):
                    main()
                self.assertEqual(["mode=full", f"native_qualification={native}",
                                  f"performance_measurement={measure}", "reuse_candidate=none"],
                                 output.read_text().splitlines())
                self.assertIn(f"native_qualification: {native}", summary.read_text())
                self.assertIn(f"performance_measurement: {measure}", summary.read_text())


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

    def test_real_documentation_add_delete_rename_and_special_modes(self):
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
                self.assertEqual(name in {"add", "delete", "rename"},
                                 docs_only(self.raw_diff()))

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


    def classify_push(self):
        env = dict(ENV, GITHUB_EVENT_NAME="push", GITHUB_REF="refs/heads/main",
                   BEFORE_SHA=self.base, GITHUB_SHA=self.git("rev-parse", "HEAD").decode().strip())
        with patch("ci_scope.git", side_effect=self.git):
            return classify(env)

    def test_real_multicommit_push_and_rename(self):
        self.write("tools/install-cargo-ndk.sh", "installer")
        self.commit("native and measurement")
        self.write("README.md", "updated")
        self.commit("docs")
        result = self.classify_push()
        self.assertEqual(("full", False, False),
                         (result.mode, result.native_qualification, result.performance_measurement))
        self.git("reset", "--hard", self.base)
        self.git("mv", "app/build.gradle.kts", "README2.md")
        self.commit("rename build contract")
        result = self.classify_push()
        self.assertEqual(("full", False, False),
                         (result.mode, result.native_qualification, result.performance_measurement))

    def test_real_push_docs_and_new_code(self):
        self.write("README.md", "updated")
        self.commit("docs")
        result = self.classify_push()
        self.assertEqual(("docs", False, False),
                         (result.mode, result.native_qualification, result.performance_measurement))
        self.assertEqual("docs", result.reuse_candidate)
        self.write("app/src/main/java/New.kt", "new source")
        self.commit("code")
        result = self.classify_push()
        self.assertEqual(("full", False, False),
                         (result.mode, result.native_qualification, result.performance_measurement))


    def classify_pr(self):
        env = dict(ENV, BASE_SHA=self.base, GITHUB_SHA=self.git("rev-parse", "HEAD").decode().strip())
        with patch("ci_scope.git", side_effect=self.git):
            return classify(env)

    def assert_decision(self, decision, mode, native, performance):
        self.assertEqual((mode, native, performance),
                         (decision.mode, decision.native_qualification, decision.performance_measurement))

    def test_real_large_pr_keeps_native_path(self):
        self.git("checkout", "-qb", "feature")
        for number in range(301):
            self.write(f"app/src/main/java/test/File{number:03}.kt", "// synthetic\n")
        self.write("rust/Cargo.lock", "# synthetic lock\n")
        self.commit("large change")
        self.write("README.md", "updated")
        self.commit("last commit docs")
        self.git("checkout", "-q", "main")
        self.git("merge", "--no-ff", "-qm", "PR merge", "feature")
        self.assertEqual(303, len(parse_diff(self.raw_diff())))
        self.assert_decision(self.classify_pr(),
                             "full", False, False)

    def test_real_pr_docs_and_special_mode(self):
        self.git("checkout", "-qb", "feature")
        self.write("README.md", "updated")
        self.commit("docs")
        self.git("checkout", "-q", "main")
        self.git("merge", "--no-ff", "-qm", "PR merge", "feature")
        self.assert_decision(self.classify_pr(),
                             "docs", False, False)
        self.git("reset", "--hard", self.base)
        self.git("checkout", "-qb", "structural")
        (self.root / "README.md").unlink()
        (self.root / "README.md").symlink_to("docs/ARCHITECTURE.md")
        self.commit("doc symlink")
        self.git("checkout", "-q", "main")
        self.git("merge", "--no-ff", "-qm", "PR merge", "structural")
        self.assert_decision(self.classify_pr(),
                             "full", False, False)

    def test_real_push_missing_or_nonancestor_base(self):
        self.git("checkout", "-qb", "other")
        self.write("README.md", "different ancestor")
        self.commit("other history")
        other = self.git("rev-parse", "HEAD").decode().strip()
        self.git("checkout", "-q", "main")
        self.write("README.md", "main history")
        self.commit("main change")
        head = self.git("rev-parse", "HEAD").decode().strip()
        for before in (other, "f" * 40, head):
            with self.subTest(before=before):
                env = dict(ENV, GITHUB_EVENT_NAME="push", GITHUB_REF="refs/heads/main",
                           BEFORE_SHA=before, GITHUB_SHA=head)
                with patch("ci_scope.git", side_effect=self.git):
                    self.assert_decision(classify(env), "full", False, False)

    def test_real_nul_paths_unknown_and_measurement_union(self):
        self.write("app/src/main/java/space tab\tnewline\n.kt", "// synthetic\n")
        self.commit("NUL paths")
        self.assert_decision(self.classify_push(), "full", False, False)
        self.write("unknown\nfile", "synthetic")
        self.write("tools/measure-android-build.sh", "# synthetic\n")
        self.commit("unknown and measurement")
        self.assert_decision(self.classify_push(), "full", False, False)



class WorkflowGateTest(unittest.TestCase):
    """Exercise actual inline shell bodies, without a duplicate gate implementation."""

    @classmethod
    def setUpClass(cls):
        cls.workflow = (Path(__file__).resolve().parents[1] /
                        ".github/workflows/android.yml").read_text()

    def step(self, name):
        # Narrow extraction of uniquely named steps; fail if this format changes.
        marker = f"      - name: {name}\n"
        self.assertEqual(1, self.workflow.count(marker))
        lines = self.workflow.split(marker, 1)[1].splitlines()
        end = next((i for i, line in enumerate(lines)
                    if line.strip() and len(line) - len(line.lstrip(" ")) <= 6), len(lines))
        return "\n".join(lines[:end])

    def shell_body(self, name):
        block = self.step(name)
        self.assertEqual(1, block.count("        run: |\n"))
        return textwrap.dedent(block.split("        run: |\n", 1)[1]).rstrip() + "\n"

    def run_shell(self, body, env):
        with tempfile.TemporaryDirectory() as directory:
            summary = Path(directory) / "summary"
            result = subprocess.run(
                ["bash", "--noprofile", "--norc", "-e", "-o", "pipefail", "-c", body],
                env=dict(os.environ, **env, GITHUB_STEP_SUMMARY=str(summary)),
                capture_output=True, text=True, timeout=10)
            return result, summary.read_text() if summary.exists() else ""

    def test_actual_scope_validation_shell(self):
        body = self.shell_body("Finalize execution policy")
        valid = {("docs", "false", "false"), ("full", "false", "false"),
                 ("full", "true", "false"), ("full", "false", "true"),
                 ("full", "true", "true")}
        for values in itertools.product(("docs", "reuse", "full", "", "unknown"),
                                        ("true", "false", "", "unknown"),
                                        ("true", "false", "", "unknown")):
            with tempfile.TemporaryDirectory() as tmp, self.subTest(values=values):
                env = dict(zip(("MODE", "NATIVE", "PERFORMANCE"), values),
                           SOURCE="", RESTORE="skipped", UPLOAD="skipped",
                           GITHUB_OUTPUT=str(Path(tmp) / "out"))
                result, _ = self.run_shell(body, env)
                self.assertEqual(values in valid, result.returncode == 0, result.stderr)

    def test_actual_gate_valid_profiles_and_negative_mutations(self):
        body = self.shell_body("Evaluate required CI gate")
        valid = [dict(SCOPE_RESULT="success", MODE="docs", CORE_RESULT="skipped",
                      ANDROID_RESULT="skipped", NATIVE_REQUIRED="false", PERFORMANCE_REQUIRED="false",
                      NATIVE_OUTCOME="", PERFORMANCE_OUTCOME="")]
        for native, measure in itertools.product(("true", "false"), repeat=2):
            valid.append(dict(SCOPE_RESULT="success", MODE="full", CORE_RESULT="success",
                              ANDROID_RESULT="success", NATIVE_REQUIRED=native,
                              PERFORMANCE_REQUIRED=measure,
                              NATIVE_OUTCOME="success" if native == "true" else "skipped",
                              PERFORMANCE_OUTCOME="success" if measure == "true" else "skipped"))
        replacements = {
            "MODE": ("docs", "full", "", "unknown"),
            "NATIVE_REQUIRED": ("true", "false", "", "unknown"),
            "PERFORMANCE_REQUIRED": ("true", "false", "", "unknown"),
        }
        statuses = ("success", "skipped", "failure", "cancelled", "", "unknown")
        for env in valid:
            with self.subTest(valid=env):
                result, summary = self.run_shell(body, env)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertIn("| Check | Expected | Actual |", summary)
                self.assertIn("| Native cache qualification", summary)
            for field in env:
                for value in replacements.get(field, statuses):
                    if value == env[field]:
                        continue
                    changed = dict(env, **{field: value})
                    with self.subTest(field=field, value=value, original=env):
                        result, _ = self.run_shell(body, changed)
                        self.assertNotEqual(0, result.returncode, f"Accepted {changed}")

    def test_actual_gate_rejects_joint_outcome_mismatches(self):
        body = self.shell_body("Evaluate required CI gate")
        statuses = ("success", "skipped", "failure", "cancelled", "", "unknown")
        for native, measure in itertools.product(("true", "false"), repeat=2):
            expected = tuple("success" if flag == "true" else "skipped"
                             for flag in (native, measure))
            for outcomes in itertools.product(statuses, repeat=2):
                with self.subTest(native=native, measure=measure, outcomes=outcomes):
                    result, _ = self.run_shell(body, dict(
                        SCOPE_RESULT="success", MODE="full", CORE_RESULT="success",
                        ANDROID_RESULT="success", NATIVE_REQUIRED=native, PERFORMANCE_REQUIRED=measure,
                        NATIVE_OUTCOME=outcomes[0], PERFORMANCE_OUTCOME=outcomes[1]))
                    self.assertEqual(outcomes == expected, result.returncode == 0, result.stderr)


    def test_reuse_gate_requires_successful_upload_and_source(self):
        body = self.shell_body("Evaluate required CI gate")
        env = dict(SCOPE_RESULT="success", MODE="reuse", CORE_RESULT="skipped",
                   ANDROID_RESULT="skipped", NATIVE_REQUIRED="false", PERFORMANCE_REQUIRED="false",
                   NATIVE_OUTCOME="", PERFORMANCE_OUTCOME="", REUSE_SOURCE="42", REUSE_UPLOAD="success")
        result, _ = self.run_shell(body, env)
        self.assertEqual(0, result.returncode, result.stderr)
        for field, value in (("REUSE_SOURCE", ""), ("REUSE_SOURCE", "0"),
                             ("REUSE_SOURCE", "unknown"), ("REUSE_UPLOAD", "failure"),
                             ("REUSE_UPLOAD", "skipped"), ("REUSE_UPLOAD", ""),
                             ("CORE_RESULT", "success"), ("ANDROID_RESULT", "failure"),
                             ("NATIVE_REQUIRED", "true"), ("PERFORMANCE_OUTCOME", "success")):
            with self.subTest(field=field, value=value):
                result, _ = self.run_shell(body, dict(env, **{field: value}))
                self.assertNotEqual(0, result.returncode)

    def test_reuse_proof_rejects_wrong_tree_attempt_and_qualification(self):
        source = {"id": 42, "run_attempt": 2, "event": "pull_request",
                  "repository": {"full_name": "owner/repo"}}
        apk = {"name": "app-debug.apk", "sha256": "a" * 64}
        proof = {"version": 1, "tree": HEAD, "parents": [BASE, MERGE], "commit": MERGE,
                 "event": "pull_request", "repository": "owner/repo", "run": 42, "attempt": 2,
                 "apk": apk, "native": "true", "measurement": "false"}
        verify_proof(proof, source, HEAD, [BASE, MERGE], None, apk, ("true", "false"))
        for field, value in (("tree", BASE), ("parents", [BASE]), ("attempt", 1),
                             ("repository", "fork/repo"), ("event", "push"), ("run", 41),
                             ("native", "false"), ("measurement", "true"), ("apk", {})):
            with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                verify_proof(dict(proof, **{field: value}), source, HEAD, [BASE, MERGE],
                             None, apk, ("true", "false"))

    @patch("ci_reuse.api")
    def test_reuse_only_real_same_repository_merge(self, api):
        current = {"commit": MERGE, "tree": HEAD, "parents": [BASE, HEAD]}
        pr = {"merge_commit_sha": MERGE, "merged_at": "2026-11-20T12:00:00Z",
              "base": {"ref": "main", "repo": {"full_name": "owner/repo"}},
              "head": {"sha": HEAD, "repo": {"full_name": "owner/repo"}}}
        api.return_value = [pr]
        self.assertEqual(pr, merged_pr("owner/repo", current, BASE))
        self.assertIsNone(merged_pr("owner/repo", current, HEAD))
        self.assertIsNone(merged_pr("owner/repo", dict(current, parents=[BASE]), BASE))
        api.return_value = [dict(pr, head={"sha": HEAD, "repo": {"full_name": "fork/repo"}})]
        self.assertIsNone(merged_pr("owner/repo", current, BASE))
        api.return_value = [dict(pr, merge_commit_sha=BASE)]
        self.assertIsNone(merged_pr("owner/repo", current, BASE))


    def test_reuse_finalizer_falls_back_without_both_successful_steps(self):
        body = self.shell_body("Finalize execution policy")
        for restore, upload, source in itertools.product(("success", "failure", "skipped"),
                                                         ("success", "failure", "skipped"),
                                                         ("42", "", "unknown")):
            with tempfile.TemporaryDirectory() as tmp, self.subTest(
                    restore=restore, upload=upload, source=source):
                output = Path(tmp) / "output"
                result, _ = self.run_shell(body, dict(MODE="full", NATIVE="true",
                    PERFORMANCE="false", SOURCE=source, RESTORE=restore, UPLOAD=upload,
                    GITHUB_OUTPUT=str(output)))
                self.assertEqual(0, result.returncode, result.stderr)
                fields = dict(line.split("=", 1) for line in output.read_text().splitlines())
                reused = restore == upload == "success" and source == "42"
                self.assertEqual("reuse" if reused else "full", fields["mode"])
                self.assertEqual("false" if reused else "true", fields["native_qualification"])
                self.assertEqual("42" if reused else "", fields["reuse_source"])

    def test_docs_finalizer_never_builds_on_missing_reuse(self):
        body = self.shell_body("Finalize execution policy")
        for restore, upload, source in itertools.product(("success", "failure", "skipped"),
                                                         ("success", "failure", "skipped"),
                                                         ("42", "", "unknown")):
            with tempfile.TemporaryDirectory() as tmp, self.subTest(
                    restore=restore, upload=upload, source=source):
                output = Path(tmp) / "output"
                result, _ = self.run_shell(body, dict(MODE="docs", NATIVE="false",
                    PERFORMANCE="false", SOURCE=source, RESTORE=restore, UPLOAD=upload,
                    GITHUB_OUTPUT=str(output)))
                self.assertEqual(0, result.returncode, result.stderr)
                fields = dict(line.split("=", 1) for line in output.read_text().splitlines())
                reused = restore == upload == "success" and source == "42"
                self.assertEqual("reuse" if reused else "docs", fields["mode"])
                self.assertEqual("42" if reused else "", fields["reuse_source"])

    @patch("ci_reuse.api")
    def test_source_latest_run_and_actual_qualification(self, api):
        repo = {"full_name": "owner/repo"}
        run_info = {"id": 42, "run_attempt": 2, "head_sha": HEAD, "event": "pull_request",
                    "status": "completed", "conclusion": "success",
                    "path": ".github/workflows/android.yml", "repository": repo,
                    "head_repository": repo}
        api.return_value = {"workflow_runs": [run_info]}
        self.assertEqual(run_info, latest_run("owner/repo", HEAD, "pull_request"))
        for changed in (dict(status="in_progress"), dict(conclusion="failure"),
                        dict(head_repository={"full_name": "fork/repo"}), dict(head_sha=BASE)):
            api.return_value = {"workflow_runs": [dict(run_info, **changed), run_info]}
            with self.subTest(changed=changed), self.assertRaises(ValueError):
                latest_run("owner/repo", HEAD, "pull_request")
        jobs = [{"name": name, "conclusion": "success"} for name in
                ("CI scope", "Core and contracts", "Android integration", "build")]
        jobs[2]["steps"] = [{"name": "Verify native task cache contract", "conclusion": "success"},
                            {"name": "Measure Android build performance (CI05)", "conclusion": "skipped"}]
        api.return_value = {"jobs": jobs}
        verify_jobs("owner/repo", run_info, ("true", "false"))
        for flags in (("false", "false"), ("true", "true")):
            with self.subTest(flags=flags), self.assertRaises(ValueError):
                verify_jobs("owner/repo", run_info, flags)
        jobs[3]["conclusion"] = "failure"
        with self.assertRaises(ValueError):
            verify_jobs("owner/repo", run_info, ("true", "false"))

    def test_sdk_install_only_missing_packages_and_verify_afterwards(self):
        body = self.shell_body("Install required Android SDK and NDK")
        for platform, ndk, broken_install in itertools.product((False, True), repeat=3):
            with tempfile.TemporaryDirectory() as tmp, self.subTest(
                    platform=platform, ndk=ndk, broken_install=broken_install):
                root = Path(tmp)
                # Fake only the SDK installer, execute the actual production shell.
                sdkmanager = root / "cmdline-tools/latest/bin/sdkmanager"
                sdkmanager.parent.mkdir(parents=True)
                log = root / "requests"
                ndk_dir = root / "ndk/29.0.14206865"
                clang = ndk_dir / "toolchains/llvm/prebuilt/linux-x86_64/bin/clang"
                sysroot = ndk_dir / "toolchains/llvm/prebuilt/linux-x86_64/sysroot"
                def install_platform():
                    jar = root / "platforms/android-37.0/android.jar"
                    jar.parent.mkdir(parents=True, exist_ok=True)
                    jar.write_text("fixture")
                def install_ndk():
                    clang.parent.mkdir(parents=True, exist_ok=True)
                    clang.write_text("#!/bin/bash\n")
                    clang.chmod(0o755)
                    sysroot.mkdir(parents=True, exist_ok=True)
                    (ndk_dir / "source.properties").write_text("Pkg.Revision = 29.0.14206865\n")
                if platform:
                    install_platform()
                if ndk:
                    install_ndk()
                sdkmanager.write_text("""#!/bin/bash
set -e
printf '%s\\n' "$@" > "$REQUEST_LOG"
if [ "$BROKEN_INSTALL" = true ]; then exit 0; fi
for package in "$@"; do
  case "$package" in
    'platforms;android-37.0')
      mkdir -p "$ANDROID_HOME/platforms/android-37.0"
      printf fixture > "$ANDROID_HOME/platforms/android-37.0/android.jar" ;;
    'ndk;29.0.14206865')
      dir="$ANDROID_HOME/ndk/29.0.14206865"
      mkdir -p "$dir/toolchains/llvm/prebuilt/linux-x86_64/bin" "$dir/toolchains/llvm/prebuilt/linux-x86_64/sysroot"
      printf '#!/bin/bash\\n' > "$dir/toolchains/llvm/prebuilt/linux-x86_64/bin/clang"
      chmod +x "$dir/toolchains/llvm/prebuilt/linux-x86_64/bin/clang"
      printf 'Pkg.Revision = 29.0.14206865\\n' > "$dir/source.properties" ;;
    *) exit 2 ;;
  esac
done
""")
                sdkmanager.chmod(0o755)
                result, _ = self.run_shell(body, dict(ANDROID_HOME=str(root),
                    REQUEST_LOG=str(log), BROKEN_INSTALL=str(broken_install).lower()))
                expected = ([] if platform else ["platforms;android-37.0"]) + (
                            [] if ndk else ["ndk;29.0.14206865"])
                self.assertEqual(expected, log.read_text().splitlines() if log.exists() else [])
                self.assertEqual(not (broken_install and expected), result.returncode == 0,
                                 result.stderr)

    def test_workflow_bindings_and_upload_order(self):
        names = ("Build arm64 APK and device test APK, run JVM tests and Android Lint",
                 "Verify packaged architecture contracts", "Upload debug APK",
                 "Verify native task cache contract", "Measure Android build performance (CI05)",
                 "Upload Gradle timing and test reports")
        positions = [self.workflow.index(f"      - name: {name}\n") for name in names]
        self.assertEqual(sorted(positions), positions)
        for name in names[:3]:
            self.assertNotIn("\n        if:", "\n" + self.step(name))
            self.assertNotIn("continue-on-error:", self.step(name))
        for step_name, flag in zip(names[3:5], ("native_qualification", "performance_measurement")):
            step = self.step(step_name)
            self.assertIn(f"id: {flag}", step)
            condition = "github.event_name == 'workflow_dispatch' && "
            self.assertIn("if: ${{ " + condition + "needs.scope.outputs." + flag + " == 'true' }}", step)
            self.assertNotIn("continue-on-error:", step)
            self.assertIn(flag + ": ${{ steps." + flag + ".outcome }}", self.workflow)
            self.assertIn(flag + ": ${{ steps.classify.outputs." + flag + " }}", self.workflow)
        self.assertIn("CI05_DIAGNOSTICS: ${{ needs.scope.outputs.performance_measurement }}",
                      self.step(names[0]))
        gate = self.step("Evaluate required CI gate")
        self.assertIn("shell: bash", gate)
        for variable, job, field in (
                ("NATIVE_REQUIRED", "scope", "native_qualification"),
                ("PERFORMANCE_REQUIRED", "scope", "performance_measurement"),
                ("NATIVE_OUTCOME", "android", "native_qualification"),
                ("PERFORMANCE_OUTCOME", "android", "performance_measurement")):
            self.assertIn(variable + ": ${{ needs." + job + ".outputs." + field + " }}", gate)
        self.assertIn("BEFORE_SHA: ${{ github.event.before }}", self.step("Classify complete event diff"))
        self.assertIn("VERIFY_NATIVE_CACHE: ${{ inputs.verify_native_cache }}",
                      self.step("Classify complete event diff"))
        self.assertIn("MEASURE_ANDROID_BUILD: ${{ inputs.measure_android_build }}",
                      self.step("Classify complete event diff"))
        self.assertNotIn("git diff --quiet", self.workflow)


if __name__ == "__main__":
    unittest.main()
