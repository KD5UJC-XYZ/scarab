# SPDX-License-Identifier: Apache-2.0
"""The trace options of the Haskell pools in the pool scenarios, and what the harness reads from their logs.

`cardano-testnet create-env` writes `"TraceOptions": {}`, so a node runs its built-in defaults
(`defaultCardanoConfig`, cardano-node 11.1.3 `cardano-node/src/Cardano/Node/Tracing/DefaultTraceConfig.hs`). Those
silence `ChainDB.AddBlockEvent.AddBlockValidation`, and with it `InvalidBlock`: a pool that refuses a block traces
nothing visible when the block was the tip of the peer's chain, as the ChainSync client's invalid-block rejector
skips a peer's tip under diffusion pipelining and the BlockFetch punishment spares the first invalid tentative block
(ouroboros-consensus 82ecba32, `MiniProtocol/ChainSync/Client.hs` `invalidBlockRejector`,
`Storage/ChainDB/API/Types/InvalidBlockPunishment.hs` `mkForDiffusionPipelining`). A non-empty `TraceOptions` in the
file replaces the defaults instead of adding to them (trace-dispatcher 2.13 `ConfigurationParser.hs`,
`mergeWithDefault`), so the pools get the whole default set again and one entry more, `InvalidBlock` at the severity
its parent `ChainDB` has. With the defaults written out, `cardano-node trace-documentation` of 11.1.3 gives the same
document as for the empty options (the effective options it prints are these, key for key); with the entry added,
only `InvalidBlock`'s filter changes. Options are looked up per kind, the most specific namespace first
(`Configuration.hs` `getOption`), which `effective` follows."""
import json
import re

REFERENCE_VERSION = "11.1.3"

SILENCE = {"severity": "Silence"}

# `defaultCardanoConfig` of cardano-node 11.1.3 in the configuration file's form ("" is the namespace root).
DEFAULT_TRACE_OPTIONS = {
    "": {"severity": "Notice", "detail": "DNormal", "backends": ["Stdout MachineFormat", "EKGBackend"]},
    "BlockFetch.Decision": SILENCE,
    "ChainDB": {"severity": "Info"},
    "ChainDB.AddBlockEvent.AddBlockValidation": SILENCE,
    "ChainSync.Client": {"severity": "Warning"},
    "Net.ConnectionManager.Remote": {"severity": "Info"},
    "Startup.DiffusionInit": {"severity": "Info"},
    "Net.ErrorPolicy": {"severity": "Info"},
    "Forge.Loop": {"severity": "Info"},
    "Forge.StateInfo": {"severity": "Info"},
    "Net.InboundGovernor.Remote": {"severity": "Info"},
    "Mempool": {"severity": "Info"},
    "Net.Mux.Remote": {"severity": "Info"},
    "Net.InboundGovernor": {"severity": "Warning"},
    "Net.PeerSelection": SILENCE,
    "LedgerMetrics": SILENCE,
    "Resources": SILENCE,
    "ChainDB.AddBlockEvent.AddedBlockToQueue": {"maxFrequency": 2.0},
    "ChainDB.AddBlockEvent.AddedBlockToVolatileDB": {"maxFrequency": 2.0},
    "ChainDB.AddBlockEvent.AddBlockValidation.ValidCandidate": {"maxFrequency": 2.0},
    "ChainDB.CopyToImmutableDBEvent.CopiedBlockToImmutableDB": {"maxFrequency": 2.0},
    "BlockFetch.Client.CompletedBlockFetch": {"maxFrequency": 2.0},
}

INVALID_BLOCK = "ChainDB.AddBlockEvent.AddBlockValidation.InvalidBlock"
POOL_TRACE_OPTIONS = {**DEFAULT_TRACE_OPTIONS, INVALID_BLOCK: {"severity": "Info"}}

# The traces the harness reads from a pool's log, with the severity 11.1.3 gives each (its `trace-documentation`):
# the refusals of a block, and the chain changes that name a block the pool adopted.
READ = {
    INVALID_BLOCK: "Notice",
    "ChainDB.AddBlockEvent.IgnoreInvalidBlock": "Info",
    "ChainSync.Client.Exception": "Warning",
    "Forge.Loop.ForgedInvalidBlock": "Error",
    "ChainDB.AddBlockEvent.AddedToCurrentChain": "Notice",
    "ChainDB.AddBlockEvent.SwitchedToAFork": "Notice",
}
TIP_CHANGES = ("ChainDB.AddBlockEvent.AddedToCurrentChain", "ChainDB.AddBlockEvent.SwitchedToAFork")

SEVERITIES = ("Debug", "Info", "Notice", "Warning", "Error", "Critical", "Alert", "Emergency")

# What a pool's log names an invalid block with: the traces above and the exceptions that carry the name
# (`InvalidBlock` of the ChainSync client, `PeerSentAnInvalidBlockException` of the punishment).
INVALID_MARK = "InvalidBlock"

# The trace dispatcher's own traces, which echo the configuration (`Reflection.TracerInfoConfig` prints the trace
# options, `InvalidBlock`'s entry among them) and never report a block.
OWN_TRACES = "Reflection."

HASH = re.compile(r"(?<![0-9a-f])[0-9a-f]{64}(?![0-9a-f])")


def effective(options, namespace, kind):
    """The value of option `kind` (`severity`, `detail`, `backends`, `maxFrequency`) for `namespace` under `options`,
    from the most specific namespace that sets it, or None."""
    parts = namespace.split(".") if namespace else []
    for n in range(len(parts), -1, -1):
        value = options.get(".".join(parts[:n]), {}).get(kind)
        if value is not None:
            return value
    return None


def emitted(options, namespace, severity):
    """Whether a trace of `severity` at `namespace` passes the severity filter of `options`."""
    floor = effective(options, namespace, "severity")
    return floor is not None and floor != "Silence" and SEVERITIES.index(severity) >= SEVERITIES.index(floor)


def pool_configuration(config):
    """`config` (create-env's configuration, parsed) with the pool scenarios' trace options. A configuration that
    already sets trace options is refused: what it would replace is not known here."""
    if config.get("TraceOptions"):
        raise ValueError(f"the reference configuration already sets TraceOptions: {sorted(config['TraceOptions'])}")
    return {**config, "TraceOptions": POOL_TRACE_OPTIONS}


def log_view(lines):
    """What the harness reads from a pool's log (its lines): the traces that name an invalid block (first five), the
    hashes of the blocks they name, the hashes its chain changes named as its new tip, and how many of these lines
    each read namespace logged. Only traces count (a JSON line with a namespace), not the node's start-up text, and not
    the trace dispatcher's own traces (`OWN_TRACES`)."""
    invalid, refused, tips, counts = [], set(), set(), {}
    for line in lines:
        named = INVALID_MARK in line
        if not named and not any(ns in line for ns in TIP_CHANGES):
            continue
        try:
            entry = json.loads(line)
        except ValueError:
            continue
        ns = entry.get("ns") if isinstance(entry, dict) else None
        if not isinstance(ns, str) or ns.startswith(OWN_TRACES):
            continue
        data = entry.get("data", {})
        if ns in READ:
            counts[ns] = counts.get(ns, 0) + 1
        if named:
            if len(invalid) < 5:
                invalid.append(line.strip()[:600])
            block = data.get("block") if isinstance(data, dict) else None
            refused.update(HASH.findall(json.dumps(block) if block is not None else line))
        elif ns in TIP_CHANGES and isinstance(data, dict) and isinstance(data.get("newtip"), str):
            tips.update(HASH.findall(data["newtip"]))
    return {"invalid": invalid, "refused": refused, "tips": tips, "counts": counts}
