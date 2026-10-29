<!-- SPDX-License-Identifier: Apache-2.0 -->
# Rebase after a squash merge

**Status: Current.** When a pull request that yours builds on is squash-merged, `main` holds one new
commit with its changes, and your branch still holds the original commits. This is the order of work.

1. Fetch, and find the last commit of the merged pull request that your branch contains (its head when
   you branched or last rebased): `gh pr view <merged> --json headRefOid`.
2. Replay only your own commits onto `main`:

   ```sh
   git fetch origin
   git rebase --onto origin/main <merged-head> <your-branch>
   ```

   A plain `git rebase origin/main` also replays the merged commits, and their conflicts with the squash
   commit look alarming without being yours.
3. Resolve conflicts inside the markers only. Never take a whole file from one side
   (`git checkout --ours`/`--theirs`) unless you have checked that `main`'s version equals the merged
   pull request's head for that file.
4. Dated sections appended by several pull requests (an ADR's amendments, a changelog list) keep every
   entry, in date order. If yours supersedes a sentence the merged one added, strike it through with a
   link to your amendment, as [AGENTS.md](../../AGENTS.md#how-code-is-written-here) describes.
5. Check that `git log origin/main..HEAD` lists only your commits, then push with
   `git push --force-with-lease=<your-branch>:<old-head>`.
6. Run the gates again from step 3 of [run-the-gates-to-ready.md](run-the-gates-to-ready.md), and re-check
   the change's factual claims against the new base.
