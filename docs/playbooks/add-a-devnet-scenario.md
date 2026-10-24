<!-- SPDX-License-Identifier: Apache-2.0 -->
# Add a devnet scenario

A scenario starts the reference cluster with `up(args)`, runs the Scarab node or a client against it, compares, and exits non-zero on any mismatch or error. Keep the decisions in pure helper functions (`stop_problem` in `scripts/devnet/scenarios/restart.py` is the model) so the unit tests need no Docker.

1. Create `scripts/devnet/scenarios/<name>.py` with an SPDX header, a one-line docstring, a pure `*_problem` helper per check, and `def <name>(args)` that calls `up(args)`, uses `data_root(args.name)` and `read_log(...)`, prints one JSON result line and calls `sys.exit(1)` on failure.
2. Register it in `scripts/devnet/cli.py`: import the function, add a line to the docstring at the top, then add the sub-command and its default:

```python
sc = sub.add_parser("<name>", parents=[scala_options], help="one line: what it proves")
sc.set_defaults(run=<name>)
```

3. Test the helpers in `scripts/tests/test_devnet_<name>.py` (the pattern of `scripts/tests/test_devnet_restart.py`). A scenario with a pure check and no test is not done.

```sh
python3 -m unittest discover -s scripts/tests
```

4. Run it, with your own name, and clean up:

```sh
python3 scripts/devnet.py --name mine <name>
python3 scripts/devnet.py --name mine down
```

Run data goes to `${SCARAB_DATA_DIR:-/tmp/scarab}`, never into the checkout. At most two devnets at once.

5. Wire it: a matrix entry in `.github/workflows/e2e.yml` (`{scenario: <name>, args: '<name>'}`) if it is nightly-worthy; a row in the "one scenario per area" table of CONTRIBUTING if it is the gate for an area; a section in `docs/running.md` ("Devnet scenarios"); a changelog fragment if a user could notice.

Comparisons run original upstream cases without masks, expected-failure lists or tolerances; a missing fixture is an incomplete gate, never a pass.
