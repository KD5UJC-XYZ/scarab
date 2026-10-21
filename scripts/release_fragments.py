#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Prepare reviewed changelog changes from immutable Git inputs; never publish a release."""
import argparse
from contextlib import contextmanager
from datetime import date
import base64
import fcntl
import hashlib
from importlib.metadata import PackageNotFoundError, version as package_version
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

from release_identity import VERSION, channel_for_version


class ReleaseError(ValueError):
    """An invalid selection, changed input, or incomplete preparation transaction."""


def require(condition, message):
    if not condition:
        raise ReleaseError(message)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def json_bytes(value):
    return (json.dumps(value, indent=2, sort_keys=True) + "\n").encode()


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, f"duplicate JSON key: {key}")
        result[key] = value
    return result


def load_json(path):
    require(path.stat().st_size <= 32 * 1024 * 1024, f"oversized JSON: {path}")
    return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique_object)


def git(repo, *args):
    process = subprocess.run(["git", "-C", str(repo), *args], capture_output=True)
    require(process.returncode == 0, f"git {' '.join(args)} failed: {process.stderr.decode().strip()}")
    return process.stdout


def commit(repo, value):
    require(isinstance(value, str) and re.fullmatch(r"[0-9a-f]{40}", value), "source and baseline require full lowercase commit SHA")
    require(git(repo, "rev-parse", f"{value}^{{commit}}").decode().strip() == value, "not a commit")
    return value


def ancestor(repo, older, newer):
    process = subprocess.run(["git", "-C", str(repo), "merge-base", "--is-ancestor", older, newer])
    require(process.returncode == 0, f"{older} must be an ancestor of {newer}")


def blob(repo, source, path):
    return git(repo, "show", f"{source}:{path}")


def tree(repo, source, directory):
    entries = git(repo, "ls-tree", "-rz", source, "--", directory).split(b"\0")
    result = []
    for item in filter(None, entries):
        meta, name = item.split(b"\t", 1)
        mode, kind, _ = meta.split()
        path = name.decode("utf-8")
        require(mode == b"100644" and kind == b"blob", f"not a regular tracked input: {path}")
        result.append(path)
    return result


def fragment_path(path):
    require(isinstance(path, str) and re.fullmatch(r"changes/[A-Za-z0-9][A-Za-z0-9_.-]*\.md", path), f"invalid fragment path: {path}")
    require(eligible(path), f"excluded fragment: {path}")
    return path


def eligible(path):
    name = Path(path).name
    return Path(path).parent == Path("changes") and name.endswith(".md") and name not in (
        "README.md", "TEMPLATE.md") and not name.endswith(".template.md")


def order(path):
    issue = re.match(r"changes/(\d+)", path)
    return (0, int(issue[1]), path) if issue else (1, 0, path)


def text(data, path):
    require(len(data) <= 2 * 1024 * 1024, f"oversized input: {path}")
    value = data.decode("utf-8")
    require("\r" not in value and "\0" not in value, f"{path}:1: require UTF-8 text with LF newlines")
    return value


def validate_fragment(data, path):
    lines = text(data, path).splitlines()
    require(bool(lines), f"{path}:1: empty fragment")
    section = None
    bullet = False
    fence = None
    for number, line in enumerate(lines, 1):
        marker = re.match(r"^\s*(`{3,}|~{3,})", line)
        if marker:
            if fence is None:
                fence = marker[1][0]
            elif marker[1][0] == fence:
                fence = None
        if fence is not None or marker:
            require(section is not None, f"{path}:{number}: content before first heading")
            continue
        if re.match(r"^#{4,6} ", line):
            require(section is not None, f"{path}:{number}: nested heading before section")
        elif line.startswith("#"):
            require(re.fullmatch(r"### (Added|Changed|Fixed|Removed)(?: \([^\n]+\))?", line),
                    f"{path}:{number}: invalid topic heading")
            require(section is None or bullet, f"{path}:{section}: section needs a bullet")
            section, bullet = number, False
        elif line.strip():
            require(section is not None, f"{path}:{number}: content before first heading")
            bullet = bullet or bool(re.match(r"^[-*] \S", line))
    require(fence is None, f"{path}:{len(lines)}: unclosed code fence")
    require(section is not None and bullet, f"{path}:{section or 1}: section needs a bullet")


