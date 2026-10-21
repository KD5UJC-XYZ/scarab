#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Independently retrieve actual staged/public bytes, authenticate subjects and exercise them."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import uuid

from publication_candidate import checked_candidate, descriptors, release_checksums
from publication_common import GitHub, asset_name, canonical, decode_json, digest, file_digest, load_json, require, safe_path, sha256
from publication_coordinator import state_from_release, server_tag, find_release
from publication_download import download
from published_receipt import CHECKS, WORKFLOW, attestation_command, validate as validate_receipt


BUNDLES = {"provenance.sigstore.json", "jar-sbom.sigstore.json", "image-provenance.sigstore.json", "image-sbom.sigstore.json"}


def verify_subject(subject, expected_digest, bundle, github_identity, source, tag, gh,
                   predicate="https://slsa.dev/provenance/v1", expected_predicate=None, subject_name=None):
    command = attestation_command(subject, github_identity["full_name"], source, tag, predicate, gh)
    command.extend(["--bundle", str(bundle)])
    result = subprocess.run(command, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    verified = decode_json(result.stdout)
    require(isinstance(verified, list) and verified, "no authenticated subject attestations")
    matches = []
    for item in verified:
        proof = item["verificationResult"]
        certificate = proof["signature"]["certificate"]
        require(certificate.get("sourceRepositoryIdentifier") == str(github_identity["id"])
                and certificate.get("sourceRepositoryOwnerIdentifier") == str(github_identity["owner"]["id"]),
                "attestation belongs to a renamed/reused repository or owner")
        require(certificate.get("sourceRepositoryDigest") == source
                and certificate.get("buildSignerDigest") == source
                and certificate.get("sourceRepositoryRef") == "refs/tags/" + tag
                and certificate.get("issuer") == "https://token.actions.githubusercontent.com"
                and certificate.get("runnerEnvironment") == "github-hosted"
                and certificate.get("buildSignerURI") == "https://github.com/" + github_identity["full_name"] + "/" + WORKFLOW + "@refs/tags/" + tag,
                "verified certificate does not bind approved workflow/source/tag/issuer/runner")
        statement = proof["statement"]
        subject_matches = [row for row in statement["subject"] if row.get("digest", {}).get("sha256") == expected_digest
                           and (subject_name is None or row.get("name") == subject_name)]
        if subject_matches and (expected_predicate is None or canonical(statement["predicate"]) == canonical(expected_predicate)):
            matches.append(item)
    require(matches, "authenticated attestation does not bind the actual subject or supplied SBOM")
    return matches


def fetch_assets(github, release, root, candidate_hash, public, authority, source, tag, gh):
    assets = github.pages(f"/releases/{release['id']}/assets")
    require(len(assets) == len({row["name"] for row in assets}), "duplicate release asset names")
    inventory = {row["name"]: row for row in assets}
    require("candidate.json" in inventory, "release candidate manifest missing")
    candidate = inventory["candidate.json"]
    require(type(candidate.get("size")) is int and 0 < candidate["size"] <= 16 * 1024 * 1024,
            "candidate manifest download budget exceeded")

    def fetch(name, target, descriptor):
        require(name in inventory, "required release asset missing: " + name)
        asset = inventory[name]
        require(asset.get("size") == descriptor["size"] and asset.get("digest") == "sha256:" + descriptor["sha256"],
                "server release asset differs from pinned candidate")
        if public:
            expected = "https://github.com/" + github.repository + "/releases/download/" + release["tag_name"] + "/" + name
            require(asset.get("browser_download_url") == expected, "unexpected public download URL")
            url, token = expected, None
        else:
            url = github.base + github.repo_path(f"/releases/assets/{asset['id']}")
            token = github.token
        return download(url, target, descriptor, token=token,
                        local=github.base.startswith("http://127.0.0.1:") and not token)

    from publication_archive import MAX_ARCHIVE_BYTES, unpack
    import shutil
    transport = root.parent / "transport"
    transport.mkdir()
    for name, budget in [("provenance.sigstore.json", 32 * 1024 * 1024), ("candidate-transport.tar", min(MAX_ARCHIVE_BYTES, 2 * 1024 ** 3 - 1))]:
        require(name in inventory, "complete signed candidate transport missing")
        asset = inventory[name]
        require(type(asset.get("size")) is int and 0 < asset["size"] <= budget
                and isinstance(asset.get("digest"), str) and asset["digest"].startswith("sha256:"), "invalid transport descriptor")
        fetch(name, transport / name, {"sha256": sha256(asset["digest"][7:]), "size": asset["size"]})
    # Authenticate the exact archive before its members or nested JSON can influence extraction.
    verify_subject(transport / "candidate-transport.tar", file_digest(transport / "candidate-transport.tar"),
                   transport / "provenance.sigstore.json", authority, source, tag, gh)
    unpack(transport / "candidate-transport.tar", root)
    require(file_digest(root / "candidate.json") == candidate_hash, "restored candidate manifest differs from approved run")
    manifest = load_json(root / "candidate.json")
    standalone = root.parent / "standalone"
    fetch("candidate.json", standalone / "candidate.json", {"sha256": candidate_hash, "size": candidate["size"]})
    for record in descriptors(manifest):
        fetch(asset_name(record["path"]), safe_path(standalone, record["path"]), record)
    expected_checksums = release_checksums(root, manifest)
    fetch("SHA256SUMS", root / "SHA256SUMS", {"sha256": digest(expected_checksums), "size": len(expected_checksums)})
    shutil.copyfile(transport / "provenance.sigstore.json", root / "provenance.sigstore.json")
    for name in sorted(BUNDLES - {"provenance.sigstore.json"}):
        require(name in inventory, "attestation bundle missing: " + name)
        asset = inventory[name]
        require(type(asset.get("size")) is int and 0 < asset["size"] <= 32 * 1024 * 1024
                and isinstance(asset.get("digest"), str) and asset["digest"].startswith("sha256:"), "invalid bundle descriptor")
        fetch(name, root / name, {"sha256": sha256(asset["digest"][7:]), "size": asset["size"]})
    return manifest


def jar_smoke(artifact, source):
    import reproducible_build
    with tempfile.TemporaryDirectory(prefix="scarab-independent-jar-") as temporary:
        reproducible_build.jvm_resources(Path(temporary), artifact, source)
        usage = subprocess.run(["java", "-jar", str(artifact), "--help"], stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, text=True, timeout=60)
        require(usage.returncode == 2 and "usage: follow" in usage.stdout, "downloaded standalone JAR failed")
        native = subprocess.run(["java", "-cp", str(artifact), "xyz.kd5ujc.scarab.node.tools.BlstCheckMain"],
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=120)
        rows = [decode_json(line) for line in native.stdout.splitlines() if line.startswith("{")]
        require(native.returncode == 0 and any(row.get("ok") is True and row.get("library") == "portable" for row in rows),
                "downloaded JAR portable native/Plutus checks failed")


def docker_image_smoke(reference, expected_config, jar_hash, tools, output, anonymous, jdk_image, source):
    require(re.fullmatch(r"ghcr\.io/[a-z0-9_.-]+/[a-z0-9_./-]+@sha256:[0-9a-f]{64}", reference), "immutable GHCR reference required")
    environment = dict(os.environ)
    if anonymous:
        config = output / "anonymous-docker"
        config.mkdir()
        (config / "config.json").write_text("{}")
        environment.update(DOCKER_CONFIG=str(config))
        environment.pop("DOCKER_AUTH_CONFIG", None)
    subprocess.run(["docker", "pull", "--platform", "linux/amd64", reference], check=True, env=environment,
                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=600)
    return image_runtime_smoke(reference, expected_config, jar_hash, output, jdk_image, source, environment)


def image_runtime_smoke(reference, expected_config, jar_hash, output, jdk_image, source, environment=None):
    """Exercise immutable local content; publication calls this only after authenticated digest pull."""
    require(re.fullmatch(r'sha256:[0-9a-f]{64}|ghcr\.io/[a-z0-9_.-]+/[a-z0-9_./-]+@sha256:[0-9a-f]{64}', reference),
            'immutable image identity required for runtime verification')
    environment = dict(os.environ) if environment is None else environment
    inspected = decode_json(subprocess.check_output(["docker", "image", "inspect", reference], env=environment, text=True))[0]
    require(inspected["Id"] == expected_config and inspected["Architecture"] == "amd64" and inspected["Os"] == "linux",
            "pulled image config/platform differs from candidate")
    identifier = subprocess.check_output(["docker", "create", "--name", "scarab-release-verify-" + uuid.uuid4().hex[:12],
                                          reference], env=environment, text=True).strip()
    try:
        extracted = output / "image-scarab.jar"
        subprocess.run(["docker", "cp", identifier + ":/opt/scarab/scarab.jar", str(extracted)], check=True,
                       env=environment, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        require(file_digest(extracted) == jar_hash, "pulled image contains a different JAR")
    finally:
        subprocess.run(["docker", "rm", "-f", identifier], check=True, env=environment,
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    common = ["docker", "run", "--rm", "--network", "none", "--cpus=4", "--read-only", "--cap-drop", "ALL",
              "--security-opt", "no-new-privileges", "--tmpfs", "/tmp:rw,exec,nosuid,size=256m", "--user", "10001:10001"]
    import reproducible_build
    checks = output / "independent-jre-checks"
    checks.mkdir(mode=0o755)
    reproducible_build.resource_check_source(checks)
    subprocess.run(["docker", "run", "--rm", "--network", "none", "--cpus=2",
                    "--user", f"{os.getuid()}:{os.getgid()}", "-v", f"{checks}:/checks",
                    "-v", f"{extracted}:/artifact.jar:ro", "-w", "/checks", jdk_image,
                    "javac", "-cp", "/artifact.jar", "ArtifactResourcesCheck.java"], check=True,
                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=120)
    subprocess.run([*common, "-v", f"{checks}:/checks:ro", "--entrypoint", "java", reference,
                    "-cp", "/checks:/opt/scarab/scarab.jar", "ArtifactResourcesCheck", "/opt/scarab/scarab.jar", source],
                   check=True, env=environment, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=120)
    usage = subprocess.run([*common, reference, "--help"], env=environment, stdout=subprocess.PIPE,
                           stderr=subprocess.STDOUT, text=True, timeout=60)
    require(usage.returncode == 2 and "usage: follow" in usage.stdout, "published image standalone entrypoint failed")
    native = subprocess.run([*common, "--entrypoint", "java", reference, "-cp", "/opt/scarab/scarab.jar",
                             "xyz.kd5ujc.scarab.node.tools.BlstCheckMain"], env=environment, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, text=True, timeout=120)
    rows = [decode_json(line) for line in native.stdout.splitlines() if line.startswith("{")]
    require(native.returncode == 0 and any(row.get("ok") is True and row.get("library") == "portable" for row in rows),
            "published image portable native/Plutus checks failed")
    return environment


def verify(args):
    root = args.output.resolve()
    root.mkdir(parents=True, exist_ok=False)
    public = args.phase == "public"
    github = GitHub(args.repository, None if public else os.environ.get("GH_TOKEN"))
    release = find_release(github, args.tag)
    state = state_from_release(release)
    require(release.get("draft") is (not public) and release.get("prerelease") is (args.channel != "prod")
            and state["candidateSha256"] == args.candidate_sha256 and state["sourceCommit"] == args.source
            and state["tag"] == args.tag
            and state["status"] in ({"published", "aliased"} if public else {"draft-staged", "verified"}),
            "release visibility or immutable attempt identity differs")
    authority = github.request("GET", "/repos/" + args.repository)
    manifest = fetch_assets(github, release, root / "candidate", args.candidate_sha256, public,
                            authority, args.source, args.tag, args.tools / "gh")
    manifest = checked_candidate(root / "candidate", args.source, args.tag, args.channel, args.candidate_sha256)
    require(state["imageDigest"] == manifest["image"]["manifest"]["digest"], "release image identity differs")
    identity = manifest["source"]["buildIdentity"]
    tag_object, tag_source = server_tag(github, args.tag)
    require(tag_object == identity["gitTagObject"] and tag_source == identity["gitCommit"],
            "official tag moved or disagrees with the independently retrieved candidate")
    authority = github.request("GET", "/repos/" + args.repository)
    candidate_root = root / "candidate"
    gh = args.tools / "gh"
    for relative in ["candidate.json", "SHA256SUMS", *[row["path"] for row in descriptors(manifest)]]:
        path = safe_path(candidate_root, relative)
        verify_subject(path, file_digest(path), candidate_root / "provenance.sigstore.json", authority,
                       args.source, args.tag, gh)
    jar = next(row for row in manifest["subjects"] if row["kind"] == "jar")
    oci = next(row for row in manifest["subjects"] if row["kind"] == "oci-archive")
    verify_subject(safe_path(candidate_root, jar["file"]["path"]), jar["file"]["sha256"],
                   candidate_root / "jar-sbom.sigstore.json", authority, args.source, args.tag, gh,
                   "https://cyclonedx.org/bom", load_json(safe_path(candidate_root, jar["bom"]["file"]["path"])))
    require(re.fullmatch(r"ghcr\.io/[a-z0-9_.-]+/[a-z0-9_./-]+", args.image_repository), "invalid pinned image repository")
    reference = args.image_repository + "@" + manifest["image"]["manifest"]["digest"]
    manifest_path = root / "oci-manifest.json"
    registry_config = root / "registry-config.json"
    if public:
        registry_config.write_text("{}")
    else:
        registry_config = Path(os.environ.get("DOCKER_CONFIG", str(Path.home() / ".docker"))) / "config.json"
    subprocess.run([str(args.tools / "oras"), "manifest", "fetch", "--registry-config", str(registry_config),
                    "--output", str(manifest_path), reference], check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    require("sha256:" + file_digest(manifest_path) == manifest["image"]["manifest"]["digest"], "retrieved OCI manifest changed")
    verify_subject(manifest_path, file_digest(manifest_path), candidate_root / "image-provenance.sigstore.json",
                   authority, args.source, args.tag, gh, subject_name=args.image_repository)
    verify_subject(manifest_path, file_digest(manifest_path), candidate_root / "image-sbom.sigstore.json",
                   authority, args.source, args.tag, gh, "https://cyclonedx.org/bom",
                   load_json(safe_path(candidate_root, oci["bom"]["file"]["path"])), args.image_repository)
    require(re.fullmatch(r"sha256:[0-9a-f]{64}", args.jdk_image), "immutable local verifier JDK image required")
    scripts = Path(__file__).resolve().parent
    subprocess.run(["docker", "run", "--rm", "--network", "none", "--cpus=4", "--read-only",
                    "--tmpfs", "/tmp:rw,exec,nosuid,size=512m", "--user", f"{os.getuid()}:{os.getgid()}",
                    "-v", f"{scripts}:/verifier:ro", "-v", f"{candidate_root}:/candidate:ro", "-w", "/verifier",
                    args.jdk_image, "python3", "verify_published_release.py", "--jar-smoke",
                    "/candidate/" + jar["file"]["path"], "--source", args.source], check=True)
    docker_image_smoke(reference, manifest["image"]["configDigest"], jar["file"]["sha256"], args.tools, root, public, args.jdk_image, args.source)
    result = {"schemaVersion": 1, "status": "verified", "phase": args.phase, "repository": args.repository,
              "tag": args.tag, "sourceCommit": args.source, "version": identity["version"], "channel": args.channel,
              "candidateSha256": args.candidate_sha256,
              "subjects": {"jarSha256": jar["file"]["sha256"], "imageDigest": manifest["image"]["manifest"]["digest"],
                           "bomSha256": sorted(subject["bom"]["file"]["sha256"] for subject in manifest["subjects"])},
              "publisher": {"workflow": WORKFLOW, "workflowCommit": args.source, "runId": args.run_id},
              "checks": {key: (public if key == "anonymousDownloads" else True) for key in CHECKS}}
    validate_receipt(result, args.repository, args.source, identity["version"], args.phase)
    (root / "verification.json").write_bytes(canonical(result) + b"\n")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar-smoke", type=Path)
    parser.add_argument("--source", required=True)
    parser.add_argument("--repository")
    parser.add_argument("--tag")
    parser.add_argument("--channel", choices=["alpha", "beta", "preprod", "prod"])
    parser.add_argument("--candidate-sha256")
    parser.add_argument("--image-repository")
    parser.add_argument("--phase", choices=["staged", "public"])
    parser.add_argument("--run-id", type=int)
    parser.add_argument("--tools", type=Path)
    parser.add_argument("--jdk-image")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if args.jar_smoke:
        jar_smoke(args.jar_smoke.resolve(), args.source)
    else:
        require(all([args.repository, args.tag, args.channel, args.candidate_sha256, args.image_repository,
                     args.phase, args.run_id, args.tools, args.jdk_image, args.output]), "complete verification inputs required")
        verify(args)
        print("Independent " + args.phase + " artifact verification passed")


if __name__ == "__main__":
    main()
