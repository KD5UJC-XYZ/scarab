<!-- SPDX-License-Identifier: Apache-2.0 -->
# Run the gates for a change level

Name the level first ([CONTRIBUTING](../../CONTRIBUTING.md#gates-and-ceremony-by-level-of-change) has the full table), then run only its gates.

| Level | Change | Gates |
|---|---|---|
| 0 | docs, comments | the three doc checks below |
| 1 | refactor, tests, tooling | `check` |
| 2 | behaviour in one area | `check` and one scenario |
| 3 | consensus-critical, persisted format, cross-cutting | `check` and the area scenarios, in parallel |
| 4 | stage boundary | the long runs the design names |

Level 0 (host, Python only):

```sh
python3 scripts/check_docs.py
python3 scripts/render_codemap.py --check
python3 scripts/check_onramp.py
```

Level 1 and above: `check`, through `sbtw`, the JDK-image wrapper of [build in Docker](build-in-docker-from-a-worktree.md).

```sh
sbtw check
```

Level 2 and above: the one scenario for the area, with your own name.

| Area | Scenario |
|---|---|
| ledger rules, rewards, governance, Plutus, headers | `e2e` |
| network, mux, peers, selection, fetch | `relay --seconds 300` |
| storage, snapshots, UTxO | `restart` |
| forging, mempool | `pool` |
| KES, certificates, reload | `kes-rotate` |
| node-to-client | `ntc` |

```sh
python3 scripts/devnet.py --name mine e2e
python3 scripts/devnet.py --name mine down
```

Decoder change: also `sbtw "fuzz 100000 1 4"`. Script change: `python3 -m unittest discover -s scripts/tests`.

Then: changelog fragment `changes/<issue>-<slug>.md` if a user could notice; level 3 adds the compatibility row and the design's "as built"; level 4 adds an evidence-log row with commit, date and command. Repeats and `soak` belong to nightly CI. Remove your containers and `down` your devnet when done.