def split_changelog(data):
    value = text(data, "CHANGELOG.md")
    matches = list(re.finditer(r"(?m)^## \[Unreleased\]\s*$", value))
    require(len(matches) == 1, "CHANGELOG.md: require exactly one Unreleased heading")
    start = matches[0].end()
    end = re.search(r"(?m)^## ", value[start:])
    require(end is not None, "CHANGELOG.md: require retained release history")
    end = start + end.start()
    section = value[start:end]
    first = re.search(r"(?m)^### ", section)
    require(first is not None or not section.strip() or not re.search(r"(?m)^[-*] ", section),
            "CHANGELOG.md: legacy entries require headings")
    intro = section if first is None else section[:first.start()]
    legacy = "" if first is None else section[first.start():].strip("\n")
    return value[:start] + intro.rstrip("\n") + "\n\n", legacy, value[end:]


def collect(blocks):
    # Stock Scriv parses/merges topic headings. Keep each validated original as one block instead.
    try:
        installed = package_version("scriv")
    except PackageNotFoundError as error:
        raise ReleaseError("install pinned scripts/release/requirements.txt in a venv") from error
    require(installed == "1.8.0", "install pinned scripts/release/requirements.txt in a venv")
    from scriv.changelog import Fragment
    from scriv.config import Config
    from scriv.scriv import Scriv

    class Topics(Scriv):
        def sections_from_fragment(self, fragment):
            return {None: [fragment.content.strip("\n")]}

    collector = Topics(config=Config(format="md", categories=[], md_header_level="2"))
    fragments = [Fragment(path=Path(str(index) + ".md"), content=body) for index, body in enumerate(blocks)]
    return collector.changelog().entry_text(collector.combine_fragments(fragments)).strip("\n") + "\n"


class History:
    """Per-operation immutable Git history memo, primed iteratively from oldest dependencies."""
    def __init__(self, repo):
        self.repo = repo
        self.nodes = {}
        self.done = set()
        self.valid = set()
        self.validations = 0

    def records(self, source):
        if source not in self.nodes:
            self.nodes[source] = [(path, json.loads(blob(self.repo, source, path),
                                  object_pairs_hook=unique_object))
                                 for path in tree(self.repo, source, ".release/changelog")
                                 if path.endswith(".json")]
        return self.nodes[source]

    def prime(self, *sources):
        work = [(source, False) for source in sources]
        while work:
            source, complete = work.pop()
            if source in self.done:
                continue
            commit(self.repo, source)
            records = self.records(source)
            if complete:
                for _, receipt in records:
                    validate_receipt(self.repo, receipt, self)
                self.done.add(source)
            else:
                work.append((source, True))
                for _, receipt in records:
                    require(receipt.get("source") != source, "receipt must reference an earlier source commit")
                    commit(self.repo, receipt["baseline"])
                    commit(self.repo, receipt["source"])
                    ancestor(self.repo, receipt["baseline"], receipt["source"])
                    ancestor(self.repo, receipt["source"], source)
                    work.extend((dependency, False) for dependency in (receipt["source"], receipt["baseline"])
                                if dependency not in self.done)


def pending(repo, source, baseline, context):
    published = set(tree(repo, baseline, ".release/changelog"))
    found = []
    for path, receipt in context.records(source):
        if path.endswith(".json"):
            require(receipt.get("schemaVersion") == 1 and receipt.get("state") in
                    ("pending-publication", "restored"), f"unsupported receipt: {path}")
            require(receipt["source"] != source, "receipt must reference an earlier source commit")
            ancestor(repo, receipt["source"], source)
            validate_receipt(repo, receipt, context)
            notes_path = f".release/changelog/{receipt['version']}.md"
            require(digest(blob(repo, source, notes_path)) == receipt["notesSha256"], "retained notes changed")
            if path in published:
                require(blob(repo, baseline, path) == blob(repo, source, path), "published-range receipt changed")
            elif receipt["state"] == "pending-publication":
                found.append(receipt)
    require(len(found) <= 1, "more than one pending stable consolidation")
    return found[0] if found else None


