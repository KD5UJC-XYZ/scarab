#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Run the exact pinned Byron decoder and fee calculation on every supplied case."""
import argparse
import hashlib
import json
import pathlib
import shutil
import subprocess

HERE = pathlib.Path(__file__).resolve().parent
PIN = "f649f9751074d2ab3de033fc3912f29c9862c1f5"
PACKAGES = ["eras/byron/ledger/impl", "libs/cardano-ledger-binary"]
FILES = [
    "eras/byron/ledger/impl/src/Cardano/Chain/Update/ProtocolParametersUpdate.hs",
    "eras/byron/ledger/impl/src/Cardano/Chain/Common/TxFeePolicy.hs",
    "eras/byron/ledger/impl/src/Cardano/Chain/Common/TxSizeLinear.hs",
    "eras/byron/ledger/impl/src/Cardano/Chain/Common/Lovelace.hs",
    "libs/cardano-ledger-binary/src/Cardano/Ledger/Binary/Decoding/DecCBOR.hs",
    "libs/cardano-ledger-binary/src/Cardano/Ledger/Binary/Decoding/Decoder.hs",
]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", required=True, type=pathlib.Path)
    parser.add_argument("--probe-dir", required=True, type=pathlib.Path)
    parser.add_argument("--output-dir", required=True, type=pathlib.Path)
    parser.add_argument("--allow-download", action="store_true")
    parser.add_argument("--container", default="scarab-byron-params-native")
    parser.add_argument("--image", default="scarab-33-genesis-reference-cache:local")
    args = parser.parse_args()
    probe = args.probe_dir.resolve()
    probe.mkdir(parents=True, exist_ok=False)
    commands = []

    def run(argv):
        log = probe / f"command-{len(commands)}.log"
        with log.open("w") as stream:
            result = subprocess.run(argv, stdout=stream, stderr=subprocess.STDOUT, text=True)
        commands.append({"argv": argv, "exitCode": result.returncode, "logSha256": sha(log)})
        (probe / "commands.json").write_text(json.dumps(commands, indent=2) + "\n")
        if result.returncode:
            raise RuntimeError(f"command failed: {log}")
        return log.read_text().strip()

    if run(["git", "-C", str(args.source), "rev-parse", "HEAD"]) != PIN:
        raise ValueError("ledger source pin differs")
    archive = probe / "source.tar"
    run(["git", "-C", str(args.source), "archive", "--format=tar", "-o", str(archive), PIN, *PACKAGES])
    source = probe / "ledger"
    source.mkdir()
    run(["tar", "-xf", str(archive), "-C", str(source)])
    archive.unlink()
    shutil.copyfile(HERE / "Main.hs", probe / "Main.hs")
    shutil.copyfile(HERE / "inputs.jsonl", probe / "inputs.jsonl")
    (probe / "native.cabal").write_text("""cabal-version: 3.0
name: scarab-byron-params-observer
version: 0.0.0
build-type: Simple
executable scarab-byron-params-observer
  main-is: Main.hs
  default-language: Haskell2010
  ghc-options: -O0
  ld-options: -Wl,--whole-archive /usr/local/lib/libsodium.a -Wl,--no-whole-archive
  build-depends: base, aeson, bytestring, cardano-ledger-byron, cardano-ledger-binary
""")
    created = False
    try:
        run(["docker", "create", "--name", args.container, "--entrypoint", "tail",
             "-v", f"{probe}:/probe", "-w", "/probe", args.image, "-f", "/dev/null"])
        created = True
        run(["docker", "start", args.container])
        base = ["docker", "exec", args.container]
        package_paths = " ".join("ledger/" + p for p in PACKAGES)
        run(base + ["sh", "-c", "sed 's|packages: harness|packages: . " + package_paths +
                    "|' /oracle/project/cabal.project > cabal.project"])
        run(base + ["cabal", "build", *([] if args.allow_download else ["--offline"]), "exe:scarab-byron-params-observer",
                    "--builddir=/probe/builddir", "--jobs=2"])
        executable = run(base + ["cabal", "list-bin", "exe:scarab-byron-params-observer",
                                 "--builddir=/probe/builddir"]).splitlines()[-1]
        run(base + [executable, "/probe/inputs.jsonl", "/probe/native.jsonl"])
        args.output_dir.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(probe / "native.jsonl", args.output_dir / "native.jsonl")
        rows = [json.loads(line) for line in (probe / "native.jsonl").read_text().splitlines()]
        manifest = {
            "schema": "scarab-native-byron-params-v1",
            "sourceCommit": PIN,
            "sourceFiles": {p: sha(source / p) for p in FILES},
            "observerSha256": sha(HERE / "Main.hs"),
            "exporterSha256": sha(HERE / "export.py"),
            "inputsSha256": sha(HERE / "inputs.jsonl"),
            "corpusSha256": sha(args.output_dir / "native.jsonl"),
            "rows": len(rows),
            "feeOutcomes": sum(len(row.get("fees", [])) for row in rows),
            "image": run(["docker", "inspect", args.container, "--format", "{{.Image}}"]),
            "executableSha256": run(base + ["sha256sum", executable]).split()[0],
            "planSha256": sha(probe / "builddir/cache/plan.json"),
        }
        (args.output_dir / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    finally:
        if created:
            run(["docker", "rm", "-f", args.container])


if __name__ == "__main__":
    main()
