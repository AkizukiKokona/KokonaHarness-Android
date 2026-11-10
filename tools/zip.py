#!/usr/bin/env python
"""Minimal `zip` replacement for the Windows build.

Git Bash ships zipinfo/zipgrep but no `zip`, so build.sh used to die at the
packaging step unless the machine happened to have one on PATH. This shim only
implements the subset the build needs:

    zip -q -X   <archive> <file>...   deflate, appended to an existing archive
    zip -0 -q -X <archive> <file>     store (for the already-compressed .xz)

Arguments are interpreted the way zip does: the first non-option word is the
archive, the rest are members; member names keep the relative path they were
given (build.sh runs from app/ with paths like assets/foo).
"""

import sys
import zipfile

STORED = "-0"


def main(argv):
    method = zipfile.ZIP_DEFLATED
    archive = None
    members = []
    for arg in argv:
        if arg.startswith("-") and arg != "-":
            # Flags are bundled (`-q -X`, `-0qX`); only -0 changes behaviour.
            if "0" in arg[1:]:
                method = zipfile.ZIP_STORED
            continue
        if archive is None:
            archive = arg
        else:
            members.append(arg)

    if archive is None or not members:
        sys.stderr.write("usage: zip.py [-0] [-q] [-X] archive member...\n")
        return 2

    with zipfile.ZipFile(archive, "a", compression=method, allowZip64=True) as zf:
        for name in members:
            # dir_fd-less append semantics: replace a member that already exists
            # so a re-run of the build never leaves duplicate entries behind.
            if name.replace("\\", "/") in zf.namelist():
                zf.writestr(name.replace("\\", "/"), open(name, "rb").read())
            else:
                zf.write(name, name.replace("\\", "/"))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
