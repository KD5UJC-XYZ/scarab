# SPDX-License-Identifier: Apache-2.0
"""Check the native large-byte observation against its compact, independent wire recipe."""
import argparse
import datetime
import hashlib
import json
import re
from pathlib import Path

IMAGE = "sha256:10d59164dce6b49fbeafdc520c1a29d3e4024a5859a3c41bb6181b2e627bebc7"
NETWORK = "2fb69829a19b5c04e4fb212bdd78fb63f9d2d776"
CONSENSUS = "82ecba329d7d054340bf707d44fe6e9ac27cec40"


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path, help="stdout/stderr from the native build and run")
    parser.add_argument("--manifest", type=Path, required=True, help="execution.json from run.py")
    args = parser.parse_args()
    source = Path(__file__).resolve().parent
    root = source.parents[2]
    fixture = root / "fixtures/reference/chainsync-wide-serialised"
    manifest = json.loads(args.manifest.read_text())
    hashes = {name: digest(source / name) for name in ("Main.hs", "build.sh", "run.py")}
    if (manifest.get("schemaVersion") != 1 or manifest.get("imageId") != IMAGE
            or type(manifest.get("exitCode")) is not int or manifest["exitCode"] != 0
            or manifest.get("sourceSha256") != hashes
            or manifest.get("sourceAfterSha256") != hashes
            or manifest.get("outputSha256") != digest(args.output)):
        raise ValueError("execution manifest does not bind this output, image and current observer inputs")
    started = datetime.datetime.fromisoformat(manifest["startedAt"])
    if started.tzinfo is None:
        raise ValueError("execution timestamp must include its UTC offset")
    measured_at = started.astimezone(datetime.timezone.utc).date().isoformat()
    previous = json.loads((root / "fixtures/reference/chainsync-find-intersect/provenance.json").read_text())
    if (previous["sourceCommit"] != NETWORK or previous["consensusSourceCommit"] != CONSENSUS
            or IMAGE not in previous["observer"]["image"]):
        raise ValueError("shared package/source metadata does not describe this pinned image and reference")
    lines = args.output.read_text().splitlines()
    observations = [json.loads(line) for line in lines if line.startswith("{")]
    if len(observations) != 2 or [row.get("stage") for row in observations] != ["encoded", "decoded"]:
        raise ValueError("expected exactly the native encoding and decoding observations")

    size = 2147483649
    prefix, suffix = bytes.fromhex("8302d8185a80000001"), bytes.fromhex("828000")
    chunk = bytes([165]) * 1048576
    payload_hash, wire_hash = hashlib.sha256(), hashlib.sha256(prefix)
    for _ in range(size // len(chunk)):
        payload_hash.update(chunk)
        wire_hash.update(chunk)
    last = chunk[:size % len(chunk)]
    payload_hash.update(last)
    wire_hash.update(last)
    wire_hash.update(suffix)
    payload = {"bytes": size, "sha256": payload_hash.hexdigest()}
    wire = {"bytes": size + len(prefix) + len(suffix), "sha256": wire_hash.hexdigest()}
    first, second = observations
    result = second.get("result", {})
    if (first.get("feedChunkBytes") != len(chunk) or first.get("payload") != payload
            or first.get("wire") != wire or result.get("accepted") is not True
            or result.get("payload") != payload or result.get("encoded") != wire):
        raise ValueError("native byte counts/digests differ from the exact wire recipe")
    binaries = [line.split()[0] for line in lines
                if re.fullmatch(r"[0-9a-f]{64}  /tmp/wide-serialised-observer", line)]
    if len(binaries) != 1:
        raise ValueError("missing unique observer executable identity")

    expected = {
        "schemaVersion": 1, "id": "ntc-opaque-2gib-plus-one", "accepted": True,
        "feedChunkBytes": len(chunk),
        "recipe": {"payloadByte": 165, "payloadLength": size,
                   "prefixHex": prefix.hex(), "suffixHex": suffix.hex()},
        "payload": payload, "wire": wire,
    }
    (fixture / "expected.json").write_text(json.dumps(expected, indent=2) + "\n")
    (fixture / "native.jsonl").write_text("".join(
        json.dumps(row, sort_keys=True, separators=(",", ":")) + "\n" for row in observations))
    provenance = {
        "schemaVersion": 1, "measuredAt": measured_at,
        "scope": "One actual 2147483649-byte opaque Serialised payload encoded, decoded through plain runPeer in 1-MiB pieces, and re-encoded. Exact complete payload and wire SHA-256 identities agree. This is not a ledger-valid, forged, stored or NtN-propagated block.",
        "sourceCommit": previous["sourceCommit"],
        "consensusSourceCommit": previous["consensusSourceCommit"],
        "compiler": previous["compiler"], "packages": previous["packages"],
        "packageIds": previous["packageIds"], "packageArchives": previous["packageArchives"],
        "sourceFiles": {key: previous["sourceFiles"][key]
                        for key in ("chainSyncCodec", "block", "rawHash", "driver", "nodeToClient")},
        "observer": {"file": "scripts/oracle/chainsync-wide-serialised/Main.hs",
                     "sha256": digest(source / "Main.hs"),
                     "build": "scripts/oracle/chainsync-wide-serialised/build.sh",
                     "buildSha256": digest(source / "build.sh"),
                     "executableSha256": binaries[0], "image": previous["observer"]["image"]},
        "generatorSha256": digest(source / "capture.py"),
        "runnerSha256": digest(source / "run.py"),
        "executionManifestSha256": digest(args.manifest),
        "execution": {"imageId": manifest["imageId"], "startedAt": manifest["startedAt"],
                      "finishedAt": manifest["finishedAt"], "sourceSha256": hashes,
                      "rawOutputSha256": manifest["outputSha256"]},
        "expectedSha256": digest(fixture / "expected.json"),
        "nativeOutputSha256": digest(fixture / "native.jsonl"),
        "cases": 1, "accepted": 1, "refused": 0,
        "originalBytesNote": "The recipe reconstructs all original bytes; only compact observations and identities are published. No bulk payload or build output is retained.",
    }
    (fixture / "provenance.json").write_text(json.dumps(provenance, indent=2) + "\n")
    print(json.dumps({"accepted": True, "payload": payload, "wire": wire}))


if __name__ == "__main__":
    main()
