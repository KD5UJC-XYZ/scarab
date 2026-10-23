# SPDX-License-Identifier: Apache-2.0
"""Local devnet: a 3-pool reference cluster plus the Scala node, all in Docker.

    devnet.py up                   create genesis and start the reference cluster
    devnet.py tip [--node N]       reference tip as JSON
    devnet.py follow [--seconds N] run the Scala follower against node1 (logs to the run dir)
    devnet.py load [--seconds N]   submit randomized ADA transfers between wallets
    devnet.py compare              compare the Scala ledger with the reference at one block
    devnet.py soak [--seconds N]   up + follow + load + periodic compare, then a summary
    devnet.py peercut              up + the Scala node with two peers, one slow then silent mid-sync (issue 130)
    devnet.py governor             up + the Scala node with the three pools as public roots (one through a slow proxy),
                                   targets of 3/3/2 and churn every few seconds: the targets, the churn rounds, the
                                   demotion by the peer metrics, a silent peer lost and coming back (G1.2)
    devnet.py relay [--seconds N]  up + the Scala node as a relay (follows the pools, serves a
                                   Haskell relay) + load through that relay; tips and propagation
    devnet.py pool [--seconds N]   the Scala node as one of the three block-producing pools: it takes
                                   pool 1's keys (pool 1's Haskell node is never started), forges in
                                   its leader slots, and the Haskell pools adopt its blocks
    devnet.py kes-rotate [--seconds N]
                                   pool, with the KES key and operational certificate rotated unattended by
                                   scripts/pool/rotate_kes.py (SIGHUP reload): no restart, forging continues, the
                                   certificate counter goes up, the Haskell pools adopt every block
    devnet.py ntc [--seconds N]    the Scala node's node-to-client interface against the reference node's:
                                   LocalStateQuery, local ChainSync and LocalTxMonitor byte for byte, `cardano-cli query`, refused transactions
    devnet.py up-eras              reference cluster that walks Byron, Shelley ... Conway
    devnet.py capture-eras         capture the up-eras chain as test fixtures (fixtures/eras)
    devnet.py sample-chain-dep     next to up-eras: the reference's DebugChainDepState answers at chosen tips
    devnet.py assemble-chain-dep   write answers.json of the sampled points that are in the captured chain
    devnet.py e2e [--keep]         up-eras + Scala follower from Origin; assert every era and
                                   era transition was followed with no validation error
    devnet.py refused-fixture      write fixtures/devnet/refused-txs.jsonl from a finished
                                   `soak --governance-extended` run directory
    devnet.py oracle               compare the Scala node's epoch-boundary state digests (written by
                                   `e2e --oracle`, `soak --oracle` or `follow --dump-state`) with the
                                   Haskell ledger state db-analyser stores in the reference container
    devnet.py restart              up + follow --db, stop it with SIGTERM (the JVM must exit within 30 s, issue 89),
                                   start it again (it must restore the snapshot, and the files are V5)
    devnet.py faults --seed N      the Scala node as pool 1 next to two Haskell pools, under a seeded schedule of network faults
                                   (tc netem latency and loss, partitions and heals, node crashes and restarts);
                                   convergence, growth, no fatal outcome and rejoin are checked, and the schedule
                                   is written to the run directory (issue 224; the nightly randomized run)
    devnet.py down                 remove containers and the run directory

Run data lives in ${SCARAB_DATA_DIR:-/tmp/scarab}/<name>. The Scala node shares the
reference container's network namespace because the reference nodes bind to loopback.
"""
import argparse
import os
from .chaindep import assemble, sample
from .cluster import down, tip, up, up_eras
from .compare import compare
from .follower import follow
from .load import govload, load
from .oracle_cmd import oracle
from .refused_fixture import DEFAULT_OUT, refused_fixture
from .scenarios.faults import faults
from .scenarios.e2e import CONWAY_EPOCHS, capture_eras, e2e
from .scenarios.governor import governor
from .scenarios.ntc import ntc
from .scenarios.peercut import peercut
from .scenarios.pool import kes_rotate_scenario, pool
from .scenarios.relay import relay
from .scenarios.peer_sharing import peer_sharing
from .scenarios.restart import restart
from .scenarios.v1context import v1_context
from .scenarios.soak import soak
from .util import REPO


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--name", help="container and run-directory name (default scarab-devnet; scarab-eras for up-eras, capture-eras, e2e; scarab-pool for pool)")
    scala_options = argparse.ArgumentParser(add_help=False)
    scala_options.add_argument("--scala-cpus", type=float, default=2,
                               help="CPUs of the Scala node's container (default 2; 1 shows how it behaves when the host is short of CPU)")
    scala_options.add_argument("--max-starvation-warnings", type=int, default=None,
                               help="fail when the Scala node logged more cats-effect CPU-starvation warnings than this "
                                    "(default: only report them)")
    sub = p.add_subparsers(dest="command", required=True)
    sub.add_parser("up", help="create genesis and start the 3-pool reference cluster").set_defaults(run=up)
    def eras_options():
        # a fresh parser each time: `set_defaults` of the e2e subparser would otherwise change the shared
        # actions' defaults and turn the plain up-eras defaults into the e2e ones
        eras = argparse.ArgumentParser(add_help=False)
        eras.add_argument("--epoch-length", type=int, default=500, help="slots per epoch")
        eras.add_argument("--active-slots-coeff", type=float, default=0.2, help="Praos active slot coefficient f")
        eras.add_argument("--byron-epochs", type=int, default=1, help="epochs spent in Byron")
        eras.add_argument("--byron-slot-ms", type=int, default=1000, help="Byron slot length in milliseconds")
        eras.add_argument("--stall-seconds", type=int, default=90, help="fail when the chain does not grow for this long")
        eras.add_argument("--shelley-epochs", type=int, default=1,
                           help="epochs at protocol 2 (the fixture cluster has 1; e2e uses 3 so pre-Allegra rewards pay out)")
        eras.add_argument("--min-utxo-value", type=int, default=0,
                           help="Shelley genesis minUTxOValue (0 in the fixture cluster; e2e uses 1 ada)")
        eras.add_argument("--pool-margin", default="0",
                           help="margin of the three pools (0 in the fixture cluster; e2e uses 0.1)")
        eras.add_argument("--cross-rewards", action="store_true",
                           help="register the pools so reward accounts also delegate to other pools (e2e does)")
        return eras
    sub.add_parser("up-eras", parents=[eras_options()],
                   help="start in Byron and walk Shelley..Conway (one epoch per era step)").set_defaults(run=up_eras)
    ce = sub.add_parser("capture-eras", help="capture the running up-eras chain as fixtures")
    ce.add_argument("--out", default=str(REPO / "fixtures/eras"), help="fixture directory")
    ce.add_argument("--conway-blocks", type=int, default=3, help="Conway blocks to capture")
    ce.set_defaults(run=capture_eras)
    sc = sub.add_parser("sample-chain-dep", help="next to up-eras: record the reference's DebugChainDepState answers at chosen tips")
    sc.add_argument("--out", required=True, help="JSON file of the recorded points")
    sc.add_argument("--interval", type=float, default=0.8, help="seconds between acquires")
    sc.add_argument("--spacing", type=int, default=100, help="slots between kept points inside an epoch")
    sc.add_argument("--dense-per-epoch", type=int, default=6,
                    help="tips kept while the clock is in an epoch the tip has not entered (after a hard fork, before the first block of the new era)")
    sc.add_argument("--byron-epochs", type=int, default=1, help="Byron epochs of the cluster (as up-eras)")
    sc.add_argument("--until-epoch", type=int, default=9, help="stop when the tip reaches this epoch")
    sc.add_argument("--timeout", type=int, default=1800, help="overall time limit in seconds")
    sc.set_defaults(run=sample)
    ac = sub.add_parser("assemble-chain-dep", help="write answers.json: the sampled points whose block is in a capture-eras chain")
    ac.add_argument("--points", required=True, help="the --out file of sample-chain-dep")
    ac.add_argument("--out", default=str(REPO / "fixtures/chain-dep"), help="the capture-eras directory (blocks.hex); answers.json goes here")
    ac.set_defaults(run=assemble)
    e2 = sub.add_parser("e2e", parents=[eras_options(), scala_options],
                        help="up-eras, follow from Origin, assert every era and transition")
    e2.add_argument("--timeout", type=int, default=1500, help="overall time limit in seconds")
    e2.add_argument("--until-epoch", type=int,
                    help=f"Conway epoch to reach (default: first Conway epoch + {CONWAY_EPOCHS})")
    e2.add_argument("--keep", action="store_true", help="leave the cluster running")
    e2.add_argument("--no-load", action="store_true", help="no era-specific transactions (blocks only)")
    e2.add_argument("--no-compare", action="store_true", help="skip the per-era ledger comparisons")
    e2.add_argument("--min-comparisons", type=int, default=3,
                    help="matched ledger comparisons required in each Shelley-based era")
    e2.add_argument("--oracle", action="store_true",
                    help="the follower writes a state digest at every epoch boundary; afterwards db-analyser "
                         "states of the reference node are compared with them (needs a reference container still up)")
    e2.set_defaults(run=e2e, shelley_epochs=3, min_utxo_value=1_000_000, pool_margin="0.1", cross_rewards=True)
    t = sub.add_parser("tip", help="print the reference tip as JSON")
    t.add_argument("--node", type=int, default=1, help="reference node 1..3")
    t.set_defaults(run=tip)
    f = sub.add_parser("follow", parents=[scala_options], help="run the Scala follower against a reference node (logs to the run dir)")
    f.add_argument("--seconds", type=int, help="stop after this long (default: until killed)")
    f.add_argument("--node", type=int, default=1, help="reference node the follower connects to")
    f.add_argument("--dump-state", action="store_true",
                   help="write a ledger-state digest at every epoch boundary to <run dir>/dump (for `oracle`)")
    f.add_argument("--db", action="store_true",
                   help="keep the follower's chain database in the run directory (<run dir>/db), snapshots at each "
                        "epoch boundary, so that a second `follow --db` restarts from the snapshot")
    f.add_argument("--snapshot-every", help="with --db: the snapshot interval of the node (default 1epochs in the harness, to exercise snapshots and restarts; the node itself defaults to the reference policy)")
    f.add_argument("--classes", help="run the classes of an earlier build (the `classes` directory of another run) "
                                     "instead of the checkout's, to write a database in an older format "
                                     "(the environment variable SCARAB_FOLLOW_CLASSES does the same for the followers "
                                     "that other scenarios start, as `e2e --oracle`)")
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
    sk = sub.add_parser("soak", parents=[scala_options], help="up + follow + load + periodic compare, then a summary")
    sk.add_argument("--seconds", type=int, default=600, help="load phase length")
    sk.add_argument("--every", type=int, default=20, help="seconds between ledger comparisons")
    sk.add_argument("--seed", type=int, default=1, help="random seed of the load")
    sk.add_argument("--governance-extended", action="store_true",
                    help="the governance load also runs the extended scenarios (about 16 epochs of 500 slots; use --seconds 900)")
    sk.add_argument("--oracle", action="store_true",
                    help="follower writes epoch-boundary state digests; compared with db-analyser at the end")
    sk.set_defaults(run=soak)
    rl = sub.add_parser("relay", parents=[scala_options], help="Scala node as a relay serving a Haskell relay; load through the relay")
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
    rl.add_argument("--no-peer-sharing", dest="peer_sharing", action="store_false",
                    help="leave PeerSharing off for the Scala node (it is on, as it is for the Haskell relay)")
    rl.add_argument("--max-ntn-version", type=int, choices=[15, 16], default=15,
                    help="highest node-to-node version the Scala node and the Haskell relay offer. 15 is the reference's "
                         "default (latestReleasedNodeVersion of cardano-node 11.1.3); 16 turns ExperimentalProtocolsEnabled on "
                         "in both configurations, which is how the reference offers it")
    rl.add_argument("--keep", action="store_true", help="leave the cluster running")
    rl.add_argument("--keep-data", action="store_true", help="keep the run directory")
    rl.set_defaults(run=relay)
    ps = sub.add_parser("peer-sharing", parents=[scala_options],
                        help="bounded private two-way PeerSharing governor proof with one configured local root")
    ps.add_argument("--timeout", type=int, default=420, help="seconds after activation setup (default420; includes300s activation)")
    ps.set_defaults(run=peer_sharing, relay_mode="InitiatorAndResponder", peer_sharing=True, max_ntn_version=15)
    pc = sub.add_parser("peercut", parents=[scala_options],
                        help="Scala node with two upstream peers, one slow and then silent mid-sync; it must reach the tip (issue 130)")
    pc.add_argument("--backlog-blocks", type=int, default=150,
                    help="let the pools grow to this block before the Scala node starts, so it has a backlog to sync")
    pc.add_argument("--backlog-timeout", type=int, default=900, help="seconds the cluster may take to grow the backlog")
    pc.add_argument("--cut-after-blocks", type=int, default=30, help="silence the slow peer once the node has applied this many blocks")
    pc.add_argument("--delay", type=float, default=0.1, help="seconds the slow peer's proxy holds every chunk")
    pc.add_argument("--sync-timeout", type=int, default=240, help="seconds the node may take to reach the tip after the cut")
    pc.add_argument("--follow-seconds", type=int, default=30, help="seconds the node must keep extending at the tip")
    pc.add_argument("--stall-minutes", type=int, default=1, help="the node's --stall-minutes")
    pc.add_argument("--keep", action="store_true", help="leave the cluster running")
    pc.set_defaults(run=peercut)
    gv = sub.add_parser("governor", parents=[scala_options],
                        help="the peer governor with churn and the peer metrics against the three pools (G1.2)")
    gv.add_argument("--backlog-blocks", type=int, default=60, help="let the pools grow to this block before the Scala node starts")
    gv.add_argument("--backlog-timeout", type=int, default=900, help="seconds the cluster may take to grow the backlog")
    gv.add_argument("--delay", type=float, default=0.4, help="seconds the slow peer's proxy holds every chunk")
    gv.add_argument("--churn-interval", type=int, default=12, help="the node's --churn-interval in seconds")
    gv.add_argument("--rounds", type=int, default=5, help="churn rounds the run must show")
    gv.add_argument("--silent-timeout", type=int, default=420, help="seconds the node may take to lose the silent peer")
    gv.add_argument("--follow-seconds", type=int, default=20, help="seconds the node must keep extending after losing it")
    gv.add_argument("--backoff-timeout", type=int, default=300, help="seconds the lost peer may take to come back once released")
    gv.add_argument("--stall-minutes", type=int, default=10, help="the node's --stall-minutes")
    gv.add_argument("--keep", action="store_true", help="leave the cluster running")
    gv.set_defaults(run=governor)
    nt = sub.add_parser("ntc", help="node-to-client: LocalStateQuery, local ChainSync, LocalTxMonitor and transaction rejections against the reference")
    nt.add_argument("--seconds", type=int, default=240, help="length of the LocalStateQuery comparison phase (with load)")
    nt.add_argument("--warmup", type=int, default=60, help="seconds of load before the first comparison round")
    nt.add_argument("--every", type=int, default=10, help="seconds between comparison rounds")
    nt.add_argument("--seed", type=int, default=1, help="random seed of the load")
    nt.add_argument("--chain-sync-blocks", type=int, default=60, help="blocks followed from Origin on both sockets")
    nt.add_argument("--monitor-burst", type=int, default=4, help="chained transactions per LocalTxMonitor burst")
    nt.set_defaults(run=ntc)
    def pool_flags(pl):
        pl.add_argument("--seconds", type=int, default=480, help="load phase length")
        pl.add_argument("--pool", type=int, choices=[1, 2, 3], default=1, help="the pool whose keys the Scala node takes")
        pl.add_argument("--every", type=int, default=5, help="seconds between tip samples")
        pl.add_argument("--compare-every", type=int, default=30, help="seconds between ledger comparisons")
        pl.add_argument("--epoch-length", type=int, default=500, help="slots per epoch")
        pl.add_argument("--start-delay", type=int, default=15, help="seconds from now to the system start")
        pl.add_argument("--byron-start-offset", type=int, default=0,
                        help="seconds the Byron genesis' startTime (the reference's clock) is set off the Shelley systemStart")
        pl.add_argument("--seed", type=int, default=1, help="random seed of the load")
        pl.add_argument("--interval", type=float, default=0.5, help="load generator pause between transactions")
        pl.add_argument("--min-comparisons", type=int, default=5, help="matched ledger comparisons required")
        pl.add_argument("--scala-flags", default="", help="extra flags for the Scala node, e.g. '--inbound-sync off' (to compare builds or settings)")
        pl.add_argument("--keep", action="store_true", help="leave the cluster running")

    pl = sub.add_parser("pool", parents=[scala_options], help="the Scala node as a block-producing pool among two Haskell pools")
    pool_flags(pl)
    pl.set_defaults(run=pool)
    kr = sub.add_parser("kes-rotate", parents=[scala_options],
                        help="pool, with the KES key rotated unattended (scripts/pool/rotate_kes.py) while the node forges")
    pool_flags(kr)
    kr.add_argument("--max-kes-evolutions", type=int, default=24,
                    help="maxKESEvolutions of the genesis (the Haskell pools' certificates, which are not rotated, must outlast the run)")
    kr.add_argument("--rotate-threshold", type=int, default=21,
                    help="the driver rotates when this many KES periods or fewer are left (24 - 21: every 3 periods)")
    kr.add_argument("--rotate-every", type=float, default=10, help="seconds between runs of the driver")
    kr.add_argument("--min-rotations", type=int, default=2, help="reloads the run must show")
    kr.set_defaults(run=kes_rotate_scenario, seconds=300)
    fl = sub.add_parser("faults", parents=[scala_options],
                        help="seeded network-fault run: tc netem latency and loss, partitions, crashes; checks convergence and growth (issue 224)")
    fl.add_argument("--seed", type=int, default=1, help="seed of the fault schedule; the same seed over the same genesis gives the same schedule")
    fl.add_argument("--duration", type=int, default=600, help="seconds of the schedule, from the moment the chain is running (at least 150)")
    fl.add_argument("--settle", type=int, default=90, help="seconds after the last heal in which all tips must become equal")
    fl.add_argument("--schedule", help="run this schedule file (the fault-schedule.json of an earlier run) instead of drawing one from the seed")
    fl.add_argument("--epoch-length", type=int, default=500, help="slots per epoch")
    fl.add_argument("--start-delay", type=int, default=15, help="seconds from now to the system start")
    fl.add_argument("--byron-start-offset", type=int, default=0, help="seconds the Byron genesis' startTime is set off the Shelley systemStart")
    fl.add_argument("--keep", action="store_true", help="leave the reference container running")
    fl.set_defaults(run=faults)
    rf = sub.add_parser("refused-fixture", help="write fixtures/devnet/refused-txs.jsonl from the run directory of a "
                                                "`soak --governance-extended`")
    rf.add_argument("--out", default=str(DEFAULT_OUT), help="fixture file")
    rf.add_argument("--raw-plutus", action="store_true",
                    help="instead write fixtures/devnet/refused-raw-plutus.jsonl (the raw reasons of `e2e`'s Plutus and "
                         "witness refusals) from the run directory of a finished `e2e`")
    rf.set_defaults(run=refused_fixture)
    rs = sub.add_parser("restart", parents=[scala_options],
                        help="up + follow --db, SIGTERM (must exit within 30 s), follow --db again (must restore the snapshot)")
    rs.add_argument("--seconds", type=int, default=20, help="seconds the follower runs after its first block, before each stop")
    rs.set_defaults(run=restart)
    vc = sub.add_parser("v1-context", help="the Plutus V1 script context case by case against the reference "
                                           "(reference inputs, reference scripts, inline datums, Byron addresses, "
                                           "both validity tags); writes v1-context-cases.json")
    vc.add_argument("--no-up", action="store_true", help="use the cluster that is already up under this name")
    vc.add_argument("--eras", action="store_true",
                    help="the cluster that walks Byron to Conway (no follower), the cases in every Alonzo, Babbage "
                         "and Conway protocol version")
    for opt, default, text in (("--epoch-length", 500, "slots per epoch"),
                               ("--active-slots-coeff", 0.2, "Praos active slot coefficient f"),
                               ("--byron-epochs", 1, "epochs spent in Byron"),
                               ("--byron-slot-ms", 1000, "Byron slot length in milliseconds"),
                               ("--stall-seconds", 90, "fail when the chain does not grow for this long"),
                               ("--shelley-epochs", 1, "epochs at protocol 2"),
                               ("--min-utxo-value", 0, "Shelley genesis minUTxOValue"),
                               ("--pool-margin", "0", "margin of the three pools")):
        vc.add_argument(opt, type=type(default), default=default, help=f"with --eras: {text}")
    vc.set_defaults(run=v1_context)
    sub.add_parser("oracle", help="compare Scala state digests with db-analyser states of the reference node"
                   ).set_defaults(run=oracle)
    sub.add_parser("down", help="remove the containers and the run directory").set_defaults(run=down)
    for scenario in (f, sk, rl, e2, sub.choices["restart"]):
        scenario.add_argument("--consensus-mode", choices=["PraosMode", "GenesisMode"],
                              help="Scala consensus mode; reference pools retain their original configuration")
    args = p.parse_args()
    args.name = args.name or ("scarab-eras" if args.command in ("up-eras", "capture-eras", "e2e", "sample-chain-dep", "assemble-chain-dep") else "scarab-pool" if args.command == "pool" else "scarab-kes-rotate" if args.command == "kes-rotate" else "scarab-devnet")
    args.run(args)
