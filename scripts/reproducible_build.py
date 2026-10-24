#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Export an immutable revision and run sbt's clean artifact comparison in one JDK image."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import uuid
import zipfile

import release_identity


MANIFEST = ".reproducible-source.json"


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def compare(first, second):
    if sha256(first) == sha256(second):
        return
    with zipfile.ZipFile(first) as left, zipfile.ZipFile(second) as right:
        left_names, right_names = set(left.namelist()), set(right.namelist())
        names = sorted(left_names | right_names)
        changed = [name for name in names if name not in left_names
                   or name not in right_names or left.read(name) != right.read(name)]
    detail = ", ".join(changed[:20]) or "JAR metadata or entry ordering"
    raise ValueError(f"artifacts differ: {detail}")


def require_export_contract(source):
    required = ["project/BuildIdentity.scala", "scripts/release_identity.py", "build.sbt"]
    if (not all((source / name).is_file() for name in required)
            or "scarabBuildIdentity" not in (source / "build.sbt").read_text(encoding="utf-8")):
        raise ValueError("revision predates the verified version export contract; use its historical verifier separately")


def verify_output_identity(output, build_identity):
    """Host-side check of actual bytes, never the selected revision's PASS message."""
    release_identity.validate(build_identity)
    reports = json.loads((output / "artifact-builds.json").read_text(encoding="utf-8"))
    if not isinstance(reports, list) or len(reports) != 2:
        raise ValueError("two original build artifact reports are required")
    verified = []
    for index, report in enumerate(reports, 1):
        artifact = output / f"build-{index}.jar"
        expected_name = f"scarab-node-{build_identity['version']}-assembly.jar"
        if (not isinstance(report, dict) or set(report) != {"file", "assemblyName", "sha256"}
                or not artifact.is_file() or artifact.is_symlink() or report.get("file") != artifact.name
                or report.get("assemblyName") != expected_name or report.get("sha256") != sha256(artifact)):
            raise ValueError("actual artifact digest or assembly filename differs from its frozen build report")
        with zipfile.ZipFile(artifact) as jar:
            names = jar.namelist()
            if len(names) != len(set(names)):
                raise ValueError("artifact has ambiguous duplicate ZIP entries")
            metadata = dict(line.split("=", 1) for line in jar.read(
                "META-INF/scarab/build-info.properties").decode("utf-8").splitlines())
            expected = {key: build_identity[key] for key in ["version", "gitCommit", "gitTag", "gitTagObject", "sourceRef", "sourceRefCommit"]}
            expected.update(buildMode=build_identity["mode"], releaseChannel=build_identity["channel"])
            if any(metadata.get(key) != value for key, value in expected.items()):
                raise ValueError("actual artifact metadata differs from the host-verified Git identity")
            attributes = manifest_attributes(jar.read("META-INF/MANIFEST.MF"))
            if attributes.get("implementation-version") != build_identity["version"]:
                raise ValueError("actual JAR manifest version differs from the host-verified Git identity")
        verified.append(dict(file=artifact.name, assemblyName=expected_name, sha256=sha256(artifact)))
    compare(output / "build-1.jar", output / "build-2.jar")
    return verified


def copy_source(source, destination, manifest):
    destination.mkdir(parents=True)
    for name, expected in manifest["files"].items():
        original = source / name
        if original.is_symlink() or not original.is_file() or sha256(original) != expected:
            raise ValueError(f"frozen source changed or is not a regular file: {name}")
        target = destination / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(original, target)
    identity = release_identity.validate(manifest["buildIdentity"])
    expected = "".join(f"{key}={identity[key]}\n" for key in sorted(identity))
    if (destination / release_identity.IDENTITY_FILE).read_text(encoding="utf-8") != expected:
        raise ValueError("frozen build identity differs from the verified export manifest")
    metadata = destination / ".git"
    metadata.mkdir()
    (metadata / "HEAD").write_text(manifest["gitCommit"] + "\n", encoding="utf-8")


def manifest_attributes(raw):
    text = raw.decode("utf-8").replace("\r\n", "\n").replace("\n ", "")
    return {key.lower(): value for line in text.split("\n\n", 1)[0].splitlines() if ": " in line
            for key, value in [line.split(": ", 1)]}


def providers(raw):
    return {line.split("#", 1)[0].strip() for line in raw.decode("utf-8").splitlines()
            if line.split("#", 1)[0].strip()}


def legal_metadata(name):
    leaf = name.rsplit("/", 1)[-1].lower()
    return (leaf.startswith(("license", "licence", "notice", "copying", "dependencies"))
            and not leaf.endswith((".class", ".tasty")))


