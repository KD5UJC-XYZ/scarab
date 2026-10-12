# SPDX-License-Identifier: Apache-2.0
"""Local devnet: a 3-pool reference cluster plus the Scala node, all in Docker.

    devnet.py up                   create genesis and start the reference cluster
    devnet.py tip [--node N]       reference tip as JSON
    devnet.py follow [--seconds N] run the Scala follower against node1 (logs to the run dir)
    devnet.py load [--seconds N]   submit randomized ADA transfers between wallets
    devnet.py compare              compare the Scala ledger with the reference at one block
    devnet.py soak [--seconds N]   up + follow + load + periodic compare, then a summary
    devnet.py relay [--seconds N]  up + the Scala node as a relay (follows the pools, serves a
                                   Haskell relay) + load through that relay; tips and propagation
    devnet.py pool [--seconds N]   the Scala node as one of the three block-producing pools: it takes
                                   pool 1's keys (pool 1's Haskell node is never started), forges in
                                   its leader slots, and the Haskell pools adopt its blocks
    devnet.py ntc [--seconds N]    the Scala node's node-to-client interface against the reference node's:
                                   LocalStateQuery byte for byte, `cardano-cli query`, refused transactions
    devnet.py up-eras              reference cluster that walks Byron, Shelley ... Conway
    devnet.py capture-eras         capture the up-eras chain as test fixtures (fixtures/eras)
    devnet.py e2e [--keep]         up-eras + Scala follower from Origin; assert every era and
                                   era transition was followed with no validation error
    devnet.py refused-fixture      write fixtures/devnet/refused-txs.jsonl from a finished
                                   `soak --governance-extended` run directory
    devnet.py down                 remove containers and the run directory

Run data lives in ${CSL_DATA_DIR:-/tmp/cardano-scala-lab}/<name>. The Scala node shares the
reference container's network namespace because the reference nodes bind to loopback.
"""
import argparse
from .cluster import down, tip, up, up_eras
from .compare import compare
from .follower import follow
from .load import govload, load
from .refused_fixture import DEFAULT_OUT, refused_fixture
from .scenarios.e2e import CONWAY_EPOCHS, capture_eras, e2e
from .scenarios.ntc import ntc
from .scenarios.pool import pool
from .scenarios.relay import relay
from .scenarios.soak import soak
from .util import REPO


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--name", help="container and run-directory name (default csl-devnet; csl-eras for up-eras, capture-eras, e2e; csl-pool for pool)")
    sub = p.add_subparsers(dest="command", required=True)
    sub.add_parser("up", help="create genesis and start the 3-pool reference cluster").set_defaults(run=up)
    eras_options = argparse.ArgumentParser(add_help=False)
    eras_options.add_argument("--epoch-length", type=int, default=500, help="slots per epoch")
    eras_options.add_argument("--active-slots-coeff", type=float, default=0.2, help="Praos active slot coefficient f")
    eras_options.add_argument("--byron-epochs", type=int, default=1, help="epochs spent in Byron")
    eras_options.add_argument("--byron-slot-ms", type=int, default=1000, help="Byron slot length in milliseconds")
    eras_options.add_argument("--stall-seconds", type=int, default=90, help="fail when the chain does not grow for this long")
    eras_options.add_argument("--shelley-epochs", type=int, default=1,
                              help="epochs at protocol 2 (the fixture cluster has 1; e2e uses 3 so pre-Allegra rewards pay out)")
    eras_options.add_argument("--min-utxo-value", type=int, default=0,
                              help="Shelley genesis minUTxOValue (0 in the fixture cluster; e2e uses 1 ada)")
    eras_options.add_argument("--pool-margin", default="0",
                              help="margin of the three pools (0 in the fixture cluster; e2e uses 0.1)")
    eras_options.add_argument("--cross-rewards", action="store_true",
                              help="register the pools so reward accounts also delegate to other pools (e2e does)")
    sub.add_parser("up-eras", parents=[eras_options],
                   help="start in Byron and walk Shelley..Conway (one epoch per era step)").set_defaults(run=up_eras)
    ce = sub.add_parser("capture-eras", help="capture the running up-eras chain as fixtures")
    ce.add_argument("--out", default=str(REPO / "fixtures/eras"), help="fixture directory")
    ce.add_argument("--conway-blocks", type=int, default=3, help="Conway blocks to capture")
    ce.set_defaults(run=capture_eras)
    e2 = sub.add_parser("e2e", parents=[eras_options],
                        help="up-eras, follow from Origin, assert every era and transition")
    e2.add_argument("--timeout", type=int, default=1500, help="overall time limit in seconds")
    e2.add_argument("--until-epoch", type=int,
                    help=f"Conway epoch to reach (default: first Conway epoch + {CONWAY_EPOCHS})")
    e2.add_argument("--keep", action="store_true", help="leave the cluster running")
    e2.add_argument("--no-load", action="store_true", help="no era-specific transactions (blocks only)")
    e2.add_argument("--no-compare", action="store_true", help="skip the per-era ledger comparisons")
    e2.add_argument("--min-comparisons", type=int, default=3,
                    help="matched ledger comparisons required in each Shelley-based era")
    e2.set_defaults(run=e2e, shelley_epochs=3, min_utxo_value=1_000_000, pool_margin="0.1", cross_rewards=True)
    t = sub.add_parser("tip", help="print the reference tip as JSON")
    t.add_argument("--node", type=int, default=1, help="reference node 1..3")
    t.set_defaults(run=tip)
    f = sub.add_parser("follow", help="run the Scala follower against a reference node (logs to the run dir)")
    f.add_argument("--seconds", type=int, help="stop after this long (default: until killed)")
    f.add_argument("--node", type=int, default=1, help="reference node the follower connects to")
    f.set_defaults(run=follow)
    ld = sub.add_parser("load", help="submit randomized ADA transfers, staking and certificates")
    ld.add_argument("--seconds", type=int, help="stop after this long (default: until killed)")
    ld.add_argument("--wallets", type=int, default=10, help="number of load wallets")
    ld.add_argument("--stakers", type=int, default=4, help="wallets that take part in staking actions")
    ld.add_argument("--stake-rate", type=float, default=0.3, help="share of iterations that submit a staking action")
    ld.add_argument("--interval", type=float, default=0.5, help="pause between transactions in seconds")
    ld.add_argument("--seed", type=int, default=1, help="random seed of the load")
    ld.add_argument("--max-out", type=int, default=200, help="largest output in ADA")
    ld.add_argument("--min-balance", type=int, default=300_000_000, help="minimum lovelace a wallet needs to be used as a sender")
    ld.add_argument("--fresh-wallets-only", action="store_true", help="skip the genesis utxo-keys wallets")
    ld.add_argument("--stats-every", type=int, default=20, help="print the running totals every N transactions")
    ld.add_argument("--socket", help="node socket (inside the reference container) to build and submit through")
    ld.set_defaults(run=load)
    gl = sub.add_parser("govload", help="Conway governance and Plutus scenarios on a Conway-only cluster")
    gl.add_argument("--seconds", type=int, help="stop after this long (default: until killed)")
    gl.add_argument("--extended", action="store_true",
                    help="also run the extended scenarios of govextra.py (SPO default votes, committee rules, "
                         "script accounts and DReps, protocol-11 rules)")
    gl.set_defaults(run=govload)
    sub.add_parser("compare", help="compare the Scala ledger with the reference at one block").set_defaults(run=compare)
    sk = sub.add_parser("soak", help="up + follow + load + periodic compare, then a summary")
    sk.add_argument("--seconds", type=int, default=600, help="load phase length")
    sk.add_argument("--every", type=int, default=20, help="seconds between ledger comparisons")
    sk.add_argument("--seed", type=int, default=1, help="random seed of the load")
    sk.add_argument("--governance-extended", action="store_true",
                    help="the governance load also runs the extended scenarios (about 16 epochs of 500 slots; use --seconds 900)")
    sk.set_defaults(run=soak)
    rl = sub.add_parser("relay", help="Scala node as a relay serving a Haskell relay; load through the relay")
    rl.add_argument("--seconds", type=int, default=300, help="load phase length")
    rl.add_argument("--every", type=int, default=5, help="seconds between tip samples")
    rl.add_argument("--seed", type=int, default=1, help="random seed of the load")
    rl.add_argument("--interval", type=float, default=0.5, help="load generator pause between transactions")
    rl.add_argument("--peers", type=int, default=3, help="reference nodes the Scala node follows")
    rl.add_argument("--sync-timeout", type=int, default=240, help="seconds the Scala node may take to catch up")
    rl.add_argument("--relay-after-blocks", type=int, default=30,
                    help="start the Haskell relay once the Scala node has applied this many blocks")
    rl.add_argument("--min-epochs", type=int, default=4, help="epochs the run must cover")
    rl.add_argument("--relay-mode", choices=["InitiatorAndResponder", "InitiatorOnly"], default="InitiatorAndResponder",
                    help="diffusion mode of the Haskell relay")
    rl.add_argument("--keep", action="store_true", help="leave the cluster running")
    rl.add_argument("--keep-data", action="store_true", help="keep the run directory")
    rl.set_defaults(run=relay)
    nt = sub.add_parser("ntc", help="node-to-client: LocalStateQuery and transaction rejections against the reference")
    nt.add_argument("--seconds", type=int, default=240, help="length of the LocalStateQuery comparison phase (with load)")
    nt.add_argument("--warmup", type=int, default=60, help="seconds of load before the first comparison round")
    nt.add_argument("--every", type=int, default=10, help="seconds between comparison rounds")
    nt.add_argument("--seed", type=int, default=1, help="random seed of the load")
    nt.set_defaults(run=ntc)
    pl = sub.add_parser("pool", help="the Scala node as a block-producing pool among two Haskell pools")
    pl.add_argument("--seconds", type=int, default=480, help="load phase length")
    pl.add_argument("--pool", type=int, choices=[1, 2, 3], default=1, help="the pool whose keys the Scala node takes")
    pl.add_argument("--every", type=int, default=5, help="seconds between tip samples")
    pl.add_argument("--compare-every", type=int, default=30, help="seconds between ledger comparisons")
    pl.add_argument("--epoch-length", type=int, default=500, help="slots per epoch")
    pl.add_argument("--start-delay", type=int, default=15, help="seconds from now to the system start")
    pl.add_argument("--seed", type=int, default=1, help="random seed of the load")
    pl.add_argument("--interval", type=float, default=0.5, help="load generator pause between transactions")
    pl.add_argument("--min-comparisons", type=int, default=5, help="matched ledger comparisons required")
    pl.add_argument("--keep", action="store_true", help="leave the cluster running")
    pl.set_defaults(run=pool)
    rf = sub.add_parser("refused-fixture", help="write fixtures/devnet/refused-txs.jsonl from the run directory of a "
                                                "`soak --governance-extended`")
    rf.add_argument("--out", default=str(DEFAULT_OUT), help="fixture file")
    rf.set_defaults(run=refused_fixture)
    sub.add_parser("down", help="remove the containers and the run directory").set_defaults(run=down)
    args = p.parse_args()
    args.name = args.name or ("csl-eras" if args.command in ("up-eras", "capture-eras", "e2e") else "csl-pool" if args.command == "pool" else "csl-devnet")
    args.run(args)
