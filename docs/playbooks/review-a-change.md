<!-- SPDX-License-Identifier: Apache-2.0 -->
# Get an independent review of a pull request

**Status: Current.** The rule is [CONTRIBUTING's independent review](../../CONTRIBUTING.md#independent-review);
this is the order of work.

1. Review the final diff: after the level's gates and preflight pass on the head
   ([run the gates](run-the-gates-to-ready.md)), and before `gh pr ready`. The pull request stays a draft until
   the review is posted and linked.
2. Write a brief in a file outside the checkout, not in a shell command. It names:
   - the repository, the branch, the head commit and the base, and the scope: `git diff <base>...HEAD`;
   - what the change claims, in three to six bullets from the pull request body;
   - that the review is analysis only: no edits, and no delegation back to the author;
   - what to check, citing file and line for every finding: correctness and missed cases, error paths and stack
     safety on deep or untrusted input; every claim about the reference, at its pinned revision (a claim it cannot
     verify is reported as "not verified"); the [design rules](../design-rules.md) the change touches; whether the
     tests would fail if the claimed behaviour were wrong; whether the documents and changelog fragments state
     facts that are true at the head; and any question the change raises of its own;
   - the output: findings ranked P1, P2 and P3, each with file and line, evidence and a suggested fix, or that
     there are none and what was checked.
3. Run the review with `python3 scripts/codex_review.py --level <the body's level> --brief <brief> --out <review
   file outside the worktree>` from the worktree, with everything committed. It runs Codex with the pinned model, at
   the lowest effort the change allows (CONTRIBUTING.md, "Independent review") or at a higher one given with
   `--effort`. The reviewer has the network, to read the reference at its pin, and a read-only file system; with
   approvals off, its commands run only in that sandbox, and live web search is requested. When the change touches
   the script itself, run the copy on the base branch (`git show origin/main:scripts/codex_review.py`) with `--cd`
   naming the worktree, so the code under review is not what runs the review. A review takes minutes at `high` and
   longer at `xhigh`; run it in the background rather than block on it. Do not run the review through an agent
   tool's Codex integration: it cannot set the effort, and the model's default effort is below every floor.
4. The script accepts the run only if Codex completed its turn with a final message, its session file records the
   pinned model and the effort in every turn with no reroute, the read-only sandbox and the network, and the
   worktree's HEAD and status are unchanged; it then writes the review with its stamp on the first line. A refused,
   failed, cancelled or empty run is no review: run it again, and never report it as a clean one.
5. Check each finding against the code before acting on it: the reviewer can be wrong. Fix the real findings and
   write down why each of the others is rejected.
6. Post the review on the pull request (`gh pr comment <number> --body-file <file>`), starting with the file the
   script wrote, stamp included: each finding quoted, and its disposition, fixed in which commit or rejected and
   why.
7. Fill in the body's "Independent review" section: the link to that comment, the verdict, and the disposition of
   each finding in a line.
8. If the fixes changed more than wording, run a second, shorter review scoped to the fix commits, through the
   script at the same level with `--base <the head the last review covered>` (the floor still comes from the whole
   pull request), and post it and link it beside the first. After a rebase that changes the diff, the review covers the
   new head; after one that does not, or a wording fix, add `Since the review: <head commit> <what changed>` to the
   section instead.

A contributor who is not an agent asks a maintainer or another contributor for the review, who submits it as a
GitHub review of the pull request (`gh pr review`), not a comment; its link (`#pullrequestreview-<id>`) goes in the
section, the author records the dispositions in the pull request body, and steps 5 and 7 are the same. A second
review after fixes is another submitted GitHub review, of the new head.