def verify_resources(artifact, source, classpath, commit, build_identity=None):
    with zipfile.ZipFile(artifact) as jar:
        names = jar.namelist()
        members = set(names)
        if len(names) != len(members) or any(name.startswith("/") or ".." in Path(name).parts
                                              for name in names):
            raise ValueError("fat JAR has duplicate or unsafe entry names")
        attributes = manifest_attributes(jar.read("META-INF/MANIFEST.MF"))
        if (attributes.get("main-class") != "xyz.kd5ujc.scarab.node.Main"
                or attributes.get("multi-release") != "true" or "class-path" in attributes):
            raise ValueError("fat JAR manifest must declare the node, multi-release classes and no external classpath")
        identity = dict(line.split("=", 1) for line in jar.read(
            "META-INF/scarab/build-info.properties").decode("utf-8").splitlines())
        if identity.get("gitCommit") != commit:
            raise ValueError("fat JAR source identity differs from the frozen commit")
        if build_identity is not None:
            expected = {key: build_identity[key] for key in ["version", "gitCommit", "gitTag", "gitTagObject", "sourceRef", "sourceRefCommit"]}
            expected.update(buildMode=build_identity["mode"], releaseChannel=build_identity["channel"])
            if any(identity.get(key) != value for key, value in expected.items()):
                raise ValueError("fat JAR version/release metadata differs from the frozen identity")
        for name in ("LICENSE", "NOTICE"):
            target = "META-INF/scarab/" + name
            if target not in members or jar.read(target) != (source / name).read_bytes():
                raise ValueError(f"fat JAR changed project legal metadata: {name}")
        portable = "supranational/blst/Linux/amd64/libblst.so"
        if jar.read(portable) != (source / "blst-native/src/main/resources" / portable).read_bytes():
            raise ValueError("fat JAR did not preserve the committed portable blst overlay")
        if any(name.endswith("module-info.class") for name in names):
            raise ValueError("fat JAR retains a dependency module descriptor")
        if any(name.startswith("META-INF/") and name.count("/") == 1
               and (name.upper().endswith((".SF", ".DSA", ".RSA", ".EC"))
                    or name.rsplit("/", 1)[-1].upper().startswith("SIG-")) for name in names):
            raise ValueError("fat JAR retains invalid dependency signatures")
        retained_legal = {hashlib.sha256(jar.read(name)).digest()
                          for name in names if legal_metadata(name) and not name.endswith("/")}
        expected_services = {}

        def verify(name, raw):
            if name.startswith("META-INF/services/"):
                expected_services.setdefault(name, set()).update(providers(raw))
            elif legal_metadata(name):
                if hashlib.sha256(raw).digest() not in retained_legal:
                    raise ValueError(f"fat JAR lost dependency legal metadata: {name}")
            elif (name != portable and name.endswith((".so", ".dll", ".dylib", ".jnilib"))
                  or name.startswith("META-INF/versions/") and not name.endswith("module-info.class")):
                if name not in members or jar.read(name) != raw:
                    raise ValueError(f"fat JAR changed native or multi-release resource: {name}")

        for entry in classpath:
            if entry.is_dir():
                for file in entry.rglob("*"):
                    if file.is_file():
                        name = file.relative_to(entry).as_posix()
                        if name.startswith("META-INF/services/") or legal_metadata(name):
                            verify(name, file.read_bytes())
            else:
                with zipfile.ZipFile(entry) as dependency:
                    for name in dependency.namelist():
                        if not name.endswith("/") and (name.startswith(("META-INF/services/", "META-INF/versions/"))
                                or legal_metadata(name) or name.endswith((".so", ".dll", ".dylib", ".jnilib"))):
                            verify(name, dependency.read(name))
        for name, expected in expected_services.items():
            if name not in members or providers(jar.read(name)) != expected:
                raise ValueError(f"fat JAR lost or added service providers: {name}")


