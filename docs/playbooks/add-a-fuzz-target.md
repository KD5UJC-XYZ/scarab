<!-- SPDX-License-Identifier: Apache-2.0 -->
# Add a fuzz target

**Status: Current.** The harness, its properties and its commands are described in
[running.md](../running.md#fuzzing-the-decoders-roadmap-k3); this is the order of work.

1. Seeds: committed fixtures (`fixtures/`) or valid items sampled from the pinned CDDL
   (`Corpus.sampled` in `fuzz/src/test/scala/xyz/kd5ujc/scarab/fuzz/Corpus.scala`). Seeds must be
   valid: `GuardSuite` fails when a registered target accepts none of its seeds.
2. Add a `Target(name, corpus, check)` in
   [Targets.scala](../../fuzz/src/test/scala/xyz/kd5ujc/scarab/fuzz/Targets.scala), built like its
   neighbours (the private `probe` around the decoder call, which records an accepted input with
   `hit`), and add it to `Targets.all`. Where the codec has a writer, check that what decodes
   re-encodes and decodes to the same value.
3. Run only the new target, then the decoder budget:

   ```sh
   scripts/sbtw "fuzz 2000 1 1 my-target"
   scripts/sbtw "fuzz 100000 1 4"
   ```

   A failure prints the input and the seed; the same command repeats it.
4. List the entry point in running.md if that section enumerates it, and pick the level and gates
   from [CONTRIBUTING](../../CONTRIBUTING.md#gates-and-ceremony-by-level-of-change) (the "A decoder"
   row).
