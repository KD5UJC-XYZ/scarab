#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Cross-check the generated CDDL codecs against cuddle, upstream's independent CDDL tool (issue K10).

    cddl_crosscheck.py run [--count N] [--seed S] [--size Z] [--sbt CMD] [--image IMG] [--build]
    cddl_crosscheck.py worker PLAN        (inside the cuddle image; not for direct use)

For every target (each `root` of models-spike/src/main/cddl/models.conf, plus the generated types the sampled
tests exercise) it checks, against `cuddle gen` / `cuddle validate-cbor` / `cuddle format` of the vendored CDDL:

  a. terms cuddle generates (canonical, and "twiddled" with indefinite lengths and non-shortest integers) are
     accepted by the generated decoder; canonical ones re-encode byte for byte
  b. terms our CddlSampler generates are valid for the same rule according to cuddle
  c. cuddle's parse of every vendored file has the same rule names and generic arities as our parser
  d. every module (all rules, not only the roots) resolves in cuddle; our generator's unsupported rules are
     exactly the allowlisted ones

Known divergences live in models-spike/cuddle-allowlist.tsv and are asserted: an unlisted divergence fails the run,
and so does a listed one that no longer happens. The cuddle side runs in Docker (docker/cuddle.Dockerfile); the
generated codecs live on the JVM, so the Scala half is a Test main (CuddleCrossCheck) run through sbt. Everything
here is standard library. Cuddle reads one self-contained CDDL file, so for each target this script writes the
transitive closure of its rules from the vendored files, with module-qualified names (`;# import` pragmas resolved
the way models.conf and the generator do); the text of every rule is upstream's, only identifiers are renamed.
"""
import argparse
import collections
import concurrent.futures
import json
import os
import pathlib
import re
import shlex
import subprocess
import sys
import tempfile
import time

REPO = pathlib.Path(__file__).resolve().parent.parent
CDDL_DIR = REPO / "models-spike" / "src" / "main" / "cddl"
ALLOWLIST = REPO / "models-spike" / "cuddle-allowlist.tsv"
SBT_MAIN = "xyz.kd5ujc.scarab.models.CuddleCrossCheck"
DEFAULT_IMAGE = "scarab-cuddle:1.8.1.0"


# --- CDDL text handling (pure; unit-tested in scripts/tests/test_cddl_crosscheck_pure.py) ---------------------------

TOKEN = re.compile(
    r"""
      (?P<comment>;[^\n]*)
    | (?P<string>"(?:\\.|[^"\\])*")
    | (?P<bytes>h?'[^']*')
    | (?P<ctrl>(?<=\s)\.[A-Za-z_]\w*)
    | (?P<num>0x[0-9a-fA-F]+|0b[01]+|\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)
    | (?P<ident>[A-Za-z@_$][A-Za-z0-9_@$]*(?:[.\-][A-Za-z0-9_@$]+)*)
    """,
    re.X,
)
HEAD = re.compile(
    r"^(?P<name>[A-Za-z@_$][A-Za-z0-9_@$]*(?:[.\-][A-Za-z0-9_@$]+)*)\s*(?P<params><[^>]*>)?\s*(?P<assign>//=|/=|=)(?![=>])",
    re.M,
)
PRAGMA = re.compile(r"^;#\s*(?:import|include)\s+(\S+)\s+as\s+(\S+)\s*$", re.M)


def params_of(m):
    return [p.strip() for p in (m.group("params") or "")[1:-1].split(",") if p.strip()]


def chunk_rules(text):
    """Split a CDDL file into [(name, params, assign, text)] at the rules that start in column 0."""
    heads = list(HEAD.finditer(text))
    ends = [h.start() for h in heads[1:]] + [len(text)]
    return [(h.group("name"), params_of(h), h.group("assign"), text[h.start():end]) for h, end in zip(heads, ends)]


class Rule:
    """All the chunks that define one rule of a module, and the file scope they were written in."""

    def __init__(self, module, chunks, aliases):
        self.module, self.chunks, self.aliases = module, chunks, aliases


def load_conf(conf_text):
    """models.conf -> (modules: {name: [files]}, roots: [module:rule])."""
    modules, roots = {}, []
    for raw in conf_text.splitlines():
        words = raw.split("#", 1)[0].split()
        if len(words) >= 4 and words[0] == "module":
            modules[words[1]] = words[3:]
        elif len(words) == 2 and words[0] == "root":
            roots.append(words[1])
    return modules, roots


def module_rules(read, modules, name, skip=()):
    """The rules of one module with the generator's override semantics (Schema.build): within a file `=` starts a
    definition and `/=`, `//=` extend it; a later file replaces an earlier definition, or extends it when it only
    has extensions. `read(file)` returns a file's text; files listed in `skip` are left out."""
    rules = {}
    for f in modules[name]:
        if f in skip:
            continue
        text = read(f)
        aliases = {a: m for m, a in PRAGMA.findall(text)}
        per_file = collections.OrderedDict()
        for c in chunk_rules(text):
            prior = per_file.get(c[0], [])
            per_file[c[0]] = [c] if c[2] == "=" else prior + [c]
        for n, cs in per_file.items():
            extending = all(c[2] != "=" for c in cs)
            merged = (rules[n].chunks if extending and n in rules else []) + cs
            rules[n] = Rule(name, merged, aliases)
    return rules