def build(repo, source, baseline, release_version, release_date, selected=None, _context=None):
    commit(repo, source)
    commit(repo, baseline)
    ancestor(repo, baseline, source)
    require(VERSION.fullmatch(release_version),
            "require canonical MAJOR.MINOR.PATCH with optional alpha.N, beta.N or rc.N suffix")
    require(date.fromisoformat(release_date).isoformat() == release_date, "require explicit ISO release date")
    channel = channel_for_version(release_version)
    context = _context or History(repo)
    context.prime(source, baseline)
    previous = pending(repo, source, baseline, context)
    require(channel != "prod" or previous is None, "stable preparation already pending; publish or review restoration first")
    receipts = tree(repo, source, ".release/changelog")
    require(f".release/changelog/{release_version}.json" not in receipts, "version already reserved")
    available = [p for p in tree(repo, source, "changes") if eligible(p)]
    consumed = set()
    for path, old in context.records(baseline):
        if path.endswith(".json"):
            validate_receipt(repo, old, context)
            if old["state"] == "pending-publication":
                consumed.update((item["path"], item["sha256"]) for item in old["inputs"])
    available = [p for p in available if (p, digest(blob(repo, source, p))) not in consumed]
    chosen = sorted(available if selected is None else selected, key=order)
    require(len(chosen) == len(set(chosen)), "duplicate selected path")
    require(all(p in available for p in chosen), "selected input is not an eligible tracked fragment")
    inputs = []
    if previous:
        require(previous["baseline"] == baseline, "pending receipt uses a different stable baseline")
        ancestor(repo, previous["source"], source)
        inputs.extend(previous["inputs"])
    inputs.extend({"path": fragment_path(p), "source": source, "sha256": digest(blob(repo, source, p))}
                  for p in chosen)
    unique = {}
    for item in inputs:
        fragment_path(item["path"])
        commit(repo, item["source"])
        data = blob(repo, item["source"], item["path"])
        require(digest(data) == item["sha256"], f"frozen input hash changed: {item['path']}")
        validate_fragment(data, item["path"])
        unique.setdefault((item["path"], item["sha256"]), item)
    inputs = sorted(unique.values(), key=lambda item: order(item["path"]))
    original = blob(repo, source, "CHANGELOG.md")
    prefix, legacy, history = split_changelog(original)
    legacy_source = source
    if previous:
        require(not legacy, "new legacy text during pending consolidation requires review")
        legacy_source = previous["legacySource"]
        _, legacy, _ = split_changelog(blob(repo, legacy_source, "CHANGELOG.md"))
        require(digest(legacy.encode()) == previous["legacySha256"], "pending legacy identity changed")
    require(inputs or legacy, "no unconsumed entries")
    require(not re.search(rf"(?m)^## \[{re.escape(release_version)}\]", history), "version already in changelog")
    blocks = ([legacy] if legacy else []) + [text(blob(repo, i["source"], i["path"]), i["path"]) for i in inputs]
    body = collect(blocks)
    notes = f"## [{release_version}] - {release_date}\n\n{body}"
    candidate = prefix + notes + "\n" + history
    plan = {"schemaVersion": 1, "collector": "scriv-1.8.0/topics-v1", "source": source,
            "baseline": baseline, "version": release_version, "date": release_date,
            "channel": channel, "selected": chosen, "inputs": inputs,
            "legacySource": legacy_source, "legacySha256": digest(legacy.encode()),
            "originalChangelogSha256": digest(original), "notesSha256": digest(notes.encode()),
            "changelogSha256": digest(candidate.encode())}
    return plan, notes.encode(), candidate.encode()


