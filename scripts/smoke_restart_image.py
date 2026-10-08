#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Signal only a sleep child in a disposable image smoke; never start Cardano."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import uuid

SMOKE = r'''set -eu
sleep 30 &
target=$!
# Fixed owned sleep comm has no spaces.
set -- $(cat "/proc/$target/stat")
shift 21
/opt/reference/libexec/restart-pidfd "$target" "$1"
set +e
wait "$target"
result=$?
set -e
test "$result" -eq 143
printf '%s\n' '{"offlineOwnedChildExitedByTerm":true,"cardanoNodeStarted":false}'
'''


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--image", required=True)
    p.add_argument("--helper-sha256", required=True)
    p.add_argument("--output", type=Path, required=True)
    args = p.parse_args()
    if not re.fullmatch("sha256:[0-9a-f]{64}", args.image) or not re.fullmatch("[0-9a-f]{64}", args.helper_sha256):
        raise ValueError("exact image ID and helper digest required")
    if os.environ.get("DOCKER_HOST") or os.environ.get("DOCKER_CONTEXT"):
        raise ValueError("Docker endpoint overrides unsupported")
    args.output.mkdir(parents=True, exist_ok=False)
    name = "cardano-restart-smoke-" + uuid.uuid4().hex[:12]
    def docker(*parts, check=True):
        result = subprocess.run(["docker", "--host", "unix:///var/run/docker.sock", *parts],
                                capture_output=True, text=True, timeout=40)
        with (args.output / "commands.md").open("a") as log:
            log.write(json.dumps({"args": parts, "returncode": result.returncode,
                                  "stdout": result.stdout, "stderr": result.stderr}) + "\n")
        if check and result.returncode:
            raise RuntimeError("owned-child image smoke failed; see private receipt")
        return result.stdout
    try:
        docker("run", "-d", "--pull=never", "--name", name, "--network=none", "--cpus=3",
               "--memory=6g", "--memory-swap=6g", "--pids-limit=384", "--cap-drop=ALL",
               "--security-opt=no-new-privileges", "--user", "1000:1000", "--read-only",
               "--tmpfs", "/work:rw,nosuid,nodev,size=2g,uid=1000,gid=1000,mode=0700",
               "--tmpfs", "/tmp:rw,nosuid,nodev,size=128m,mode=1777",
               "--entrypoint=/bin/sh", args.image, "-c", "sleep 120")
        (args.output / "container.md").write_text(docker("inspect", name))
        mounts = docker("exec", name, "cat", "/proc/self/mountinfo")
        (args.output / "mountinfo.md").write_text(mounts)
        parsed = {row.split()[4]: set(row.split()[5].split(",")) for row in mounts.splitlines()}
        if "ro" not in parsed.get("/", set()) or any("noexec" not in parsed.get(path, set()) for path in ("/work", "/tmp")):
            raise ValueError("read-only root and noexec data required; no automatic mount changes")
        path = "/opt/reference/libexec/restart-pidfd"
        if docker("exec", name, "stat", "-c", "%u:%g:%a:%F", path).strip() != "0:0:555:regular file":
            raise ValueError("immutable helper ownership/mode differs")
        if docker("exec", name, "sha256sum", path).split()[0] != args.helper_sha256:
            raise ValueError("immutable helper digest differs")
        result = docker("exec", name, "/bin/sh", "-c", SMOKE)
        (args.output / "result.md").write_text(result)
        print(result)
    finally:
        docker("rm", "-f", name, check=False)
        remaining = docker("ps", "-aq", "--filter", "name=" + name).strip()
        (args.output / "cleanup.md").write_text(json.dumps({"name": name, "remaining": remaining}))
        if remaining:
            raise RuntimeError("owned smoke cleanup incomplete")


if __name__ == "__main__": main()
