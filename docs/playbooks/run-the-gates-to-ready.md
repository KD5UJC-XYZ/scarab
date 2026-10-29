<!-- SPDX-License-Identifier: Apache-2.0 -->
# Run the gates and preflight, then mark a pull request ready

**Status: Current.** The gates of each level are in
[CONTRIBUTING](../../CONTRIBUTING.md#gates-and-ceremony-by-level-of-change) and the hand-back rules in
[AGENTS.md](../../AGENTS.md#before-you-hand-back); this is the order of work.

1. Name the level of the change from the
   [levels table](../../CONTRIBUTING.md#levels). A documentation-only change (level 0) needs only that
   its relative links resolve: `python3 scripts/check_docs.py`.
2. Iterate with a persistent sbt server of your own
   ([CONTRIBUTING](../../CONTRIBUTING.md#fast-iteration-a-persistent-sbt-server-optional)), then run
   the level's gates: at least `scripts/sbtw check`, and for level 2 and above
   [one scenario per area touched](../../CONTRIBUTING.md#the-one-scenario-per-area) under a devnet name
   of your own.
3. Rebase onto the pull request's target, then run the repository-wide checks on the final head:

   ```sh
   scripts/preflight --check
   ```

   Do not edit the worktree while it runs: the secret scan, the guards and `check` read its files. A
   known flake is rerun once and its recurrence recorded on its issue; any other failure is fixed first.
4. Fill in the [pull request template](../../.github/pull_request_template.md): the level, each gate
   with its result on the head it ran on, and the deviations from the plan. Every gap the body names
   links an issue of its own ([definition of done](../process.md#definition-of-done)); file one with
   `python3 scripts/file_issue.py` or an issue form.
5. Wait for CI on that same head. Then `gh pr ready <number>`.
6. After any later rebase or merge of the base, go back to step 3, and re-check every factual claim the
   change makes against the new base.