def resource_check_source(runtime):
    source = runtime / "ArtifactResourcesCheck.java"
    source.write_text("""// SPDX-License-Identifier: Apache-2.0
class ArtifactResourcesCheck {
  public static void main(String[] args) throws Exception {
    final ClassLoader loader = ClassLoader.getSystemClassLoader();
    for (final String directory : new String[] {
        "xyz/kd5ujc/scarab/node/", "supranational/blst/Linux/amd64/", "natives/linux_64/"}) {
      if (loader.getResource(directory) == null)
        throw new IllegalStateException("missing directory resource: " + directory);
    }
    final boolean logging = java.util.ServiceLoader.load(org.slf4j.spi.SLF4JServiceProvider.class)
      .stream().anyMatch(p -> p.type().getName().equals("xyz.kd5ujc.scarab.node.log.LibraryLogsProvider"));
    if (!logging) throw new IllegalStateException("missing node logging service provider");
    final java.util.Properties metadata = new java.util.Properties();
    try (java.io.InputStream in = loader.getResourceAsStream("META-INF/scarab/build-info.properties")) {
      metadata.load(in);
    }
    if (!System.getProperty("java.vendor").equals(metadata.getProperty("javaVendor")) ||
        !System.getProperty("java.runtime.version").equals(metadata.getProperty("javaRuntimeVersion")))
      throw new IllegalStateException("JDK identity does not match the build image");
    final Class<?> info = Class.forName("xyz.kd5ujc.scarab.node.BuildInfo$");
    final Object instance = info.getField("MODULE$").get(null);
    if (!args[1].substring(0, 8).equals(info.getMethod("gitCommit").invoke(instance)))
      throw new IllegalStateException("BuildInfo does not identify the frozen source");
    for (final String field : new String[] {"version", "scalaVersion", "sbtVersion", "buildMode", "releaseChannel", "gitTag"}) {
      if (!metadata.getProperty(field).equals(info.getMethod(field).invoke(instance)))
        throw new IllegalStateException("compiled BuildInfo and artifact metadata disagree: " + field);
    }
    try (java.util.jar.JarFile jar = new java.util.jar.JarFile(new java.io.File(args[0]), true,
          java.util.zip.ZipFile.OPEN_READ, Runtime.version())) {
      final java.util.jar.JarEntry entry = jar.getJarEntry(
        "org/bouncycastle/jcajce/provider/asymmetric/edec/BC11XDHPrivateKey.class");
      if (!jar.isMultiRelease() || entry == null || !entry.getRealName().startsWith("META-INF/versions/"))
        throw new IllegalStateException("multi-release dependency lookup was not retained");
    }
    javax.crypto.Cipher.getInstance("AES/GCM/NoPadding", new org.bouncycastle.jce.provider.BouncyCastleProvider());
    org.rocksdb.RocksDB.loadLibrary();
  }
}
""", encoding="utf-8")
    return source


def jvm_resources(runtime, artifact, commit):
    source = resource_check_source(runtime)
    result = subprocess.run(["java", "-cp", str(artifact), source.name, str(artifact), commit],
                            cwd=runtime, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=60)
    if result.returncode:
        raise ValueError(f"fat JAR resource/service/identity check failed: {result.stdout}")


def smoke(artifact, source, classpath, commit, build_identity=None):
    verify_resources(artifact, source, classpath, commit, build_identity)
    with tempfile.TemporaryDirectory(prefix="scarab-fatjar-runtime-") as scratch:
        runtime = Path(scratch)
        jvm_resources(runtime, artifact, commit)
        cli = subprocess.run(["java", "-jar", str(artifact), "--help"], cwd=runtime,
                             text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=60)
        if cli.returncode != 2 or "usage: follow" not in cli.stdout:
            raise ValueError(f"fat JAR java -jar smoke failed: {cli.stdout}")
        native = subprocess.run(["java", "-cp", str(artifact),
                                 "xyz.kd5ujc.scarab.node.tools.BlstCheckMain"], cwd=runtime,
                                text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=120)
        if native.returncode or not any(
                line.startswith("{") and json.loads(line).get("ok")
                and json.loads(line).get("library") == "portable"
                for line in native.stdout.splitlines()):
            raise ValueError(f"fat JAR native/Plutus check failed: {native.stdout}")


