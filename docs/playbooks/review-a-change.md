<!-- SPDX-License-Identifier: Apache-2.0 -->
# Get an independent review of a pull request

**Status: Current.** The rule is [CONTRIBUTING](../../CONTRIBUTING.md#independent-review): every pull request
gets an independent review of its final diff before it is marked ready, and for agent work that is a read-only
Codex review. This is the order of work.

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
3. Run the review read-only on the worktree with the brief as its prompt: Codex in its read-only sandbox
   (`codex exec --sandbox read-only`), or an agent tool's Codex integration given the same brief. A review takes
   minutes; run it in the background rather than block on it.
4. Accept the result only if the run completed, exited successfully and produced findings or an explicit "none".
   A failed, cancelled or empty run is no review: run it again, and never report it as a clean one.
5. Check each finding against the code before acting on it: the reviewer can be wrong. Fix the real findings and
   write down why each of the others is rejected.
6. Post the review on the pull request (`gh pr comment <number> --body-file <file>`): each finding quoted, and its
   disposition, fixed in which commit or rejected and why.
7. Fill in the body's "Independent review" section: the link to that comment, the verdict, and the disposition of
   each finding in a line. `scripts/pr_policy.py` refuses a pull request that is not a draft without the section
   and a link in it.
8. If the fixes changed more than wording, run a second, shorter review scoped to the fix commits, and post and
   link it the same way. After a rebase that changes the diff, the review covers the new head.

A contributor who is not an agent asks a maintainer or another contributor for the review, who posts it on the
pull request; steps 5 to 8 are the same.
