"""Validate the exact raw Maven plugin JAR before retaining a CI artifact."""

import hashlib
import re
import struct
import sys
import zipfile
from pathlib import Path


def verify_jar(path):
    if not path.is_file() or path.stat().st_size == 0:
        raise ValueError("Missing or empty raw plugin JAR")
    with zipfile.ZipFile(path) as jar:
        names = jar.namelist()
        if len(names) != len(set(names)):
            raise ValueError("Duplicated JAR entries")
        if jar.testzip() is not None:
            raise ValueError("Corrupted JAR entry")
        if "plugin.yml" not in names:
            raise ValueError("Missing Bukkit plugin descriptor")
        descriptor = jar.read("plugin.yml").decode("utf-8")
        for pattern in (
            r"(?m)^name:\s*SF_BetterStructuresLink\s*$",
            r"""(?m)^version:\s*['"]?0\.3\.1-SNAPSHOT['"]?\s*$""",
            r"(?m)^main:\s*io\.github\.wickidcow\.sfbetterstructureslink\.SFBetterStructuresLink\s*$",
        ):
            if not re.search(pattern, descriptor):
                raise ValueError("Missing or altered plugin identity: " + pattern)
        if any(x.startswith(("org/bukkit/", "io/papermc/paper/")) for x in names):
            raise ValueError("Server API bundled in distributable plugin")
        # Do not miss incompatible bytecode hidden outside the plugin namespace.
        # Retain the canonical plugin-main/owned-class identity requirement.
        class_files = [name for name in names if name.endswith(".class")]
        owned_classes = [
            name for name in class_files
            if name.startswith("io/github/wickidcow/sfbetterstructureslink/")
        ]
        if not owned_classes or "io/github/wickidcow/sfbetterstructureslink/SFBetterStructuresLink.class" not in owned_classes:
            raise ValueError("Plugin main class missing from distributable JAR")
        for class_path in class_files:
            header = jar.read(class_path)[:8]
            if len(header) != 8 or header[:4] != b"\xca\xfe\xba\xbe":
                raise ValueError("Invalid JVM class: " + class_path)
            minor, major = struct.unpack(">HH", header[4:8])
            if not 45 <= major <= 65:  # Java 21 classfile ceiling
                raise ValueError("Unsupported Java bytecode: " + class_path + " (" + str(major) + "." + str(minor) + ")")
            # JVMS 4.1: modern classfiles require minor 0 for stable code.
            # Minor 65535 is preview bytecode, which requires --enable-preview
            # for its *specific* release and is not a stable distributable.
            if major >= 56 and minor != 0:
                kind = "preview" if minor == 65535 else "invalid minor"
                raise ValueError("Non-distributable " + kind + " bytecode: "
                                 + class_path + " (" + str(major) + "." + str(minor) + ")")
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    print(f"Verified raw BetterStructuresLink JAR: {path.name}; {len(owned_classes)} owned, "
          f"{len(class_files)} packaged Java 21-or-earlier non-preview classes; sha256:{digest}")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: python3 scripts/verify_jar_identity.py target/plugin.jar")
    try:
        verify_jar(Path(sys.argv[1]))
    except (OSError, ValueError, zipfile.BadZipFile) as e:
        raise SystemExit(str(e)) from e