def mangle(module, name):
    """Module-qualified rule name that cuddle accepts as an identifier."""
    prefix = re.match(r"^\$*", name).group(0)
    return f"{prefix}{re.sub(r'[^A-Za-z0-9]', '_', module)}__{name[len(prefix):]}"


def locate(table, module, aliases, ident):
    """(module, rule) that `ident` names from a file with these aliases, or None (prelude or a type parameter)."""
    if ident in table[module]:
        return module, ident
    head, dot, rest = ident.partition(".")
    if dot and head in aliases:
        target = aliases[head]
        if target in table and rest in table[target]:
            return target, rest
        raise KeyError(f"{module}: `{ident}` names no rule of module {target}")
    return None


def closure(table, roots):
    """CDDL text for the rules reachable from `roots` ([(module, rule)]), renamed to qualified names."""
    seen, order, queue = set(), [], list(roots)
    out = []
    while queue:
        mod, name = queue.pop(0)
        if (mod, name) in seen:
            continue
        seen.add((mod, name))
        rule = table[mod][name]
        for cname, params, assign, text in rule.chunks:
            ps = set(params)

            def sub(m, ps=ps, rule=rule, mod=mod):
                if m.lastgroup != "ident" or m.group() in ps:
                    return m.group()
                hit = locate(table, mod, rule.aliases, m.group())
                if hit is None:
                    return m.group()
                if hit not in seen:
                    queue.append(hit)
                return mangle(*hit)

            out.append(TOKEN.sub(sub, text).rstrip() + "\n")
    return "\n".join(out)


def rules_of_formatted(text):
    """{(name, arity)} from `cuddle format` output (rules start in column 0)."""
    return {(m.group("name"), len(params_of(m))) for m in HEAD.finditer(text)}


def read_allowlist(text):
    """[(kind, subject, reason)] from the tab-separated allowlist (`#` comments, blank lines ignored)."""
    entries = []
    for n, line in enumerate(text.splitlines(), 1):
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) != 3 or not all(p.strip() for p in parts):
            raise SystemExit(f"{ALLOWLIST}:{n}: expected kind<TAB>subject<TAB>reason")
        entries.append(tuple(p.strip() for p in parts))
    return entries


def file_name(target_id):
    return target_id.replace(":", "__") + ".hex"


# --- the cuddle side (runs inside the image) ---------------------------------------------------------------------------


def cuddle(args, timeout=120):
    p = subprocess.run(["cuddle", *args], capture_output=True, text=True, timeout=timeout)
    return p.returncode, p.stdout, p.stderr


