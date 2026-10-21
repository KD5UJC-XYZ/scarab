# SPDX-License-Identifier: Apache-2.0
"""Single-coordinator GitHub state transitions; no replacement assets or early completion."""
import re
import urllib.parse

import release_identity
from publication_common import PublicationError, canonical, commit, decode_json, file_digest, require, sha256
from published_receipt import AuthenticatedReceipt, receipt_marker, validate as validate_receipt
from production_acceptance import AuthenticatedAcceptance


MARKER = "<!-- scarab-publication-v1:"
STATES = {"draft", "draft-staged", "verified", "published", "aliased"}


def state_from_release(release):
    body = release.get("body", "")
    require(body.count(MARKER) == 1 and body.rstrip().endswith(" -->"), "release lacks one immutable-input state marker")
    value = decode_json(body.rsplit(MARKER, 1)[1].rstrip()[:-4])
    require(set(value) == {"schemaVersion", "status", "sourceCommit", "tag", "candidateSha256", "imageDigest"}
            and type(value["schemaVersion"]) is int and value["schemaVersion"] == 1 and value["status"] in STATES, "invalid publication state")
    return value


def body_with_state(notes, state):
    require(MARKER not in notes, "release notes cannot supply coordinator state")
    status = "VERIFICATION INCOMPLETE" if state["status"] != "aliased" else "INDEPENDENTLY VERIFIED"
    return f"**{status}**\n\n{notes.rstrip()}\n\n{MARKER}{canonical(state).decode()} -->\n"


def server_tag(github, tag):
    reference = github.get("/git/ref/tags/" + urllib.parse.quote(tag, safe=""))
    require(reference.get("ref") == "refs/tags/" + tag, "server returned a different tag ref")
    original = reference["object"]["sha"]
    current = reference["object"]
    for _ in range(8):
        commit(current["sha"])
        if current["type"] == "commit":
            return original, current["sha"]
        require(current["type"] == "tag", "release tag must resolve to a commit")
        current = github.get("/git/tags/" + current["sha"])["object"]
    raise PublicationError("annotated tag depth exceeds verification budget")


def find_release(github, tag, optional=False):
    # The by-tag endpoint promises published releases; push-authorized listing also exposes drafts.
    matches = [row for row in github.pages('/releases') if row.get('tag_name') == tag]
    require(len(matches) <= 1, 'ambiguous retained releases for this tag')
    require(optional or matches, 'retained release is missing or not visible with this credential')
    return matches[0] if matches else None


