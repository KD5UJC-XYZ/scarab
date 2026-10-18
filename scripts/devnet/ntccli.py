# SPDX-License-Identifier: Apache-2.0
"""`cardano-cli` as a user runs it against the Scala node's socket, for the `ntc` scenario.

The comparison of `cardano-cli conway query ...` outputs (`CLI_QUERIES` in the scenario) runs the
subcommands one at a time against both nodes. This module runs what a user does with the node: the
`latest` era alias, the socket given by `CARDANO_NODE_SOCKET_PATH`, `query tip` (which asks for the
genesis parameters, the era history and the system start over two connections), and the
`transaction build` / `sign` / `submit` round trip, the build against both nodes from the same
input (the body must be byte-equal) and the submit through the Scala socket only. A connection the
node ends for a reason other than the client finishing is a failure too (`unexpected_closes`).
"""
import datetime
import json
import subprocess
import time

from .ntccases import CLI, Builder
from .util import ENV, MAGIC, docker, in_ref, ref

SCALA_CLI = "/opt/reference/bin/cardano-cli"
SCALA_SOCKET = "/sock/node.socket"
REFERENCE_SOCKET = f"{ENV}/socket/node1/sock"

# why an inbound connection of the Scala node may end during the scenario: the client finished
EXPECTED_CLOSES = {"closed by peer", "connection closed: closed by peer", "client finished"}


def on_scala(args, text, env_socket=False, timeout=90):
    """(exit code, stdout, stderr) of `cardano-cli latest <text>` in the CLI container against the Scala socket."""
    socket = "" if env_socket else f" --socket-path {SCALA_SOCKET}"
    prefix = f"CARDANO_NODE_SOCKET_PATH={SCALA_SOCKET} " if env_socket else ""
    r = docker("exec", f"{args.name}-cli", "/bin/sh", "-c",
               f"{prefix}{SCALA_CLI} latest {text} --testnet-magic {MAGIC}{socket}", check=False, timeout=timeout)
    return r.returncode, r.stdout.strip(), r.stderr.strip()


def on_reference(args, text, timeout=90):
    """The same against the reference node's socket."""
    r = docker("exec", ref(args.name), "/bin/sh", "-c",
               f"{CLI.replace('conway', 'latest')} {text} --testnet-magic {MAGIC} --socket-path {REFERENCE_SOCKET}",
               check=False, timeout=timeout)
    return r.returncode, r.stdout.strip(), r.stderr.strip()


def put(container, path, text):
    subprocess.run(["docker", "exec", "-i", container, "/bin/sh", "-c", f"cat > {path}"], input=text, text=True,
                   check=True)


def read(container, path):
    return docker("exec", container, "cat", path).stdout.strip()


def queries(args, addr, txin):
    """The `latest` queries of a user against the Scala socket, with `CARDANO_NODE_SOCKET_PATH` for the first
    two. Each must exit 0; `query tip` must be a Conway tip at 100 percent."""
    results = {}
    # a time after the devnet's system start, which is the time the scenario runs at
    now = (datetime.datetime.now(datetime.timezone.utc) - datetime.timedelta(seconds=30)).strftime("%Y-%m-%dT%H:%M:%SZ")
    cases = [("tip (socket from the environment)", "query tip", True),
             ("tip", "query tip", False),
             ("protocol-parameters", "query protocol-parameters", False),
             ("utxo --address", f"query utxo --address {addr} --output-text", False),
             ("utxo --tx-in", f"query utxo --tx-in {txin} --output-json", False),
             ("era-history", "query era-history", False),
             ("slot-number", f"query slot-number {now}", False)]
    for label, text, env_socket in cases:
        code, out, err = on_scala(args, text, env_socket)
        results[label] = {"exit": code, "output": (out or err)[:300]}
        if label.startswith("tip") and code == 0:
            tip = json.loads(out)
            results[label]["era"] = tip.get("era")
            results[label]["syncProgress"] = tip.get("syncProgress")
        print(json.dumps({"scalaCli": label, "exit": code}), flush=True)
    return results