def do_job(job):
    op = job["op"]
    try:
        if op == "format":
            rc, out, err = cuddle(["format", job["file"]])
            return {**job, "rc": rc, "out": out if rc == 0 else "", "err": err[-600:]}
        if op == "resolve":
            rc, out, err = cuddle(["validate", job["file"]])
            return {**job, "rc": rc, "err": (out + err)[-600:]}
        if op == "gen":
            terms, errors = [], []
            for i in range(job["count"]):
                args = ["gen", "-f", "hex", "--seed", str(job["seed"] + i), "--size", str(job["size"])]
                if not job["twiddle"]:
                    args.append("--no-twiddle")
                rc, out, err = cuddle([*args, job["rule"], job["file"]], timeout=job.get("timeout", 120))
                line = out.strip().splitlines()[-1].strip() if out.strip() else ""
                if rc == 0 and line:
                    terms.append(line)
                else:
                    errors.append(f"seed {job['seed'] + i}: rc={rc} {err.strip()[-300:]}")
            return {**job, "terms": terms, "errors": errors}
        if op == "validate":
            valid, invalid = 0, []
            for i, hx in enumerate(job["terms"]):
                fd, path = tempfile.mkstemp(suffix=".cbor")
                # cuddle 1.8.1.0 ignores -f for the input file and always reads raw bytes
                with os.fdopen(fd, "wb") as f:
                    f.write(bytes.fromhex(hx))
                rc, out, err = cuddle(["validate-cbor", "-f", "binary", "--no-fold-valid", job["rule"], path, job["file"]])
                os.remove(path)
                if rc == 0:
                    valid += 1
                else:
                    invalid.append({"hex": hx, "trace": (out + err)[-900:]})
            return {**job, "valid": valid, "invalid": invalid}
    except subprocess.TimeoutExpired:
        return {**job, "timeout": True, "terms": [], "errors": ["timeout"], "valid": 0, "invalid": [], "rc": 124}
    raise SystemExit(f"unknown job {op}")


def worker(plan_path):
    plan = json.loads(pathlib.Path(plan_path).read_text())
    jobs = plan["jobs"]
    with concurrent.futures.ThreadPoolExecutor(max_workers=plan["parallel"]) as pool:
        results = list(pool.map(do_job, jobs))
    pathlib.Path(plan["results"]).write_text(json.dumps(results))


# --- the host side ------------------------------------------------------------------------------------------------------


def sh(cmd, **kw):
    print("+", " ".join(shlex.quote(c) for c in cmd), flush=True)
    return subprocess.run(cmd, check=True, cwd=REPO, **kw)


def sbt(args, command):
    sh([*shlex.split(args.sbt), f"modelsSpike/Test/runMain {SBT_MAIN} {command}"])


def build_inputs(work, modules, roots, targets):
    """Write the closure files under work/cddl and return the jobs that need them."""
    read = lambda f: (CDDL_DIR / f).read_text()
    table = {m: module_rules(read, modules, m) for m in modules}
    cdir = work / "cddl"
    cdir.mkdir(parents=True, exist_ok=True)
    files = {}
    for tid in targets:
        mod, rule = tid.split(":", 1)
        path = cdir / (tid.replace(":", "__") + ".cddl")
        path.write_text(closure(table, [(mod, rule)]))
        files[tid] = (mangle(mod, rule), path)
    modfiles = {}
    for m, rules in table.items():
        path = cdir / f"module__{m}.cddl"
        path.write_text(closure(table, [(m, r) for r in rules if not rules[r].chunks[0][1]]))
        modfiles[m] = path
    return table, files, modfiles


