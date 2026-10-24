<!-- SPDX-License-Identifier: Apache-2.0 -->
# Add a fuzz target

Every decoder that reads a peer, a client or a block has a target. A target is seeds plus a property; the harness mutates the seeds and checks bounded time and allocation, no unexpected exception, and a round trip where the codec has a writer.

1. Seeds: use committed fixtures (`fixtures/`) or valid items sampled from the pinned CDDL (`Corpus.scala` in the fuzz project has `Corpus.sampled`). The seeds must be valid, or the fuzzing is shallow: `GuardSuite` requires every registered target to accept at least one seed.
2. Add the target in `fuzz/src/test/scala/xyz/kd5ujc/scarab/fuzz/Targets.scala`: a `Target(name, corpus, check)`, built with `probe(name)` around the decoder call and `hit(name)` when an input decodes. Where there is a writer, also assert that the decoded value re-encodes and decodes to the same value.
3. Add it to a group that `Targets.all` concatenates (or add the group there). Names are unique and describe the entry point (`block-header`, `ntc-query`).
4. Run only your target, then the suite (`sbtw` is the Docker wrapper of [build in Docker](build-in-docker-from-a-worktree.md)):

```sh
sbtw "fuzz 2000 1 1 my-target"
sbtw "fuzz 100000 1 4"
```

The arguments are `fuzz <cases per target and seed> <first seed> <number of seeds> [target-name-part]`. A failure prints the input and the seed; the same command repeats it.

5. `sbtw check` runs the suite at its default budget (20,000 cases, seed 1). A pull request that touches a decoder or the fuzz project also runs `fuzz 100000 1 4` in `.github/workflows/fuzz-gate.yml`; run it locally first.
6. List the entry point in `docs/running.md` ("Fuzzing the decoders") and add a changelog fragment if a user could notice. A new persisted file format gets its target in the same change as its reader.

Level: a new target alone is level 1; a decoder change is level 2 or 3 ([run the gates](run-the-gates.md)).