def build_and_submit(args):
    """`transaction build` against both sockets from one input (equal bodies), the body signed with the wallet's key
    in the reference container, then `transaction submit` through the Scala socket; it must reach a block."""
    builder = Builder(args.name, REFERENCE_SOCKET)
    payer, payee = builder.wallet(2), builder.wallet(1)
    arguments = (f"transaction build --tx-in {payer['txin']} --tx-out {payee['addr']}+5000000 "
                 f"--change-address {payer['addr']} --out-file /tmp/ncli.body")
    result = {"input": payer["txin"]}
    ref_code, ref_out, ref_err = on_reference(args, arguments.replace("/tmp/", "/work/"))
    scala_code, scala_out, scala_err = on_scala(args, arguments)
    result["buildExit"] = {"reference": ref_code, "scala": scala_code}
    result["buildOutput"] = {"reference": ref_out or ref_err, "scala": scala_out or scala_err}
    if ref_code or scala_code:
        return result
    ref_body, scala_body = read(ref(args.name), "/work/ncli.body"), read(f"{args.name}-cli", "/tmp/ncli.body")
    result["bodiesEqual"] = ref_body == scala_body
    # the body the Scala node's build produced is the one signed and submitted
    put(ref(args.name), "/work/ncli-scala.body", scala_body)
    in_ref(args.name, f"{CLI} transaction sign --tx-body-file /work/ncli-scala.body --signing-key-file "
                      f"{payer['skey']} --out-file /work/ncli-scala.signed")
    signed = read(ref(args.name), "/work/ncli-scala.signed")
    put(f"{args.name}-cli", "/tmp/ncli.signed", signed)
    txid = in_ref(args.name, f"{CLI} transaction txid --tx-file /work/ncli-scala.signed --output-text").stdout.strip()
    code, out, err = on_scala(args, "transaction submit --tx-file /tmp/ncli.signed")
    result.update({"txid": txid, "submitExit": code, "submitOutput": (out or err)[:300], "reachedABlock": False})
    deadline = time.monotonic() + 60
    while code == 0 and time.monotonic() < deadline:
        found = in_ref(args.name, f"{CLI} query utxo --tx-in {txid}#0 --testnet-magic {MAGIC} "
                                  f"--socket-path {REFERENCE_SOCKET} --output-json").stdout
        if json.loads(found):
            result["reachedABlock"] = True
            break
        time.sleep(1)
    return result


def unexpected_closes(events):
    """The inbound connections of the Scala node that ended for another reason than the client finishing."""
    return [e for e in events if e.get("event") == "inbound" and e.get("state") == "closed"
            and e.get("reason") not in EXPECTED_CLOSES]


def failures(queries_result, build, closes):
    """What fails the scenario in the results above."""
    out = [f"cardano-cli latest {label} failed against the Scala node: {r['output']}"
           for label, r in queries_result.items() if r["exit"] != 0]
    out += [f"cardano-cli latest {label}: tip is {r.get('era')} at {r.get('syncProgress')}"
            for label, r in queries_result.items() if label.startswith("tip") and r["exit"] == 0
            and (r.get("era") != "Conway" or r.get("syncProgress") != "100.00")]
    if any(build["buildExit"].values()):
        out.append(f"cardano-cli transaction build failed: {build['buildOutput']} {build['buildExit']}")
    elif not build.get("bodiesEqual"):
        out.append("transaction build produced a different body on the Scala node than on the reference")
    elif build.get("submitExit") != 0 or not build.get("reachedABlock"):
        out.append(f"cardano-cli transaction submit through the Scala socket did not reach a block: {build}")
    if closes:
        out.append(f"the Scala node ended connections for other reasons than the client finishing: "
                   f"{[c['reason'] for c in closes][:5]}")
    return out
