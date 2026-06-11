#!/usr/bin/env python3
"""Verify packaged Android native libraries use 16 KB ELF LOAD alignment.

Usage:
  python3 scripts/check_elf_alignment.py app/build/outputs/apk/debug/app-debug.apk
  python3 scripts/check_elf_alignment.py app/libs/some-runtime.aar

The check intentionally runs on the final APK/AAR because transitive native
dependencies are easy to miss in Gradle files. It fails when any LOAD segment
has an alignment smaller than 0x4000, which is what triggers Android 15+/Play
16 KB page-size compatibility failures.
"""

from __future__ import annotations

import argparse
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path


LOAD_RE = re.compile(r"^\s*LOAD\s+.*\s(0x[0-9a-fA-F]+)\s*$")
MIN_ALIGN = 0x4000


def readelf_alignments(path: Path) -> list[int]:
    readelf = shutil.which("readelf")
    if not readelf:
        raise RuntimeError("readelf was not found on PATH")
    output = subprocess.check_output(
        [readelf, "-lW", str(path)],
        text=True,
        stderr=subprocess.STDOUT,
    )
    aligns: list[int] = []
    for line in output.splitlines():
        match = LOAD_RE.match(line)
        if match:
            aligns.append(int(match.group(1), 16))
    if not aligns:
        raise RuntimeError(f"No LOAD segments found in {path.name}")
    return aligns


def native_entries(archive: Path) -> list[str]:
    with zipfile.ZipFile(archive) as zf:
        return [
            name
            for name in zf.namelist()
            if name.endswith(".so")
            and (name.startswith("lib/") or name.startswith("jni/"))
        ]


def check_archive(archive: Path) -> int:
    if not archive.exists():
        print(f"ERROR: file not found: {archive}", file=sys.stderr)
        return 2

    entries = native_entries(archive)
    if not entries:
        print(f"OK: {archive} contains no native libraries")
        return 0

    failures: list[str] = []
    with tempfile.TemporaryDirectory(prefix="dora-elf-align-") as tmp:
        tmpdir = Path(tmp)
        with zipfile.ZipFile(archive) as zf:
            for entry in entries:
                target = tmpdir / entry
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(zf.read(entry))
                aligns = readelf_alignments(target)
                min_align = min(aligns)
                status = "OK" if min_align >= MIN_ALIGN else "FAIL"
                print(
                    f"{status}: {entry} LOAD alignments="
                    f"{', '.join(hex(value) for value in aligns)}"
                )
                if min_align < MIN_ALIGN:
                    failures.append(entry)

    if failures:
        print("\n16 KB ELF alignment check failed for:")
        for entry in failures:
            print(f"  - {entry}")
        return 1

    print(f"\nOK: all native libraries in {archive} are 16 KB aligned")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("archive", type=Path, help="APK/AAB/AAR/ZIP to inspect")
    args = parser.parse_args()
    return check_archive(args.archive)


if __name__ == "__main__":
    raise SystemExit(main())
