"""Execute the real JAR validator against deliberately malformed ZIP/class inputs.

These are bytecode-header fixtures, not proof that synthetic classes load in a JVM.
"""
import hashlib
import io
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest
import warnings
import zipfile

from verify_jar_identity import verify_jar

SCRIPT = Path(__file__).with_name("verify_jar_identity.py")
MAIN = "io/github/wickidcow/sfbetterstructureslink/SFBetterStructuresLink.class"
EXTERNAL = "example/dependency/Dependency.class"
DESCRIPTOR = (
    "name: SF_BetterStructuresLink\n"
    "version: 0.3.1-SNAPSHOT\n"
    "main: io.github.wickidcow.sfbetterstructureslink.SFBetterStructuresLink\n"
)


def header(major=65, minor=0):
    return struct.pack(">IHH", 0xCAFEBABE, minor, major)


class FullJarBytecodeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.jar_path = Path(self.temp.name) / "SF_BetterStructuresLink-0.3.1-SNAPSHOT.jar"
        self.entries = {MAIN: header()}
        self.descriptor = DESCRIPTOR

    def write(self, duplicate=False):
        with zipfile.ZipFile(self.jar_path, "w") as archive:
            archive.writestr("plugin.yml", self.descriptor)
            for path, data in self.entries.items():
                archive.writestr(path, data)
            if duplicate:
                with warnings.catch_warnings():
                    warnings.simplefilter("ignore", UserWarning)
                    archive.writestr(MAIN, header())

    def assert_invalid(self, reason, duplicate=False):
        self.write(duplicate)
        digest = hashlib.sha256(self.jar_path.read_bytes()).hexdigest()
        with self.assertRaisesRegex(ValueError, reason):
            verify_jar(self.jar_path)
        self.assertEqual(digest, hashlib.sha256(self.jar_path.read_bytes()).hexdigest())

    def test_normal_java21_is_accepted_without_rewriting_jar(self):
        self.write()
        before = hashlib.sha256(self.jar_path.read_bytes()).hexdigest()
        verify_jar(self.jar_path)
        self.assertEqual(before, hashlib.sha256(self.jar_path.read_bytes()).hexdigest())

    def test_stable_older_and_shaded_classes_are_accepted(self):
        self.entries[EXTERNAL] = header(52, 0)
        self.write()
        verify_jar(self.jar_path)

    def test_historical_pre_preview_minor_versions_remain_accepted(self):
        for major, minor in ((45, 3), (52, 1), (55, 65535)):
            with self.subTest(major=major, minor=minor):
                self.entries[EXTERNAL] = header(major, minor)
                self.write()
                verify_jar(self.jar_path)

    def test_java21_preview_owned_class_fails(self):
        self.entries[MAIN] = header(65, 65535)
        self.assert_invalid("preview")

    def test_earlier_release_preview_hidden_in_shaded_class_fails(self):
        for major in (56, 61, 64):
            with self.subTest(major=major):
                self.entries[EXTERNAL] = header(major, 65535)
                self.assert_invalid("preview")

    def test_invalid_modern_minor_versions_fail(self):
        for major, minor in ((56, 1), (61, 42), (65, 65534)):
            with self.subTest(major=major, minor=minor):
                self.entries[EXTERNAL] = header(major, minor)
                self.assert_invalid("invalid minor")

    def test_newer_java_hidden_in_shaded_class_fails(self):
        self.entries[EXTERNAL] = header(69)
        self.assert_invalid("Unsupported Java bytecode")

    def test_newer_java_owned_class_fails(self):
        self.entries[MAIN] = header(66)
        self.assert_invalid("Unsupported Java bytecode")

    def test_too_old_classfile_major_fails(self):
        self.entries[EXTERNAL] = header(44)
        self.assert_invalid("Unsupported Java bytecode")

    def test_bad_magic_in_external_class_fails(self):
        self.entries[EXTERNAL] = b"notclass"
        self.assert_invalid("Invalid JVM class")

    def test_truncated_owned_header_fails(self):
        self.entries[MAIN] = header(65)[:7]
        self.assert_invalid("Invalid JVM class")

    def test_canonical_main_remains_required(self):
        self.entries = {EXTERNAL: header()}
        self.assert_invalid("main class missing")

    def test_duplicate_jar_members_fail(self):
        self.assert_invalid("Duplicated JAR entries", duplicate=True)

    def test_server_api_classes_remain_forbidden(self):
        self.entries["org/bukkit/Material.class"] = header()
        self.assert_invalid("Server API bundled")

    def test_wrong_plugin_version_fails(self):
        self.descriptor = DESCRIPTOR.replace("0.3.1-SNAPSHOT", "0.3.2")
        self.assert_invalid("Missing or altered plugin identity")

    def test_cli_checks_actual_file_and_exits_nonzero_on_preview(self):
        self.entries[EXTERNAL] = header(65, 65535)
        self.write()
        before = hashlib.sha256(self.jar_path.read_bytes()).hexdigest()
        result = subprocess.run(
            [sys.executable, str(SCRIPT), str(self.jar_path)],
            text=True, capture_output=True, check=False, timeout=10,
        )
        self.assertNotEqual(0, result.returncode)
        self.assertIn("preview", result.stderr)
        self.assertEqual(before, hashlib.sha256(self.jar_path.read_bytes()).hexdigest())


if __name__ == "__main__":
    unittest.main()
