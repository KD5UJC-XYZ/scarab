# SPDX-License-Identifier: Apache-2.0
"""The Scala node's Prometheus endpoint: scraping it from a scenario and checking it against the node's log.

The text format is read here the way a scraper reads it (strictly: a line the grammar of the exposition
format 0.0.4 does not allow is an error). The checks are about the node, not the format: the series that
the documentation (docs/running.md, "Metrics") promises are there, and the values agree with the JSON log
of the same run.
"""
import re
import time
from .util import docker

METRICS_PORT = 12798

_NAME = r"[a-zA-Z_:][a-zA-Z0-9_:]*"
_SAMPLE = re.compile(rf"^({_NAME})(?:\{{(.*)\}})? (\S+)$")
_LABEL = re.compile(r'([a-zA-Z_][a-zA-Z0-9_]*)="((?:[^"\\]|\\.)*)"(?:,|$)')
_ESCAPE = re.compile(r"\\(.)")

# What a follower that follows one peer, applies blocks and does not forge must export.
REQUIRED = [
    "cardano_node_metrics_blockNum_int", "cardano_node_metrics_slotNum_int",
    "cardano_node_metrics_epoch_int", "cardano_node_metrics_slotInEpoch_int",
    "cardano_node_metrics_density_real", "cardano_node_metrics_txsInMempool_int",
    "cardano_node_metrics_mempoolBytes_int", "cardano_node_metrics_connectionManager_outboundConns_int",
    "cardano_node_metrics_forks_counter", "cardano_node_metrics_node_start_time_int",
    "scarab_build_info", "scarab_era", "scarab_blocks_applied_total", "scarab_block_apply_seconds_count",
    "scarab_peer_tip_slot", "scarab_peer_tip_block", "scarab_volatile_blocks", "scarab_rollbacks_total",
    "scarab_chainsync_rounds_total", "scarab_scheduling_timer_samples_total",
    "scarab_scheduling_timer_lateness_max_seconds", "scarab_cpu_steps_total",
    "scarab_cpu_longest_step_seconds", "scarab_connections",
    "jvm_memory_bytes_used", "jvm_threads_current", "process_cpu_seconds_total", "process_start_time_seconds",
]


def _unescape(text):
    return _ESCAPE.sub(lambda m: {"n": "\n", "\\": "\\", '"': '"'}.get(m.group(1), m.group(0)), text)


def _number(text):
    if text in ("NaN", "+Inf", "-Inf"):
        return float(text.replace("+Inf", "inf").replace("-Inf", "-inf"))
    return float(text)


def parse(text):
    """{(name, ((label, value), ...)): float} of a text exposition; ValueError on a line the format forbids,
    on a sample without HELP and TYPE of its family, and on a repeated sample."""
    helps, types, samples = set(), {}, {}
    for number, line in enumerate(text.split("\n")[:-1] if text.endswith("\n") else text.split("\n"), 1):
        if line.startswith("# HELP "):
            helps.add(line[7:].split(" ", 1)[0])
        elif line.startswith("# TYPE "):
            name, kind = line[7:].split(" ")
            if name not in helps or name in types:
                raise ValueError(f"line {number}: TYPE of {name} without HELP, or repeated")
            types[name] = kind
        elif line.startswith("#") or not line:
            raise ValueError(f"line {number}: unexpected {line!r}")
        else:
            m = _SAMPLE.match(line)
            if not m:
                raise ValueError(f"line {number}: not a sample: {line!r}")
            name, raw, value = m.groups()
            labels, rest = [], raw or ""
            while rest:
                lm = _LABEL.match(rest)
                if not lm:
                    raise ValueError(f"line {number}: bad labels in {line!r}")
                labels.append((lm.group(1), _unescape(lm.group(2))))
                rest = rest[lm.end():]
            family = next((c for c in (name, re.sub(r"_(bucket|sum|count)$", "", name)) if c in types), None)
            if family is None:
                raise ValueError(f"line {number}: {name} has no TYPE")
            key = (name, tuple(labels))
            if key in samples:
                raise ValueError(f"line {number}: {name}{labels} twice")
            samples[key] = _number(value)
    return samples


def value(samples, name, **labels):
    """The value of the series `name` with exactly `labels`, or None."""
    return samples.get((name, tuple(labels.items())))


def total(samples, name):
    """The sum of a metric over its label sets (0 if it has none)."""
    return sum(v for (n, _), v in samples.items() if n == name)


def scrape(name, port, path="/metrics"):
    """The text a scenario's Scala node answers at `path` of `port`; none when it does not answer."""
    url = f"http://127.0.0.1:{port}{path}"
    script = ("import urllib.request,sys;r=urllib.request.urlopen('%s',timeout=20);"
              "sys.stdout.write(r.headers.get('Content-Type','')+'\\n'+r.read().decode())" % url)
    r = docker("exec", f"{name}-scala", "python3", "-c", script, check=False, timeout=60)
    if r.returncode != 0 or not r.stdout:
        return None
    content_type, _, body = r.stdout.partition("\n")
    return content_type, body


def log_tip(events):
    """(block number, epoch, number of `extended` events) of the last applied block of a follower log."""
    extended = [e for e in events if e.get("event") == "extended"]
    if not extended:
        return 0, 0, 0
    last = extended[-1]
    return last["block"]["blockNo"], last["epoch"], len(extended)


def check(samples, before, after):
    """What is wrong with a scrape, as a list of texts. `before` and `after` are `log_tip` of the follower log
    read before and after the scrape, so a value that follows the chain lies between them."""
    failures = [f"missing series {name}" for name in REQUIRED if not any(n == name for n, _ in samples)]
    block, epoch = value(samples, "cardano_node_metrics_blockNum_int"), value(samples, "cardano_node_metrics_epoch_int")
    if block is not None and not before[0] <= block <= after[0]:
        failures.append(f"blockNum {block} is not between the last extended block before ({before[0]}) and after ({after[0]})")
    if epoch is not None and not before[1] <= epoch <= after[1]:
        failures.append(f"epoch {epoch} is not between {before[1]} and {after[1]}")
    applied = total(samples, "scarab_blocks_applied_total")
    if not before[2] <= applied <= after[2]:
        failures.append(f"scarab_blocks_applied_total {applied} is not between the extended events before ({before[2]}) and after ({after[2]})")
    timed = value(samples, "scarab_block_apply_seconds_count")
    if timed is not None and timed < applied:
        failures.append(f"{applied} blocks applied but only {timed} timed")
    if total(samples, "scarab_validation_errors_total"):
        failures.append("scarab_validation_errors_total is not 0")
    if value(samples, "scarab_era", era="Conway") != 1.0 and not before[2] == 0:
        failures.append("scarab_era{era=Conway} is not 1")
    return failures


def scrape_and_check(name, port, read_events, wait=20):
    """Scrape `port`, bracketed by reads of the follower log (`read_events()` gives its events). The log reaches
    the file a moment after the node writes it, so a scrape ahead of the log is read again for a few seconds.
    Returns (failures, samples, content type)."""
    deadline = time.monotonic() + wait
    while True:
        before = log_tip(read_events())
        got = scrape(name, port)
        if got is None:
            return [f"no answer at port {port}"], {}, None
        content_type, text = got
        after = log_tip(read_events())
        try:
            samples = parse(text)
        except ValueError as e:
            return [f"not well formed: {e}"], {}, content_type
        failures = check(samples, before, after)
        if content_type != "text/plain; version=0.0.4; charset=utf-8":
            failures.append(f"content type {content_type!r}")
        if not failures or time.monotonic() > deadline:
            return failures, samples, content_type
        time.sleep(1)
