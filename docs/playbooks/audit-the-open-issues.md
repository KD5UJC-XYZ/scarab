<!-- SPDX-License-Identifier: Apache-2.0 -->
# Audit the open issues and pick up work

**Status: Current.** How to go through a band of open issues, find the ones already done, close or park them as
[process.md](../process.md) says, and choose what to work on. The nightly triage fixes the mechanical drift; this
is the part that needs judgement. The rules are in process.md (states, the [definition of done](../process.md#definition-of-done),
follow-ups, changing course), [REF-4](../design-rules.md#ref-4), [FMT-1](../design-rules.md#fmt-1) and
[what approval is](../design-rules.md#approval); this recipe sets none.

1. **Scope and baseline.** Take one band, P0 first, then P1. Fetch `main` and read code only at `origin/main`.
   Run `python3 scripts/status.py --check`. Note what other sessions hold: `status:in-progress`, branches and
   worktrees that name the issue, open pull requests, comments of the last two days. Leave those alone, or ask
   whoever holds them.
2. **Gather leads, per issue:** the unticked checklist lines, the later comments (scope often moved there), the
   commits that name it (`git log origin/main --oneline --grep '#N'`), and the pull requests that cross-reference it:
   `gh api --paginate "repos/{owner}/{repo}/issues/N/timeline" --jq '.[] | select(.event == "cross-referenced" and .source.issue.pull_request) | [.source.issue.html_url, .source.issue.state, .source.issue.pull_request.merged_at] | @tsv'`.
3. **Assess.** One verdict per issue: done; mostly done (what remains); partial; not started; superseded (by
   what); waiting on the owner (for what). Each claim cites the file and line at `origin/main`, or the merged pull
   request whose diff was read, and says whether it was checked in code or only inferred from a title or comment.
   A large band can be split by theme across read-only reviewers that use the same verdicts.
4. **Verify before acting.** Check every "done" and every "done but unticked" claim against `origin/main` yourself.
   Reviewers misplace lines and miss dependencies, such as an item that waits on another issue's result.
5. **Ask before acting where a rule needs the owner's approval:** a difference from the reference not shown to be
   a Mirror or an Improvement (REF-4; [raise a deviation](raise-a-deviation-or-audit-finding.md)), and any storage
   format change (FMT-1). Check [decisions.md](../decisions.md) first for an answer already given. The question and
   the decision are recorded as what approval is says; a subagent reports to its lead, which asks. Repository
   settings are the maintainer's, never an agent's.
6. **Act, each by its rule in process.md:**
   - *Done:* it closes as completed only when the whole definition of done holds. Move every follow-up first:
     `python3 scripts/file_issue.py --title T --body-file F --priority P2 --parent N` for an open parent's
     checklist, an issue of its own (`--own-issue REASON`; `--help` gives its labels and milestone), or a roadmap
     item; mark the old line "moved to #M". The closing comment gives what was done, the commit and the evidence.
   - *Done but unticked:* tick the line and append "done in #PR". Read the body just before the edit and check it
     after: other sessions edit epics too.
   - *The rest lives in another issue:* fold it, closing with a pointer once that issue is shown to cover it.
   - *A change of priority:* a label or milestone change, with one sentence on why.
   - *Paused on purpose:* replace its status with `status:parked`, remove its milestone, and comment what is done,
     where the work is and how to resume.
   - *A table of audit rows:* it closes when every row has an outcome: aligned, recorded in decisions.md, tracked by
     an issue, or filed.
7. **Pick up work** as process.md's "Now, next, later" orders it: the `status:backlog` issues of each milestone in
   the roadmap's [order of work](../roadmap.md#order-of-work), then by priority, with at most 20 issues in progress.
   A started item follows AGENTS.md "Before you start" and gets `status:in-progress`.
8. **Record** a dated working note, outside the repository, of what was closed, filed, parked and left open, and
   the questions put to the owner.