def probes(work, modules):
    """Divergences that must keep happening: the bounded_bytes override (a 65-byte string is invalid upstream)."""
    out = []
    long_bytes = "58 41".replace(" ", "") + "ab" * 65  # a 65-byte string
    for m, files in modules.items():
        override = [f for f in files if f.endswith("overrides/bounded-bytes.cddl")]
        if not override:
            continue
        read = lambda f: (CDDL_DIR / f).read_text()
        upstream = module_rules(read, modules, m, skip=override)
        patched = module_rules(read, modules, m)
        for label, tbl in (("upstream", upstream), ("override", patched)):
            path = work / "cddl" / f"probe__{m}__{label}.cddl"
            path.write_text(closure({m: tbl}, [(m, "bounded_bytes")]))
            out.append({"module": m, "label": label, "file": path, "rule": mangle(m, "bounded_bytes"), "hex": long_bytes})
    return out


def to_container(p, work):
    p = pathlib.Path(p)
    try:
        return "/work/" + str(p.relative_to(work))
    except ValueError:
        return "/cddl/" + str(p.relative_to(CDDL_DIR))


def run(args):
    work = (REPO / args.work).resolve()
    if work.exists():
        sh(["rm", "-rf", str(work)])
    work.mkdir(parents=True)
    conf = (CDDL_DIR / "models.conf").read_text()
    modules, roots = load_conf(conf)
    if args.build or subprocess.run(["docker", "image", "inspect", args.image], capture_output=True).returncode:
        sh(["docker", "build", "-f", "docker/cuddle.Dockerfile", "-t", args.image, "docker"])

    sbt(args, f"export {args.work} {args.count} {args.seed}")
    targets = [l.split("\t")[0] for l in (work / "targets.tsv").read_text().splitlines() if l]
    kinds = dict(l.split("\t")[:2] for l in (work / "targets.tsv").read_text().splitlines() if l)
    table, files, modfiles = build_inputs(work, modules, roots, targets)
    probe_list = probes(work, modules)

    jobs = []
    vendored = sorted(p for p in CDDL_DIR.rglob("*.cddl"))
    for p in vendored:
        jobs.append({"op": "format", "file": to_container(p, work), "rel": str(p.relative_to(CDDL_DIR))})
    for m, p in modfiles.items():
        jobs.append({"op": "resolve", "file": to_container(p, work), "module": m})
    for tid, (rule, p) in files.items():
        for mode, twiddle in (("exact", False), ("twiddle", True)):
            jobs.append({"op": "gen", "id": tid, "mode": mode, "twiddle": twiddle, "rule": rule, "file": to_container(p, work),
                         "count": args.count, "seed": args.seed, "size": args.size, "timeout": args.timeout})
        terms = [l for l in (work / "ours" / file_name(tid)).read_text().splitlines() if l]
        jobs.append({"op": "validate", "id": tid, "rule": rule, "file": to_container(p, work), "terms": terms})
        # negative control: `false` is no valid item of any target, so a validator that accepts it checks nothing
        jobs.append({"op": "validate", "id": f"control:{tid}", "rule": rule, "file": to_container(p, work), "terms": ["f4"]})
    for pr in probe_list:
        jobs.append({"op": "validate", "id": f"probe:{pr['module']}:{pr['label']}", "rule": pr["rule"],
                     "file": to_container(pr["file"], work), "terms": [pr["hex"]]})
    plan = {"jobs": jobs, "parallel": args.jobs, "results": "/work/results.json"}
    (work / "plan.json").write_text(json.dumps(plan))
    started = time.time()
    uid = f"{os.getuid()}:{os.getgid()}"
    sh(["docker", "run", "--rm", "--user", uid, "--cpus", str(args.jobs), "-v", f"{work}:/work", "-v", f"{CDDL_DIR}:/cddl:ro",
        "-v", f"{pathlib.Path(__file__).resolve()}:/crosscheck.py:ro", "--entrypoint", "python3", args.image,
        "-I", "/crosscheck.py", "worker", "/work/plan.json"])
    print(f"cuddle side took {time.time() - started:.0f}s", flush=True)
    results = json.loads((work / "results.json").read_text())

    for r in results:
        if r["op"] == "gen":
            d = work / "cuddle" / r["mode"]
            d.mkdir(parents=True, exist_ok=True)
            (d / file_name(r["id"])).write_text("".join(t + "\n" for t in r["terms"]))
    sbt(args, f"check {args.work}")

    failures = judge(work, results, targets, kinds, args)
    sys.exit(1 if failures else 0)


