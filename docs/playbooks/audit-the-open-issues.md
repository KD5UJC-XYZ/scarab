<!-- SPDX-License-Identifier: Apache-2.0 -->
# Audit the open issues and pick up work

**Status: Current.** How to go through a band of open issues, find the ones already done, close or park them as
[process.md](../process.md) says, and choose what to work on. The nightly triage fixes the mechanical drift; this
is the part that needs judgement. The rules are in process.md (states, the definition of done, follow-ups,
parking), [REF-4 and REF-5](../design-rules.md#ref-4) and [FMT-1](../design-rules.md#fmt-1); this recipe sets none.

1. **Scope and baseline.** Take one band, P0 first, then P1. Fetch `main` and read code only at `origin/main`.
   Run `python3 scripts/status.py --check`. Note what other sessions hold: `status:in-progress`, branches and
   worktrees that name the issue, open pull requests, comments of the last two days. Leave those alone, or ask
   whoever holds them.
2. **Gather, per issue:** the unticked checklist lines, the later comments (scope often moved there), and the pull
   requests that cite it:
   `gh api --paginate "repos/{owner}/{repo}/issues/N/timeline" --jq '.[] | select(.event == "cross-referenced" and .source.issue.pull_request) | [.source.issue.number, .source.issue.state, .source.issue.pull_request.merged_at] | @tsv'`.
3. **Assess.** One verdict per issue: done; mostly done (what remains); partial; not started; superseded (by
   what); waiting on the owner (for what). Each claim cites the file and line at `origin/main`, or the merged pull
   request whose diff was read, and says whether it was checked in code or only inferred from a title or comment.
   A large band can be split by theme across read-only reviewers that use the same verdicts.
4. **Verify before acting.** Check every "done" and every "done but unticked" claim against `origin/main` yourself.
   Reviewers misplace lines and miss dependencies, such as an item that waits on another issue's result.
5. **Act, each by its rule in process.md:**
   - *Done:* move every follow-up first, a P2 to an open parent's checklist
     (`python3 scripts/file_issue.py --priority P2 --parent N`), or to an issue of its own (`--own-issue` with the
     reason) when its parent is the one closing, and mark the old line "moved to #M". Then close with what was
     done, the commit and the evidence.
   - *Done but unticked:* tick the line and append "done in #PR". Read the body just before the edit and check it
     after: other sessions edit epics too.
   - *The rest belongs to another issue:* close with a pointer, once that issue is shown to cover it.
   - *Paused on purpose:* `status:parked`, no milestone, and a comment saying what is done, where the work is and
     how to resume.
   - *A table of audit rows:* it closes when every row has an outcome: aligned, recorded in
     [decisions.md](../decisions.md), tracked by an issue, or filed.
6. **Stop and ask the owner,** after checking decisions.md for an answer already given, about: a difference from
   the reference that looks deliberate (REF-5); any storage format change (FMT-1); demoting, parking or dropping
   scope; anything that changes a deployed node. Repository settings are never an agent's. Labels, comments and
   documents do not approve; record the decision in neutral words with its date.
7. **Pick up work:** the current top priority first, by the roadmap's [order of work](../roadmap.md#order-of-work);
   then bugs that need no decision; then the rest of the band in that order. A started item follows AGENTS.md
   "Before you start" and gets `status:in-progress`.
8. **Record** a dated working note, outside the repository, of what was closed, filed, parked and left open, and
   the questions put to the owner.
