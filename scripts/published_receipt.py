#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Authenticate a completed public-verification receipt before using a published baseline."""
import argparse
import base64
from dataclasses import dataclass, field
import re
import subprocess
from pathlib import Path

import release_identity
from publication_common import canonical, commit, decode_json, load_json, require, sha256


WORKFLOW = ".github/workflows/release-publication.yml"
CHECKS = {"hashes", "attestations", "sourceBinding", "bomBinding", "jarRuntime", "imageRuntime", "anonymousDownloads"}
_AUTHENTICATED = object()


@dataclass(frozen=True)
class AuthenticatedReceipt:
    """An in-process capability produced only after the real verifier succeeds."""
    encoded: bytes
    authority: object = field(repr=False)

    def __post_init__(self):
        require(self.authority is _AUTHENTICATED, "caller JSON is not an authenticated verification receipt")

    @property
    def document(self):
        return decode_json(self.encoded)


def validate(receipt, repository, source, version, phase="public"):
    require(set(receipt) == {"schemaVersion", "status", "phase", "repository", "tag", "sourceCommit",
                             "version", "channel", "candidateSha256", "subjects", "publisher", "checks"},
            "incomplete published-verification receipt")
    require(phase in {"staged", "public"} and type(receipt["schemaVersion"]) is int and receipt["schemaVersion"] == 1
            and receipt["status"] == "verified" and receipt["phase"] == phase,
            "a staged, failed or incomplete attempt is not a published baseline")
    commit(source)
    require(receipt["repository"] == repository and receipt["sourceCommit"] == source
            and receipt["version"] == version and receipt["tag"] == "v" + version
            and receipt["channel"] == release_identity.channel_for_version(version), "published baseline identity mismatch")
    sha256(receipt["candidateSha256"])
    subjects = receipt["subjects"]
    require(set(subjects) == {"jarSha256", "imageDigest", "bomSha256"}, "missing published artifact subjects")
    sha256(subjects["jarSha256"])
    require(isinstance(subjects["imageDigest"], str) and re.fullmatch(r"sha256:[0-9a-f]{64}", subjects["imageDigest"]),
            "immutable published OCI subject required")
    require(isinstance(subjects["bomSha256"], list) and len(subjects["bomSha256"]) == 2
            and len(set(subjects["bomSha256"])) == 2, "both subject-bound BOMs required")
    for value in subjects["bomSha256"]:
        sha256(value)
    publisher = receipt["publisher"]
    require(set(publisher) == {"workflow", "workflowCommit", "runId"}
            and publisher["workflow"] == WORKFLOW and publisher["workflowCommit"] == source
            and type(publisher["runId"]) is int and publisher["runId"] > 0, "unapproved receipt publisher")
    require(set(receipt["checks"]) == CHECKS
            and all(receipt["checks"][key] is True for key in CHECKS - {"anonymousDownloads"})
            and receipt["checks"]["anonymousDownloads"] is (phase == "public"),
            "every independent public verification must have completed")
    return receipt


def attestation_command(subject, repository, source, tag, predicate="https://slsa.dev/provenance/v1", gh="gh"):
    commit(source)
    return [str(gh), "attestation", "verify", str(subject), "--repo", repository,
            "--signer-workflow", repository + "/" + WORKFLOW, "--signer-digest", source,
            "--source-digest", source, "--source-ref", "refs/tags/" + tag,
            "--cert-oidc-issuer", "https://token.actions.githubusercontent.com",
            "--deny-self-hosted-runners", "--predicate-type", predicate, "--format", "json"]


def authenticate_receipt(path, repository, source, version, gh="gh", phase="public"):
    require(Path(path).stat().st_size <= 32768, "verification receipt exceeds bounded notes budget")
    receipt = validate(load_json(path), repository, source, version, phase)
    # Online lookup deliberately binds the currently named repository, unlike a name-only offline bundle.
    result = subprocess.run(attestation_command(path, repository, source, receipt["tag"], gh=gh),
                            check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    verified = decode_json(result.stdout)
    require(isinstance(verified, list) and verified, "no authenticated receipt attestation")
    return AuthenticatedReceipt(Path(path).read_bytes(), _AUTHENTICATED)


RECEIPT_MARKER = '<!-- scarab-public-verification-v1:'


def receipt_marker(proof):
    require(isinstance(proof, AuthenticatedReceipt), 'authenticated original receipt bytes required')
    from publication_common import digest
    return RECEIPT_MARKER + digest(proof.encoded) + ':' + base64.b64encode(proof.encoded).decode() + ' -->'


def extract_receipt(body):
    from publication_common import digest
    require(body.count(RECEIPT_MARKER) == 1, 'one retained public verification marker required')
    item = body.split(RECEIPT_MARKER, 1)[1].split(' -->', 1)[0]
    expected, encoded = item.split(':', 1)
    require(len(encoded) <= 45000, 'retained receipt exceeds notes budget')
    raw = base64.b64decode(encoded, validate=True)
    require(digest(raw) == sha256(expected), 'retained original receipt bytes changed')
    return raw


def authenticate(path, repository, source, version, gh="gh"):
    return authenticate_receipt(path, repository, source, version, gh).document


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--receipt", type=Path, required=True)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--source", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--gh", type=Path, default=Path("gh"))
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    require(release_identity.channel_for_version(args.version) == "prod", "a prerelease is not a stable baseline")
    receipt = authenticate(args.receipt, args.repository, args.source, args.version, args.gh)
    args.output.write_bytes(canonical(receipt) + b"\n")
    print("Authenticated completed stable published baseline")


if __name__ == "__main__":
    main()