def judge(work, results, targets, kinds, args):
    """Compare everything with the allowlist. Returns the list of failures (empty: the cross-check passed)."""
    allow = read_allowlist(ALLOWLIST.read_text())
    listed = {(k, s): why for k, s, why in allow}
    used = set()
    failures = []
    notes = []

    def diverge(kind, subject, detail):
        """A divergence: fine when allowlisted (and then marked used), a failure otherwise."""
        if (kind, subject) in listed:
            used.add((kind, subject))
            notes.append(f"{kind} {subject}: {detail[:160]}")
        else:
            failures.append(f"{kind} {subject}: {detail}")

    decode = {}
    for line in (work / "decode.tsv").read_text().splitlines():
        f = (line.split("\t") + [""] * 9)[:9]
        decode[(f[0], f[1])] = tuple(int(x) for x in f[2:7]) + (f[7], f[8])
    gens = {(r["id"], r["mode"]): r for r in results if r["op"] == "gen"}
    vals = {r["id"]: r for r in results if r["op"] == "validate"}

    report = {"targets": {}}
    rows = []
    for tid in targets:
        row = {}
        for mode in ("exact", "twiddle"):
            g = gens[(tid, mode)]
            if g["errors"]:
                diverge("cuddle-gen", tid, f"{mode}: {len(g['errors'])} of {g['count']} failed, e.g. {g['errors'][0]}")
            total, accepted, exact, stable, floats, reject, inexact = decode.get((tid, mode), (0, 0, 0, 0, 0, "", ""))
            if total == 0 or total != len(g["terms"]):
                failures.append(f"{tid} {mode}: {total} terms checked, cuddle generated {len(g['terms'])}")
            if floats:
                diverge("decode-float", tid, f"{mode}: {floats} of {total} terms hold a float, which lab.cbor cannot represent")
            if accepted + floats < total:
                diverge("decode-reject", tid, f"{mode}: {accepted + floats}/{total} accepted; first rejected {reject}")
            if stable < accepted:
                diverge("decode-unstable", tid, f"{mode}: {stable}/{accepted} re-encode to a stable value; first {reject}")
            if mode == "exact" and exact < accepted:
                diverge("not-byte-exact", tid, f"{exact}/{accepted} re-encode byte-exactly; first {inexact}")
            row[mode] = {"terms": total, "accepted": accepted, "byte_exact": exact, "stable": stable, "floats": floats}
        if vals[f"control:{tid}"]["valid"]:
            failures.append(f"{tid}: cuddle validated `false` against the rule, so its verdicts mean nothing")
        v = vals[tid]
        total_v = v["valid"] + len(v["invalid"])
        if v["invalid"]:
            diverge("cuddle-rejects", tid, f"{v['valid']}/{total_v} of our terms valid; first {v['invalid'][0]['hex'][:200]} {v['invalid'][0]['trace'][-300:]}")
        rows.append((tid, kinds[tid], row["exact"], row["twiddle"], v["valid"], total_v))
        report["targets"][tid] = {"kind": kinds[tid], "cuddle_to_ours": row, "ours_to_cuddle": [v["valid"], total_v]}

    print(f"\n{'target':<46}{'kind':<8}{'cuddle->ours':>13}{'byte-exact':>11}{'twiddled (non-canonical)':>26}{'ours->cuddle':>14}")
    for tid, kind, e, t, ok, n in rows:
        twiddled = f"{t['accepted']}/{t['terms']} ({t['accepted'] - t['byte_exact']})"
        print(f"{tid:<46}{kind:<8}{e['accepted']:>8}/{e['terms']:<4}{e['byte_exact']:>11}{twiddled:>26}{ok:>9}/{n:<4}")

    # (c) rule names and generic arities, file by file
    ours = collections.defaultdict(set)
    for line in (work / "rules.tsv").read_text().splitlines():
        rel, name, arity = line.split("\t")[:3]
        ours[rel].add((name, int(arity)))
    n_rules = 0
    for r in (x for x in results if x["op"] == "format"):
        rel = r["rel"]
        if r["rc"] != 0:
            diverge("cuddle-parse", rel, r["err"].strip()[-300:])
            continue
        theirs = rules_of_formatted(r["out"])
        n_rules += len(theirs)
        if ours[rel] != theirs:
            diverge("rule-set", rel, f"only ours {sorted(ours[rel] - theirs)[:6]}, only cuddle {sorted(theirs - ours[rel])[:6]}")
    print(f"\nrule sets: {len(ours)} files, {n_rules} (name, arity) pairs agree with cuddle's parse")
    report["rule_files"], report["rules"] = len(ours), n_rules

    # whole modules, not only the roots, resolve in cuddle
    resolved = [r for r in results if r["op"] == "resolve"]
    for r in resolved:
        if r["rc"] != 0:
            diverge("cuddle-resolve", r["module"], r["err"].strip()[-300:])
    print(f"modules cuddle resolves completely: {sum(1 for r in resolved if r['rc'] == 0)} of {len(resolved)}")

    # rules our generator cannot produce: exactly the allowlisted set
    have = {tuple(l.split("\t")[:2]): l.split("\t")[2] for l in (work / "unsupported.tsv").read_text().splitlines() if l}
    for (m, n), why in sorted(have.items()):
        diverge("unsupported", f"{m}:{n}", why)
    print(f"rules our generator cannot produce on their own: {len(have)}")
    report["unsupported"] = len(have)

    # the bounded_bytes override: upstream rejects a 65-byte string, the overridden rule must accept it
    for r in results:
        if r["op"] == "validate" and r["id"].startswith("probe:"):
            _, module, label = r["id"].split(":")
            accepted = r["valid"] == 1
            if label == "upstream":
                if accepted:
                    failures.append(f"override {module}:bounded_bytes: upstream now accepts a 65-byte string, drop the override")
                else:
                    diverge("override", f"{module}:bounded_bytes", "upstream rejects a 65-byte string (the chain uses chunked strings)")
            elif not accepted:
                failures.append(f"override {module}:bounded_bytes: the overridden rule does not accept a 65-byte string")

    for (k, s), why in listed.items():
        if (k, s) not in used:
            failures.append(f"stale allowlist entry `{k} {s}` ({why}): the divergence no longer happens")

    print(f"\nallowlisted divergences seen ({len(notes)}):")
    for n in notes:
        print("  ", n)
    (work / "report.json").write_text(json.dumps(report, indent=1))
    print()
    if failures:
        print(f"{len(failures)} cross-check failure(s):")
        for f in failures:
            print("  FAIL", f)
    else:
        print(f"cross-check passed: every divergence is allowlisted and asserted ({len(used)} entries)")
    return failures


def main():
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    sub = p.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("run", help="run the whole cross-check")
    r.add_argument("--count", type=int, default=40, help="terms per target and direction (default 40)")
    r.add_argument("--seed", type=int, default=1)
    r.add_argument("--size", type=int, default=12, help="cuddle generator size")
    r.add_argument("--timeout", type=int, default=120, help="seconds per cuddle gen call")
    r.add_argument("--jobs", type=int, default=4, help="parallel cuddle processes")
    r.add_argument("--image", default=DEFAULT_IMAGE)
    r.add_argument("--build", action="store_true", help="build the cuddle image even if it exists")
    r.add_argument("--sbt", default="bash scripts/sbtw", help="command that runs sbt with the commands as arguments")
    r.add_argument("--work", default="target/crosscheck", help="working directory, relative to the repository")
    r.set_defaults(fn=run)
    w = sub.add_parser("worker", help="(inside the image)")
    w.add_argument("plan")
    w.set_defaults(fn=lambda a: worker(a.plan))
    args = p.parse_args()
    args.fn(args)


if __name__ == "__main__":
    main()
