<!-- SPDX-License-Identifier: Apache-2.0 -->
# Add a devnet scenario

**Status: Current.** Scenarios are described in
[running.md](../running.md#devnet-scenarios-scriptsdevnetpy). A scenario starts a reference
cluster, runs the Scarab node or a client against it, compares, and exits non-zero on any mismatch
or error.

1. Create `scripts/devnet/scenarios/<name>.py` with an SPDX header and a docstring. Start the
   cluster the check needs: `up(args)` from `scripts/devnet/cluster.py` for the three-pool
   reference cluster (as `restart` does), `up_eras` for the cluster that walks the eras from Byron
   (as `e2e` does). Keep each decision in a pure helper that the unit tests can call without Docker
   (`stop_problem` and `restore_problem` in `scripts/devnet/scenarios/restart.py`).
2. Register it in [scripts/devnet/cli.py](../../scripts/devnet/cli.py): import it, describe it in
   the module docstring, and add a sub-command with `set_defaults(run=<name>)`, as `restart` is.
3. Test the helpers in `scripts/tests/test_devnet_<name>.py`, following
   `scripts/tests/test_devnet_restart.py`, and run `python3 -m unittest discover -s scripts/tests`.
4. Run it under your own name and take it down:

   ```sh
   python3 scripts/devnet.py --name <yours> <name>
   python3 scripts/devnet.py --name <yours> down
   ```

5. Document it in running.md. A scenario that runs nightly is an entry of the matrix in
   `.github/workflows/e2e.yml` (`{scenario: <name>, args: '<name>'}`).
