# SPDX-License-Identifier: Apache-2.0
"""Record real signed context, decode and evaluation controls while the native chain walks eras."""
import argparse
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from devnet.cluster import up_eras
from devnet.scenarios.v1context import Driver, ENV_DIR, in_ref_text, run as contexts
from devnet.util import data_root
import error_transactions
import malformed_transactions


def record(args):
    root = data_root(args.name)
    state = {"driver": None, "seen": set()}

    def wallet():
        address = in_ref_text(args.name,
                              "cardano-cli conway address build --testnet-magic 42 "
                              f"--payment-verification-key-file {ENV_DIR}/stake-delegators/delegator2/payment.vkey "
                              f"--stake-verification-key-file {ENV_DIR}/stake-delegators/delegator2/staking.vkey")
        return f"{ENV_DIR}/stake-delegators/delegator2/payment.skey", address

    def ready(driver):
        state["driver"] = driver

    def tick(tip):
        era, epoch = tip["era"].lower(), tip["epoch"]
        driver = state["driver"]
        if driver is None or era not in ("alonzo", "babbage", "conway"):
            return
        if epoch in state["seen"] or ("epoch", epoch) not in driver.done:
            return
        state["seen"].add(epoch)
        major = driver.protocol[epoch]
        skey, address = wallet()
        prefix = root / f"{era}-pv{major}"
        context_driver = Driver(args.name, era, major, skey, address,
                                log=lambda row: print(json.dumps(row), flush=True))
        failures = contexts(context_driver)
        prefix.with_name(prefix.name + "-context.json").write_text(
            json.dumps(context_driver.cases, indent=1) + "\n")
        if failures:
            raise RuntimeError(f"native context controls disagreed: {failures}")
        malformed_transactions.run(args.name, era, major,
                                   prefix.with_name(prefix.name + "-malformed.json"),
                                   args.registry, wallet=(skey, address))
        error_transactions.run(args.name, era, major,
                               prefix.with_name(prefix.name + "-evaluation.json"),
                               args.registry, args.programs, args.definitions,
                               wallet=(skey, address))
        print(json.dumps({"recordedProfile": [era, major]}), flush=True)

    up_eras(args, on_pools_ready=ready, on_tick=tick)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--name", required=True)
    parser.add_argument("--programs", type=Path, required=True)
    parser.add_argument("--definitions", type=Path, required=True)
    parser.add_argument("--registry", type=Path,
                        default=Path(__file__).resolve().parents[3] / "fixtures/reference/plutus-costing/models.jsonl")
    parser.add_argument("--epoch-length", type=int, default=1000)
    parser.add_argument("--active-slots-coeff", type=float, default=0.2)
    parser.set_defaults(byron_epochs=1, byron_slot_ms=1000, stall_seconds=90,
                        shelley_epochs=1, min_utxo_value=0, pool_margin="0",
                        cross_rewards=False, final_era=None)
    record(parser.parse_args())
