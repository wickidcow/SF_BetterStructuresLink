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
            r"(?m)^name:\\s*SF_BetterStructuresLink\\s*$",
            r"(?m)^version:\\s*['\\\"]?0\\.3\\.1-SNAPSHOT['\\\"]?\\s*$",
            r"(?m)^main:\\s*io\\.github\\.wickidcow\\.sfbetterstructureslink\\.SFBetterStructuresLink\\s*$",
        ):
            if not re.search(pattern, descriptor):
                raise ValueError("Missing or altered plugin identity: " + pattern)
        if any(x.startswith(("org/bukkit/", "io/papermc/paper/")) for x in names):
            raise ValueError("Server API bundled in distributable plugin")
        classes = [
            x for x in names
            if x.startswith("io/github/wickidcow/sfbetterstructureslink/") and x.endswith(".class")
        ]
        if not classes or "io/github/wickidcow/sfbetterstructureslink/SFBetterStructuresLink.class" not in classes:
            raise ValueError("Plugin main class missing from distributable JAR")
        for class_path in classes:
            data = jar.read(class_path)
            if len(data) < 8 or data[:4] != b"\\xca\\xfe\\xba\\xbe":
                raise ValueError("Invalid JVM class: " + class_path)
            major = struct.unpack(">H", data[6:8])[0]
            if major > 65:  # Java 21 classfile version
                raise ValueError("Java >21 bytecode: " + class_path + " (" + str(major) + ")")
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    print(f"Verified raw BetterStructuresLink JAR: {path.name}; {len(classes)} Java 21-or-earlier classes; sha256:{digest}")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: python3 scripts/verify_jar_identity.py target/plugin.jar")
    try:
        verify_jar(Path(sys.argv[1]))
    except (OSError, ValueError, zipfile.BadZipFile) as e:
        raise SystemExit(str(e)) from e