def prepare(repo, output, **kwargs):
    plan, notes, changelog = build(repo, **kwargs)
    output.mkdir(parents=True, exist_ok=False)
    (output / "plan.json").write_bytes(json_bytes(plan))
    (output / "notes.md").write_bytes(notes)
    (output / "CHANGELOG.md").write_bytes(changelog)
    return plan


def trial(repo, source, output):
    """Reproduce the stock-versus-adapter trial in an exclusive scratch directory."""
    commit(repo, source)
    paths = sorted([p for p in tree(repo, source, "changes") if eligible(p)], key=order)
    blocks = [text(blob(repo, source, p), p).strip("\n") for p in paths]
    adapted = collect(blocks)
    from scriv.changelog import Fragment
    from scriv.config import Config
    from scriv.scriv import Scriv
    output.mkdir(parents=True, exist_ok=False)
    fragments = []
    for number, block in enumerate(blocks):
        path = output / f"{number:05}.md"
        path.write_text(block, encoding="utf-8")
        fragments.append(Fragment(path=path))
    stock = Scriv(config=Config(format="md", categories=[], md_header_level="2"))
    original = stock.changelog().entry_text(stock.combine_fragments(fragments))
    require(adapted == "\n\n".join(blocks) + "\n", "lossless adapter trial failed")
    report = {"source": source, "scrivVersion": "1.8.0", "fragmentCount": len(paths),
              "stockContiguousBlocks": sum(block in original for block in blocks),
              "adapterLossless": True, "stockSha256": digest(original.encode()),
              "adapterSha256": digest(adapted.encode()),
              "inputs": {p: digest(blob(repo, source, p)) for p in paths}}
    (output / "stock.md").write_text(original, encoding="utf-8")
    (output / "adapted.md").write_text(adapted, encoding="utf-8")
    (output / "trial.json").write_bytes(json_bytes(report))
    return report


def safe_file(repo, path):
    require(not Path(path).is_absolute() and ".." not in Path(path).parts, f"unsafe output path: {path}")
    target = repo / path
    require(not any(p.is_symlink() for p in [target, *target.parents] if p != repo.parent),
            f"symlink output path: {path}")
    return target


def current(repo, path):
    target = safe_file(repo, path)
    require(not target.exists() or target.is_file(), f"not a regular file: {path}")
    return target.read_bytes() if target.exists() else None


def identity(value):
    return None if value is None else digest(value)


