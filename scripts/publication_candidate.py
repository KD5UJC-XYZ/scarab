# SPDX-License-Identifier: Apache-2.0
"""One candidate inventory drives upload names, signatures and independently fetched bytes."""
from pathlib import Path

from publication_common import asset_name, file_digest, load_json, require, safe_path, sha256


MAX_FILE_BYTES = 2 * 1024 ** 3
MAX_TOTAL_BYTES = 8 * 1024 ** 3


def descriptors(manifest):
    records = [manifest["source"]["archive"]]
    for subject in manifest["subjects"]:
        records.extend([subject["file"], subject["bom"]["file"]])
    records.extend(item["file"] for item in manifest["evidence"])
    require(1 <= len(records) <= 64, "bounded complete candidate inventory required")
    for record in records:
        require(isinstance(record, dict) and set(record) == {"path", "sha256", "size"}, "invalid candidate transport descriptor")
        safe_path(Path("/unused"), record["path"])
        sha256(record["sha256"])
        require(type(record["size"]) is int and 0 < record["size"] <= MAX_FILE_BYTES, "candidate asset exceeds transport policy")
    paths = [record["path"] for record in records]
    require(len(paths) == len(set(paths)) and "candidate.json" not in paths, "ambiguous candidate transport inventory")
    require(sum(record["size"] for record in records) <= MAX_TOTAL_BYTES, "candidate download budget exceeded")
    return sorted(records, key=lambda record: record["path"])


def records(root, manifest):
    candidate = Path(root) / "candidate.json"
    result = [{"path": "candidate.json", "name": "candidate.json", "sha256": file_digest(candidate), "size": candidate.stat().st_size}]
    result.extend(dict(record, name=asset_name(record["path"])) for record in descriptors(manifest))
    return result


def release_checksums(root, manifest):
    return "".join(f"{record['sha256']}  {record['name']}\n" for record in sorted(records(root, manifest), key=lambda row: row["name"])).encode()


def checked_candidate(root, expected_source, expected_tag, expected_channel, expected_hash):
    # This module is supplied by the candidate-building stage; no replacement permissive parser exists.
    import candidate_manifest
    candidate = Path(root) / "candidate.json"
    require(file_digest(candidate) == sha256(expected_hash), "candidate differs from the approved workflow artifact")
    manifest = candidate_manifest.validate(Path(root), require_release=True)
    identity = manifest["source"]["buildIdentity"]
    require(identity["gitCommit"] == expected_source and identity["gitTag"] == expected_tag
            and identity["channel"] == expected_channel, "candidate does not match the server/event source identity")
    descriptors(manifest)
    return manifest
