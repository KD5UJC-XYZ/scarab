#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Exercise actual sbt version settings in isolated minimal builds. Run inside the JDK image."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

import release_identity


def require(condition, detail):
    if not condition:
        raise ValueError(detail)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--case", action="append", help="run selected controls only")
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    source = (root / "build.sbt").read_text()
    settings = source[source.index("lazy val scarabBuildIdentity"):source.index("ThisBuild / organization")]
    plugins = [line for line in (root / "project/plugins.sbt").read_text().splitlines()
               if '"sbt-dynver"' in line]
    require(len(plugins) == 1, "exactly one pinned dynver plugin is required")
    full_commit = "a" * 40
    common = dict.fromkeys(release_identity.KEYS, "")
    common.update(schemaVersion="1", mode="development", version="0.0.0-dev.g" + full_commit,
                  gitCommit=full_commit, channel="development")
    stable = dict(common, mode="release", version="1.2.3", gitTag="v1.2.3", gitTagObject="b"*40,
                  channel="prod", sourceRef="refs/heads/release/prod", sourceRefCommit=full_commit)
    beta = dict(stable, version="1.2.3-beta.1", gitTag="v1.2.3-beta.1", channel="beta",
                sourceRef="refs/heads/release/beta")
    cases = [
        ("development-export", common, full_commit, common["version"], None),
        ("stable-export", stable, full_commit, "1.2.3", None),
        ("beta-export", beta, full_commit, "1.2.3-beta.1", None),
        ("workspace-without-git-executable", None, full_commit,
         "0.0.0-dev.g" + full_commit + ".workspace", None),
        ("archive-without-identity", None, None, None, "source has neither Git identity"),
        ("frozen-export-without-identity", None, full_commit, None, "frozen export has no verified build identity"),
        ("live-checkout-identity-override", stable, full_commit, None, "frozen identity cannot override a live Git checkout"),
        ("mismatched-version", dict(stable, version="9.9.9"), full_commit, None,
         "release tag, channel or approved source metadata disagree"),
        ("mismatched-channel", dict(stable, channel="beta"), full_commit, None,
         "release tag, channel or approved source metadata disagree"),
        ("mismatched-commit", stable, "c"*40, None, "frozen identity and source commit disagree"),
        ("missing-field", {key: value for key, value in stable.items() if key != "gitTag"}, full_commit,
         None, "incomplete frozen build identity"),
    ]
    if args.case:
        known = {case[0] for case in cases}
        if not set(args.case) <= known:
            parser.error("unknown control name")
        cases = [case for case in cases if case[0] in args.case]
    with tempfile.TemporaryDirectory(prefix="scarab-version-controls-") as temporary:
        for label, identity, commit, expected, refused in cases:
            location = Path(temporary) / label
            (location / "project").mkdir(parents=True)
            (location / "scripts").mkdir()
            shutil.copyfile(root / "scripts/sbtw", location / "scripts/sbtw")
            for name in ["BuildIdentity.scala", "GitHead.scala", "build.properties", "repositories"]:
                shutil.copyfile(root / "project" / name, location / "project" / name)
            (location / "project/plugins.sbt").write_text("\n".join(plugins) + "\n")
            (location / "build.sbt").write_text(settings + '''
val versionContract = taskKey[Unit]("Retain the resolved identity")
versionContract := IO.write(baseDirectory.value / "resolved-version.txt",
  Seq(version.value, dynver.value, isSnapshot.value.toString).mkString("\\n"))
''')
            if commit:
                (location / ".git").mkdir()
                (location / ".git/HEAD").write_text(commit + "\n")
            if label == "frozen-export-without-identity":
                (location / ".reproducible-source.json").write_text("{}")
            if label == "live-checkout-identity-override":
                (location / ".git/objects").mkdir()
            if identity:
                # Deliberately permit malformed controls here; production writer validates first.
                (location / release_identity.IDENTITY_FILE).write_text(
                    "".join(f"{key}={identity[key]}\n" for key in sorted(identity)))
            env = dict(os.environ, SBT_GLOBAL_BASE=str(location / "global"),
                       SCARAB_CACHE_DIR=os.environ.get("SCARAB_CACHE_DIR", str(root / ".cache")))
            result = subprocess.run(["bash", "scripts/sbtw", "versionContract"], cwd=location, env=env,
                                    text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            if refused:
                require(result.returncode != 0 and refused in result.stdout, f"{label}: expected refusal missing: {result.stdout[-5000:]}")
            else:
                require(result.returncode == 0, f"{label}: sbt failed: {result.stdout[-5000:]}")
                actual = (location / "resolved-version.txt").read_text().splitlines()
                require(actual == [expected, expected, str(identity is None or identity["mode"] != "release").lower()],
                        f"{label}: wrong identity: {actual}")
            print("PASS " + label, flush=True)
    print(f"PASS {len(cases)} real sbt identity controls", flush=True)


if __name__ == "__main__":
    main()
