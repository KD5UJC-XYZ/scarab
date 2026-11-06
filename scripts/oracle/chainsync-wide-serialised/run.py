# SPDX-License-Identifier: Apache-2.0
"""Run the wide-byte witness in its pinned image and bind output to executed inputs."""
import argparse
import datetime
import hashlib
import json
import subprocess
import sys
from pathlib import Path

IMAGE = "sha256:10d59164dce6b49fbeafdc520c1a29d3e4024a5859a3c41bb6181b2e627bebc7"
SOURCE = Path(__file__).resolve().parent
sys.path.insert(0, str(SOURCE.parents[2] / "scripts"))
from cleanup import container_labels


def source_hashes():
    return {name: hashlib.sha256((SOURCE / name).read_bytes()).hexdigest()
            for name in ("Main.hs", "build.sh", "run.py")}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output_dir", type=Path, help="fresh disposable evidence directory")
    parser.add_argument("--owner", required=True, help="owner label for this disposable container")
    args = parser.parse_args()
    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=True)
    log, manifest = output / "native.log", output / "execution.json"
    if log.exists() or manifest.exists():
        raise ValueError("choose a fresh output directory; existing evidence is preserved")
    image = subprocess.check_output(
        ["docker", "image", "inspect", IMAGE, "--format", "{{.Id}}"], text=True).strip()
    if image != IMAGE:
        raise ValueError("the observed image identity differs from the pinned reference image")
    before = source_hashes()
    command = ["docker", "run", "--rm", "--name", args.owner + "-native",
               *container_labels("oracle", args.owner), "--network=none", "--read-only",
               "--entrypoint", "bash", "--tmpfs", "/tmp:rw,exec,size=512m",
               "--cpus=4", "--memory=16g", "--memory-swap=16g",
               "-v", str(SOURCE) + ":/witness:ro", image, "/witness/build.sh"]
    record = {"schemaVersion": 1, "imageId": image, "sourceSha256": before,
              "startedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
              "command": command, "owner": args.owner}
    manifest.write_text(json.dumps(record, indent=2) + "\n")
    with log.open("w") as target:
        result = subprocess.run(command, stdout=target, stderr=subprocess.STDOUT)
    record.update(finishedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                  exitCode=result.returncode, sourceAfterSha256=source_hashes(),
                  outputSha256=hashlib.sha256(log.read_bytes()).hexdigest())
    manifest.write_text(json.dumps(record, indent=2) + "\n")
    if result.returncode != 0:
        raise SystemExit(result.returncode)
    if record["sourceAfterSha256"] != before:
        raise ValueError("observer inputs changed during execution")
    print(json.dumps({"exitCode": 0, "imageId": image, "outputSha256": record["outputSha256"]}))


if __name__ == "__main__":
    main()