def atomic(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, name = tempfile.mkstemp(prefix=".changelog-", dir=path.parent)
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(name, path)
    finally:
        Path(name).unlink(missing_ok=True)


def git_path(repo, name):
    return Path(git(repo, "rev-parse", "--path-format=absolute", "--git-path", name).decode().strip())


@contextmanager
def locked(repo):
    with git_path(repo, "scarab-changelog.lock").open("a") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as error:
            raise ReleaseError("another changelog transaction is running") from error
        yield


def operation(repo, path, after):
    before = current(repo, path)
    return {"path": path, "before": identity(before), "after": identity(after),
            "payload": None if after is None else base64.b64encode(after).decode()}


def finish(repo, journal):
    # Check every remaining path before changing any: an interrupted transaction can only roll forward.
    require(set(journal) == {"schemaVersion", "sourceHead", "operations"} and journal["schemaVersion"] == 1,
            "unsupported transaction schema")
    commit(repo, journal["sourceHead"])
    require(git(repo, "rev-parse", "HEAD").decode().strip() == journal["sourceHead"],
            "transaction source HEAD changed; manual review required")
    require(0 < len(journal["operations"]) <= 10000, "invalid transaction size")
    paths = [op["path"] for op in journal["operations"]]
    require(len(set(paths)) == len(paths), "duplicate transaction paths")
    for op in journal["operations"]:
        require(set(op) == {"path", "before", "after", "payload"}, "unsupported transaction operation")
        if op["path"].startswith("changes/"):
            fragment_path(op["path"])
        allowed = op["path"] == "CHANGELOG.md" or bool(re.fullmatch(
            r"(?:changes/[A-Za-z0-9][A-Za-z0-9_.-]*\.md|\.release/changelog/[0-9]+\.[0-9]+\.[0-9]+\.(?:json|md))", op["path"]))
        require(allowed and op["path"] != "changes/README.md", "invalid transaction output path")
        require(all(value is None or re.fullmatch(r"[0-9a-f]{64}", value)
                    for value in (op["before"], op["after"])), "invalid transaction identity")
        require(identity(current(repo, op["path"])) in (op["before"], op["after"]),
                f"transaction input changed: {op['path']}")
        payload = None if op["payload"] is None else base64.b64decode(op["payload"], validate=True)
        require(identity(payload) == op["after"], "transaction payload hash mismatch")
    for op in journal["operations"]:
        observed = identity(current(repo, op["path"]))
        require(observed in (op["before"], op["after"]), f"transaction input changed: {op['path']}")
        if observed != op["after"]:
            target = safe_file(repo, op["path"])
            if op["payload"] is None:
                target.unlink()
            else:
                atomic(target, base64.b64decode(op["payload"], validate=True))
    git_path(repo, "scarab-changelog-transaction.json").unlink()


def transact(repo, operations):
    journal_path = git_path(repo, "scarab-changelog-transaction.json")
    require(not journal_path.exists(), "incomplete transaction: run recover before another preparation")
    journal = {"schemaVersion": 1, "sourceHead": git(repo, "rev-parse", "HEAD").decode().strip(), "operations": operations}
    atomic(journal_path, json_bytes(journal))
    finish(repo, journal)


def validate_receipt(repo, receipt, context=None):
    require(receipt.get("state") in ("pending-publication", "restored"), "unsupported receipt state")
    context = context or History(repo)
    context.prime(receipt["source"], receipt["baseline"])
    key = digest(json_bytes(receipt))
    if key in context.valid:
        return
    context.validations += 1
    expected, _, _ = build(repo, receipt["source"], receipt["baseline"], receipt["version"],
                           receipt["date"], receipt["selected"], _context=context)
    require(receipt == dict(expected, state=receipt["state"], planSha256=digest(json_bytes(expected))),
            "receipt does not match frozen selection")
    context.valid.add(key)


def verify_applied(repo, receipt):
    ancestor(repo, receipt["source"], git(repo, "rev-parse", "HEAD").decode().strip())
    validate_receipt(repo, receipt)
    require(receipt["state"] == "pending-publication", "receipt is not pending publication")
    for path, expected in (("CHANGELOG.md", receipt["changelogSha256"]),
                           (f".release/changelog/{receipt['version']}.md", receipt["notesSha256"])):
        require(identity(current(repo, path)) == expected, f"prepared output changed: {path}")
    for item in receipt["inputs"]:
        require(current(repo, item["path"]) is None, f"consumed fragment reappeared: {item['path']}")


def apply(repo, plan_path):
    plan = load_json(plan_path)
    require(plan.get("channel") == "prod", "prerelease preview never consumes fragments")
    with locked(repo):
        require(not git_path(repo, "scarab-changelog-transaction.json").exists(), "incomplete transaction: run recover")
        receipt_path = f".release/changelog/{plan['version']}.json"
        existing = current(repo, receipt_path)
        if existing is not None:
            receipt = json.loads(existing, object_pairs_hook=unique_object)
            require(receipt.get("planSha256") == digest(json_bytes(plan)), "different selection reserved this version")
            verify_applied(repo, receipt)
            return "already prepared; publication remains pending"
        for path in (repo / ".release/changelog").glob("*.json"):
            other = load_json(safe_file(repo, str(path.relative_to(repo))))
            relative = str(path.relative_to(repo))
            published = relative in tree(repo, plan["baseline"], ".release/changelog")
            require(other.get("state") == "restored" or (published and
                    blob(repo, plan["baseline"], relative) == path.read_bytes()),
                    "another stable preparation remains pending")
        ancestor(repo, plan["source"], git(repo, "rev-parse", "HEAD").decode().strip())
        expected, notes, changelog = build(repo, plan["source"], plan["baseline"], plan["version"],
                                           plan["date"], plan["selected"])
        require(expected == plan, "plan does not match frozen Git inputs")
        require((plan_path.parent / "notes.md").read_bytes() == notes and
                (plan_path.parent / "CHANGELOG.md").read_bytes() == changelog, "reviewed output changed; regenerate")
        require(identity(current(repo, "CHANGELOG.md")) == plan["originalChangelogSha256"], "changelog changed since selection")
        for item in plan["inputs"]:
            require(identity(current(repo, item["path"])) == item["sha256"], f"selected input changed or missing: {item['path']}")
        receipt = dict(plan, state="pending-publication", planSha256=digest(json_bytes(plan)))
        operations = [operation(repo, "CHANGELOG.md", changelog)]
        operations += [operation(repo, item["path"], None) for item in plan["inputs"]]
        operations += [operation(repo, f".release/changelog/{plan['version']}.md", notes),
                       operation(repo, receipt_path, json_bytes(receipt))]
        transact(repo, operations)
        return "prepared for review; publication remains pending"


def recover(repo):
    with locked(repo):
        path = git_path(repo, "scarab-changelog-transaction.json")
        require(path.exists(), "no incomplete transaction")
        journal = load_json(path)
        require(journal.get("schemaVersion") == 1, "unsupported transaction")
        finish(repo, journal)


def restore(repo, receipt_path, baseline):
    commit(repo, baseline)
    ancestor(repo, baseline, git(repo, "rev-parse", "HEAD").decode().strip())
    require(receipt_path not in tree(repo, baseline, ".release/changelog"),
            "cannot abandon preparation contained in the declared published baseline")
    with locked(repo):
        receipt = load_json(safe_file(repo, receipt_path))
        require(receipt_path == f".release/changelog/{receipt['version']}.json", "invalid receipt path")
        verify_applied(repo, receipt)
        operations = [operation(repo, "CHANGELOG.md", blob(repo, receipt["source"], "CHANGELOG.md"))]
        operations += [operation(repo, item["path"], blob(repo, item["source"], item["path"])) for item in receipt["inputs"]]
        operations.append(operation(repo, receipt_path, json_bytes(dict(receipt, state="restored"))))
        transact(repo, operations)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    commands = parser.add_subparsers(dest="command", required=True)
    preview = commands.add_parser("preview", help="freeze release notes for any channel without touching tracked inputs")
    for name in ("source", "baseline", "version", "date"):
        preview.add_argument("--" + name, required=True)
    preview.add_argument("--select", action="append", dest="selected")
    preview.add_argument("--output", required=True, type=Path)
    experiment = commands.add_parser("trial", help="compare pinned Scriv and lossless adapter on immutable fragments")
    experiment.add_argument("--source", required=True)
    experiment.add_argument("--output", required=True, type=Path)
    consume = commands.add_parser("apply", help="apply reviewed stable outputs; no publication")
    consume.add_argument("plan", type=Path)
    commands.add_parser("recover", help="roll an interrupted local transaction forward")
    undo = commands.add_parser("restore", help="prepare reviewed abandonment/restoration; never reuse its version")
    undo.add_argument("receipt")
    undo.add_argument("--baseline", required=True)
    check = commands.add_parser("check", help="verify an applied pending preparation")
    check.add_argument("receipt")
    args = parser.parse_args()
    repo = args.repo.resolve()
    try:
        if args.command == "preview":
            prepare(repo, args.output, source=args.source, baseline=args.baseline,
                    release_version=args.version, release_date=args.date, selected=args.selected)
        elif args.command == "trial":
            print(json.dumps(trial(repo, args.source, args.output), indent=2))
        elif args.command == "apply":
            print(apply(repo, args.plan))
        elif args.command == "recover":
            recover(repo)
        elif args.command == "restore":
            restore(repo, args.receipt, args.baseline)
        else:
            verify_applied(repo, load_json(safe_file(repo, args.receipt)))
    except (ReleaseError, OSError, ValueError, KeyError) as error:
        parser.exit(2, f"changelog preparation refused: {error}\n")


if __name__ == "__main__":
    main()
