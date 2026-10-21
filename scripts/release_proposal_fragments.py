#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Materialize the existing collector's reviewed patch in an isolated sparse checkout."""
import argparse
import json
from pathlib import Path
import subprocess
import tempfile

import release_fragments as fragments
from release_identity import channel_for_version


def materialize(repo, source, baseline, version, release_date):
    fragments.commit(repo, source)
    fragments.commit(repo, baseline)
    common = fragments.git(repo, "rev-parse", "--path-format=absolute", "--git-common-dir").decode().strip()
    with tempfile.TemporaryDirectory() as scratch:
        checkout = Path(scratch) / "repo"
        subprocess.run(["git", "init", "-q", str(checkout)], check=True)
        (checkout / ".git/objects/info/alternates").write_text(str(Path(common) / "objects") + "\n")
        fragments.git(checkout, "reset", "--mixed", source)
        paths = ["CHANGELOG.md"] + fragments.tree(repo, source, "changes") + fragments.tree(repo, source, ".release")
        fragments.git(checkout, "checkout", source, "--", *paths)
        output = Path(scratch) / "preview"
        fragments.prepare(checkout, output, source=source, baseline=baseline,
                          release_version=version, release_date=release_date)
        plan = fragments.load_json(output / "plan.json")
        notes = (output / "notes.md").read_text()
        files = {}

        def retain(path, content):
            before = fragments.current(checkout, path)
            files[path] = {"beforeSha256": fragments.identity(before), "content": content}

        if channel_for_version(version) == "prod":
            paths = ["CHANGELOG.md"] + [item["path"] for item in plan["inputs"]]
            paths += [f".release/changelog/{version}.json", f".release/changelog/{version}.md"]
            before = {path: fragments.identity(fragments.current(checkout, path)) for path in paths}
            fragments.apply(checkout, output / "plan.json")
            for path in paths:
                data = fragments.current(checkout, path)
                files[path] = {"beforeSha256": before[path], "content": data.decode() if data is not None else None}
            notes_path = f".release/changelog/{version}.md"
        else:
            notes_path = f".release/notes/{version}.md"
            fragments.require(fragments.current(checkout, notes_path) is None, "prerelease notes version already reserved")
            retain(notes_path, notes)
        return {"plan": plan, "planSha256": fragments.digest(fragments.json_bytes(plan)),
                "notesPath": notes_path, "files": files}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", required=True, type=Path)
    parser.add_argument("--source", required=True)
    parser.add_argument("--baseline", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--date", required=True)
    args = parser.parse_args()
    print(json.dumps(materialize(args.repo.resolve(), args.source, args.baseline, args.version, args.date)))


if __name__ == "__main__":
    main()
