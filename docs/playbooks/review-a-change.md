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
3. Run the review with `scripts/codex_review.py --level <the body's level> --brief <brief> --out <review file>`
   from the worktree. It runs Codex read-only with the pinned model, at the lowest effort the level allows
   (CONTRIBUTING.md, "Independent review") or at a higher one given with `--effort`; the reviewer has the network
   and live web search, to read the reference at its pin, and runs no command outside the sandbox. A review takes
   minutes at `high` and longer at `xhigh`; run it in the background rather than block on it. Do not run the review
   through an agent tool's Codex integration: it cannot set the effort, and Codex's default effort is lower than
   any floor.
4. The script accepts the run only if it completed with a final message, its session file records the pinned
   model, the effort, the read-only sandbox and the network, and the worktree is unchanged; it then writes the
   review with its stamp. A refused, failed, cancelled or empty run is no review: run it again, and never report it
   as a clean one.
5. Check each finding against the code before acting on it: the reviewer can be wrong. Fix the real findings and
   write down why each of the others is rejected.
6. Post the review on the pull request (`gh pr comment <number> --body-file <file>`), starting with the file the
   script wrote, stamp included: each finding quoted, and its disposition, fixed in which commit or rejected and
   why.
7. Fill in the body's "Independent review" section: the link to that comment, the verdict, and the disposition of
   each finding in a line.
8. If the fixes changed more than wording, run a second, shorter review scoped to the fix commits, through the
   script at the same level with `--base <the commit before the fixes>`, and post and link it the same way. After a
   rebase that changes the diff, the review covers the new head.

A contributor who is not an agent asks a maintainer or another contributor for the review, who posts it on the
pull request; steps 5 to 8 are the same.