class Publication:
    def __init__(self, github, identity, candidate_sha256, image_digest):
        release_identity.validate(identity)
        require(identity["mode"] == "release", "development artifacts cannot be published")
        sha256(candidate_sha256)
        require(re.fullmatch(r"sha256:[0-9a-f]{64}", image_digest), "immutable OCI manifest required")
        self.github, self.identity = github, identity
        self.expected = {"schemaVersion": 1, "status": "draft", "sourceCommit": identity["gitCommit"],
                         "tag": identity["gitTag"], "candidateSha256": candidate_sha256, "imageDigest": image_digest}

    def checked_release(self, optional=False):
        release = find_release(self.github, self.identity["gitTag"], optional)
        if release is None:
            return None
        state = state_from_release(release)
        require({key: value for key, value in state.items() if key != "status"}
                == {key: value for key, value in self.expected.items() if key != "status"},
                "existing release belongs to different immutable inputs; never overwrite")
        require(release.get("tag_name") == self.identity["gitTag"]
                and release.get("prerelease") is (self.identity["channel"] != "prod"), "release tag/channel mismatch")
        require(release.get("draft") is (state["status"] in {"draft", "draft-staged", "verified"}),
                "public/draft state differs from coordinator record")
        return release

    def check_source(self, retry=False):
        tag_object, source = server_tag(self.github, self.identity["gitTag"])
        require(tag_object == self.identity["gitTagObject"] and source == self.identity["gitCommit"],
                "server tag moved or disagrees with candidate identity")
        branch = "heads/release/" + self.identity["channel"]
        ref = self.github.get("/git/ref/" + branch)
        require(ref.get("ref") == "refs/" + branch and ref["object"]["type"] == "commit", "approved source branch missing")
        tip = commit(ref["object"]["sha"])
        if retry:
            existing = self.checked_release()
            require(existing is not None, "ancestry retry requires a retained validated attempt")
            comparison = self.github.get("/compare/" + source + "..." + tip)
            require(comparison.get("status") in {"identical", "ahead"}, "retry source is no longer retained by its channel")
        else:
            require(source == tip, "first publication must use the approved release branch tip")

    def ensure_draft(self, notes):
        existing = self.checked_release(optional=True)
        self.check_source(retry=existing is not None)
        if existing:
            require(existing["body"].split(MARKER, 1)[0] == body_with_state(notes, state_from_release(existing)).split(MARKER, 1)[0],
                    "retry notes differ from the retained attempt")
            return existing
        return self.github.request("POST", self.github.repo_path("/releases"), payload={
            "tag_name": self.identity["gitTag"], "target_commitish": self.identity["gitCommit"],
            "name": self.identity["version"], "body": body_with_state(notes, self.expected),
            "draft": True, "prerelease": self.identity["channel"] != "prod", "make_latest": "false"})

    def upload(self, release, records):
        existing = self.github.pages(f"/releases/{release['id']}/assets")
        require(len({row["name"] for row in existing}) == len(existing), "ambiguous existing release assets")
        allowed = {row["name"]: row for row in records}
        require(len(allowed) == len(records) and {row["name"] for row in existing} <= set(allowed),
                "unexpected draft assets require manual investigation")
        inventory = {row["name"]: row for row in existing}
        for name, record in allowed.items():
            require(file_digest(record["path"]) == record["sha256"], "local candidate asset changed before upload")
            if name in inventory:
                remote = inventory[name]
                require(remote.get("size") == record["size"] and remote.get("digest") == "sha256:" + record["sha256"],
                        "existing draft asset differs or has no authoritative digest; never clobber")
            else:
                remote = self.github.upload_asset(release["id"], record["path"], name)
                require(remote.get("name") == name and remote.get("size") == record["size"]
                        and remote.get("digest") == "sha256:" + record["sha256"], "uploaded asset identity differs")

    def transition(self, previous, next_state, notes, verified_receipt=None, acceptance=None):
        require((previous, next_state) in {("draft", "draft-staged"), ("draft-staged", "verified"),
                                          ("verified", "published"), ("published", "aliased")}, "forbidden publication transition")
        release = self.checked_release()
        state = state_from_release(release)
        if state["status"] == next_state:
            return release
        require(state["status"] == previous, "publication state advanced unexpectedly or is incomplete")
        if next_state in {"verified", "published", "aliased"}:
            require(isinstance(verified_receipt, AuthenticatedReceipt), "independently authenticated verification receipt required")
            verified = validate_receipt(verified_receipt.document, self.github.repository,
                                        self.identity["gitCommit"], self.identity["version"],
                                        "public" if next_state == "aliased" else "staged")
            require(verified["candidateSha256"] == self.expected["candidateSha256"]
                    and verified["subjects"]["imageDigest"] == self.expected["imageDigest"], "verification describes other artifacts")
        if next_state == "published" and self.identity["channel"] == "prod":
            require(isinstance(acceptance, AuthenticatedAcceptance),
                    "production requires canonical exact-artifact produced-block acceptance")
            observed = acceptance.document
            require(observed["sourceCommit"] == self.identity["gitCommit"]
                    and observed["candidateSha256"] == self.expected["candidateSha256"]
                    and observed["imageDigest"] == self.expected["imageDigest"]
                    and observed["jarSha256"] == verified["subjects"]["jarSha256"],
                    "production acceptance describes other artifacts")
        updated = dict(state, status=next_state)
        complete_notes = notes + "\n\n" + receipt_marker(verified_receipt) if next_state == "aliased" else notes
        payload = {"body": body_with_state(complete_notes, updated), "make_latest": "false"}
        if next_state == "published":
            payload["draft"] = False
        return self.github.request("PATCH", self.github.repo_path(f"/releases/{release['id']}"), payload=payload)


def reserve_tag(github, tag, source, channel):
    """The protected App job reserves only deterministic lightweight tags, never moves them."""
    commit(source)
    require(tag.startswith("v") and release_identity.channel_for_version(tag[1:]) == channel, "tag/channel mismatch")
    branch = github.get("/git/ref/heads/release/" + channel)
    require(branch["object"]["sha"] == source and branch["object"]["type"] == "commit", "approved release branch tip changed")
    existing = github.optional("/git/ref/tags/" + tag)
    if existing:
        require(existing.get("ref") == "refs/tags/" + tag and existing["object"] == {"type": "commit", "sha": source,
                "url": existing["object"].get("url")}, "existing official tag differs; never move or replace it")
        return existing
    return github.request("POST", github.repo_path("/git/refs"), payload={"ref": "refs/tags/" + tag, "sha": source})
