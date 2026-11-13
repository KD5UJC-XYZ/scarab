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
5. Generated files are regenerated, never merged by hand: `docs/data/fixture-inventory.json` with
   `python3 scripts/check_test_coverage.py --refresh-fixtures`, a fixture an oracle recorded with its recorder
   (for example `scripts/oracle/genesis-fields/run.py --record`, then `--replay`), and a rendered page with its
   renderer. A JSON register both sides added entries to is joined as JSON: the base's entries plus the ones
   your commit added, never a line-by-line merge of the two.
6. Check that `git log origin/main..HEAD` lists only the commits of this change, then push with
   `git push --force-with-lease=<your-branch>:<old-head>`.
7. Run the gates again from step 2 of [run-the-gates-to-ready.md](run-the-gates-to-ready.md) (the level's
   gates and scenarios, then preflight), and re-check the change's factual claims against the new base.

## A stack of pull requests

When each pull request of a stack targets the branch of the one below it, a squash merge of the bottom one
moves the next one onto `main`:

1. Confirm the merge on GitHub (`gh pr view <merged> --json state` reads `MERGED`) before rebasing anything:
   rebasing onto `main` as if a pull request had merged drops its commits from the next one.
2. Retarget the next pull request first (`gh pr edit <next> --base main`), then rebase it as above and push.
   Pushing first starts two CI runs on the same new head, one for the push and one for the base change;
   the workflow's concurrency group cancels one of them, and its summary jobs report a failure that the
   pull request's page shows in red although the other run passed.
3. Rebase each pull request above onto its rebased parent (`git rebase --onto <new parent head> <old
   parent head>`) and push it; they keep their bases until their turn.

## Reading the checks

`gh pr checks` shows the newest run of each check; the pull request's page shows every run on its head
commit, cancelled ones included. Read them all before calling a pull request green:

```sh
gh api repos/KD5UJC-XYZ/scarab/commits/<head-sha>/check-runs --jq '.check_runs[] | "\(.conclusion // .status) \(.name)"'
```

A summary job that fails within seconds and whose log reads `cancelled` for the jobs it requires belongs to a
superseded run; `gh run rerun <run-id>` runs it again on the same commit.
