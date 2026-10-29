"""Regression gates for an immutable, build-only SF BetterStructures Link CI workflow."""

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WORKFLOW_DIR = ROOT / ".github" / "workflows"

# No release/tag write path belongs in the ordinary push/PR build workflow.
FORBIDDEN = (
    ("release mutation", re.compile(r"\bgh\s+release\s+(?:create|edit|upload|delete)\b", re.I)),
    ("GitHub API mutation", re.compile(r"\bgh\s+api[^\n]*(?:--method[=\s]+|-X\s+)(?:PATCH|POST|PUT|DELETE)\b", re.I)),
    ("forceful tag or ref mutation", re.compile(r"\bforce\s*=\s*true\b|--force(?:-with-lease)?\b", re.I)),
    ("clobbered asset", re.compile(r"--clobber\b", re.I)),
    ("git push from CI", re.compile(r"\bgit\s+push\b", re.I)),
    ("publisher action", re.compile(r"softprops/action-gh-release|actions/create-release|ncipollo/release-action|upload-release-asset", re.I)),
    ("write via curl", re.compile(r"\bcurl\b[^\n]*(?:-X|--request)\s+(?:PATCH|POST|PUT|DELETE)\b", re.I)),
)


def violations(text):
    return [name for name, expression in FORBIDDEN if expression.search(text)]


class ImmutableReleasePolicyTests(unittest.TestCase):
    def test_every_actual_workflow_is_build_only(self):
        paths = sorted(WORKFLOW_DIR.glob("*.yml")) + sorted(WORKFLOW_DIR.glob("*.yaml"))
        self.assertTrue(paths, "At least one build workflow must exist")
        for path in paths:
            text = path.read_text(encoding="utf-8")
            self.assertEqual([], violations(text), str(path))
            self.assertNotIn("GH_TOKEN:", text, str(path))

    def test_known_mutation_forms_are_all_rejected(self):
        examples = (
            "gh release upload dev-build foo.jar --clobber",
            "gh release create dev-build foo.jar",
            "gh release edit dev-build --target deadbeef",
            "gh release delete dev-build",
            "gh api --method PATCH /repos/example/git/refs/tags/dev-build",
            "gh api --method=DELETE /repos/example/releases/123",
            "gh api -X POST /repos/example/releases",
            "gh api --method PUT /repos/example/releases/assets/123",
            "git push origin main --force",
            "curl -X PATCH https://api.github.com/repos/example/releases/123",
            "uses: softprops/action-gh-release@v2",
        )
        for example in examples:
            with self.subTest(example=example):
                self.assertTrue(violations(example), example)

    def test_normal_read_only_and_build_commands_remain_allowed(self):
        examples = (
            "mvn -B -ntp clean verify",
            "gh release view v0.3.1",
            "sha256sum target/SF_BetterStructuresLink.jar",
            "uses: actions/upload-artifact@v7",
            "git rev-parse HEAD",
        )
        for example in examples:
            with self.subTest(example=example):
                self.assertEqual([], violations(example))

    def test_real_api_and_binary_regression_gates_remain(self):
        text = (WORKFLOW_DIR / "build.yml").read_text(encoding="utf-8")
        for required in (
            "pull_request:",
            "branches: [ main ]",
            "mvn -B -ntp clean verify",
            "mvn -B -ntp surefire:test -Dpaper.version=26.2.build.125-stable",
            "jar tf target/SF_BetterStructuresLink-*.jar",
            "python3 scripts/verify_jar_identity.py",
            "actions/upload-artifact@v7",
            "${{ github.sha }}",
            "if-no-files-found: error",
        ):
            self.assertIn(required, text)

    def test_historical_release_is_documented_as_immutable(self):
        text = (ROOT / "README.md").read_text(encoding="utf-8")
        self.assertIn("frozen historical artifacts", text)
        self.assertIn("6f34c294174ac7cf7e425e5881b4fdca5302ec5db92497e064d06b6d147e0815", text)
        self.assertIn("releases/download/dev-build/SF_BetterStructuresLink-0.3.1-SNAPSHOT.jar", text)

    def test_java21_and_12111_source_floor_are_not_downgraded(self):
        pom = (ROOT / "pom.xml").read_text(encoding="utf-8")
        self.assertIn("<maven.compiler.release>21</maven.compiler.release>", pom)
        self.assertIn("<paper.version>1.21.11-R0.1-SNAPSHOT</paper.version>", pom)


if __name__ == "__main__":
    unittest.main()