def inside_jdk():
    source = Path.cwd()
    manifest = json.loads((source / MANIFEST).read_text(encoding="utf-8"))
    if not manifest["files"] or len(manifest["gitCommit"]) != 40:
        raise ValueError("a frozen git source export is required; use the host entrypoint")
    output = Path(os.environ["SCARAB_REPRO_OUTPUT"])
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="scarab-repro-", dir=output if os.environ.get("SCARAB_REPRO_INVENTORY") == "1" else None) as scratch:
        locations = [Path(scratch) / "first", Path(scratch) / "different" / "depth" / "second"]
        artifacts = []
        reports = []
        for index, location in enumerate(locations, 1):
            copy_source(source, location, manifest)
            if any(location.rglob("target")):
                raise ValueError("clean source export unexpectedly contains target outputs")
            log = output / f"build-{index}.log"
            env = dict(os.environ, SBT_GLOBAL_BASE=f"/tmp/sbt-repro-{index}")
            print(f"Clean build {index}/2: {location}; log {log}", flush=True)
            tasks = (["check"] if index == 1 and os.environ.get("SCARAB_REPRO_CHECK") == "1" else [])
            inventory_tasks = ["node/releaseInventory"] if os.environ.get("SCARAB_REPRO_INVENTORY") == "1" else []
            with log.open("w", encoding="utf-8") as stream:
                result = subprocess.run(["bash", "scripts/sbtw", *tasks, "node/assembly", "node/runtimeClasspathFile", *inventory_tasks],
                                        cwd=location, env=env, stdout=stream, stderr=subprocess.STDOUT)
            if result.returncode:
                print("\n".join(log.read_text(encoding="utf-8").splitlines()[-80:]), flush=True)
                raise ValueError(f"clean build {index} failed with exit {result.returncode}")
            produced = list((location / "node/target").glob("scala-*/*-assembly.jar"))
            if len(produced) != 1:
                raise ValueError(f"expected one fat JAR, found {produced}")
            expected_name = f"scarab-node-{manifest['buildIdentity']['version']}-assembly.jar"
            if produced[0].name != expected_name:
                raise ValueError("fat JAR filename differs from the frozen version")
            artifact = output / f"build-{index}.jar"
            shutil.copyfile(produced[0], artifact)
            classpath = [Path(entry) for entry in (location / "node/target/runtime-classpath.txt")
                         .read_text(encoding="utf-8").strip().split(":")]
            smoke(artifact, location, classpath, manifest["gitCommit"], manifest["buildIdentity"])
            if inventory_tasks:
                from assembly_inventory import export
                export(artifact, location / "node/target/release-inventory.json", output / f"inventory-{index}")
            print(f"Build {index}: fat JAR resources, java -jar and portable native/Plutus checks passed", flush=True)
            artifacts.append(artifact)
            reports.append(dict(file=artifact.name, assemblyName=produced[0].name, sha256=sha256(artifact)))
            print(f"Build {index}: {sha256(artifact)}", flush=True)
        compare(*artifacts)
        (output / "artifact-builds.json").write_text(json.dumps(reports, sort_keys=True) + "\n", encoding="utf-8")
        print(f"PASS: two clean builds of {manifest['gitCommit']} have identical bytes", flush=True)


