<!-- SPDX-License-Identifier: Apache-2.0 -->
# Rebase after a squash merge

**Status: Current.** When a pull request that yours builds on is squash-merged, `main` holds one new
commit with its changes, and your branch still holds the original commits. This is the order of work.

1. Fetch, and find the last commit of the merged pull request that your branch contains: read
   `git log --oneline origin/main..HEAD` and take the newest commit that does not belong to this change. The merged pull
   request's head (`gh pr view <merged> --json headRefOid`) is only a candidate: it differs when that pull
   request moved after you branched, so confirm it with `git merge-base --is-ancestor <commit> HEAD` and
   that every commit above it belongs to this change.
2. Replay only your own commits onto `main`:

   ```sh
   git fetch origin
   git rebase --onto origin/main <merged-head> <your-branch>
   ```

   A plain `git rebase origin/main` also replays the merged commits, and their conflicts with the squash
   commit look alarming without being yours.
3. Resolve each conflict so that both the merged change and yours survive, checking each resolution with
   `git diff` and `git diff --staged` before `git rebase --continue`. When the rebase has finished, read the
   whole result (`git diff origin/main...HEAD`) for a fix the markers did not show. Taking a whole file from one side
   drops the other side's changes; during a rebase `--ours` is the base you replay onto and `--theirs` is
   your commit, the reverse of a merge.
4. Dated sections appended by several pull requests (an ADR's amendments, a changelog list) keep every
   entry, in date order. If yours supersedes a sentence the merged one added, strike it through with a
   link to your amendment, as [AGENTS.md](../../AGENTS.md#how-code-is-written-here) describes.
5. Check that `git log origin/main..HEAD` lists only the commits of this change, then push with
   `git push --force-with-lease=<your-branch>:<old-head>`.
6. Run the gates again from step 2 of [run-the-gates-to-ready.md](run-the-gates-to-ready.md) (the level's
   gates and scenarios, then preflight), and re-check the change's factual claims against the new base.