def export_source(repo, revision, output, **release_inputs):
    identity = release_identity.resolve(repo, revision, **release_inputs)
    commit = identity["gitCommit"]
    archive = output.parent / "source.tar"
    with archive.open("wb") as stream:
        subprocess.run(["git", "archive", "--format=tar", commit], cwd=repo, stdout=stream, check=True)
    output.mkdir()
    with tarfile.open(archive) as tree:
        tree.extractall(output, filter="data")
    require_export_contract(output)
    if any((output / name).exists() for name in [release_identity.IDENTITY_FILE, MANIFEST]):
        raise ValueError("source revision contains reserved generated export metadata")
    if release_identity.resolve(repo, commit, **release_inputs) != identity:
        raise ValueError("version authority changed during source export")
    release_identity.write(identity, output / release_identity.IDENTITY_FILE)
    files = {str(path.relative_to(output)): sha256(path)
             for path in sorted(output.rglob("*")) if path.is_file()}
    manifest = {"gitCommit": commit, "buildIdentity": identity, "files": files}
    (output / MANIFEST).write_text(json.dumps(manifest, sort_keys=True), encoding="utf-8")
    (output / ".git").mkdir()
    (output / ".git/HEAD").write_text(commit + "\n", encoding="utf-8")
    return commit


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--revision", default="HEAD", help="immutable commit to export (default HEAD)")
    parser.add_argument("--image", default="scarab-jdk:local", help="JDK image; resolved to its image ID")
    parser.add_argument("--cache", type=Path, default=Path(os.environ.get("SCARAB_CACHE_DIR", ".cache")))
    parser.add_argument("--output", type=Path, help="fresh directory for two artifacts and build logs")
    parser.add_argument("--check", action="store_true", help="also run the full check gate in the first clean build")
    parser.add_argument("--inventory", action="store_true", help="retain reconciled runtime/assembly inputs for both builds")
    parser.add_argument("--release-tag", help="exact vSemVer tag; enables strict release validation")
    parser.add_argument("--channel", choices=["alpha", "beta", "preprod", "prod"])
    parser.add_argument("--expected-commit", help="trusted full source SHA, not inferred from a moving tag")
    parser.add_argument("--expected-tag-object", help="trusted full tag object SHA (commit SHA for lightweight tags)")
    parser.add_argument("--source-ref", help="approved release/{alpha,beta,preprod,prod} local or origin remote-tracking ref")
    parser.add_argument("--expected-version", help="optional proposal cross-check; cannot override the tag")
    parser.add_argument("--inside-jdk", action="store_true", help=argparse.SUPPRESS)
    parser.add_argument("--verify-output-jvm", type=Path, help=argparse.SUPPRESS)
    args = parser.parse_args()
    if args.verify_output_jvm:
        if args.expected_commit is None:
            parser.error("independent JVM verification requires the host-verified source commit")
        for index in [1, 2]:
            with tempfile.TemporaryDirectory(prefix="scarab-outer-jvm-") as temporary:
                jvm_resources(Path(temporary), args.verify_output_jvm / f"build-{index}.jar", args.expected_commit)
        return
    if args.inside_jdk:
        inside_jdk()
        return
    repo = Path(__file__).resolve().parent.parent
    output = (args.output or Path(tempfile.gettempdir()) / f"scarab-repro-{uuid.uuid4().hex}").resolve()
    output.mkdir(parents=True, exist_ok=False)
    cache = args.cache.resolve()
    cache.mkdir(parents=True, exist_ok=True)
    image = subprocess.check_output(
        ["docker", "image", "inspect", args.image, "--format", "{{.Id}}"], text=True).strip()
    with tempfile.TemporaryDirectory(prefix="scarab-frozen-source-", dir=output.parent if args.inventory else None) as scratch:
        source = Path(scratch) / "source"
        commit = export_source(repo, args.revision, source, tag=args.release_tag, channel=args.channel,
                               expected_commit=args.expected_commit, expected_tag_object=args.expected_tag_object,
                               source_ref=args.source_ref, expected_version=args.expected_version)
        manifest = json.loads((source / MANIFEST).read_text(encoding="utf-8"))
        if args.inventory:
            shutil.copyfile(source.parent / "source.tar", output / "source.tar")
            shutil.copyfile(source / MANIFEST, output / "source-manifest.json")
        print(f"Source {commit}; JDK image {image}; output {output}", flush=True)
        (output / "build-environment.json").write_text(
            json.dumps({"gitCommit": commit, "jdkImage": image, "buildIdentity": manifest["buildIdentity"]}, sort_keys=True) + "\n", encoding="utf-8")
        subprocess.run([
            "docker", "run", "--rm", "--cpus=4", "--name", f"scarab-repro-{uuid.uuid4().hex[:12]}",
            "--user", f"{os.getuid()}:{os.getgid()}", "-v", f"{source}:/source",
            "-v", f"{cache}:/cache", "-v", f"{output}:/output", "-w", "/source",
            "-e", "HOME=/cache/home", "-e", "SCARAB_CACHE_DIR=/cache",
            "-e", "SBT_GLOBAL_BASE=/tmp/sbt-repro-orchestrator", "-e", "SCARAB_REPRO_OUTPUT=/output",
            "-e", "TZ=UTC", "-e", "LC_ALL=C.UTF-8",
            "-e", f"SCARAB_REPRO_CHECK={int(args.check)}",
            "-e", f"SCARAB_REPRO_INVENTORY={int(args.inventory)}", image,
            "bash", "scripts/sbtw", "reproducibleCheck"
        ], check=True)
        verified = verify_output_identity(output, manifest["buildIdentity"])
        # Execute this caller's verifier, not code from the selected revision. Expected identity
        # stays in host memory while the build runs; child logs/receipts cannot override it.
        subprocess.run([
            "docker", "run", "--rm", "--network", "none", "--cpus=4",
            "--user", f"{os.getuid()}:{os.getgid()}", "-v", f"{repo / 'scripts'}:/verifier:ro",
            "-v", f"{output}:/artifacts:ro", "-w", "/verifier", image,
            "python3", "reproducible_build.py", "--verify-output-jvm", "/artifacts",
            "--expected-commit", commit
        ], check=True)
        receipt = {"gitCommit": commit, "buildIdentity": manifest["buildIdentity"],
                   "jdkImage": image, "artifacts": verified,
                   "verifierSha256": sha256(Path(__file__).resolve())}
        (output / "verified-artifacts.json").write_text(json.dumps(receipt, sort_keys=True) + "\n", encoding="utf-8")
        print("PASS: host artifact digests/metadata and independent JVM BuildInfo verification", flush=True)


if __name__ == "__main__":
    main()
