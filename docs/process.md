# How work moves

How Scarab's work is tracked, from an idea to a closed issue with evidence. It is written for the
owner, for contributors and for independent agent sessions working in parallel (see also
[AGENTS.md](../AGENTS.md)). The aim is a lean, flexible process: priorities can change at any time,
work can be parked and resumed, and nothing goes stale silently.

## One place per question

| Question | Source of truth | Kept current by |
|---|---|---|
| What work exists, and what state is it in? | [GitHub issues](https://github.com/KD5UJC-XYZ/scarab/issues), one per roadmap ID, with a `status:` label | whoever moves the work, when it moves |
| What is planned, grouped by area? | [roadmap.md](roadmap.md), generated from the register [roadmap.json](data/roadmap.json) by `scripts/render_roadmap.py`: one entry per item, ID, issue link and status; each status derived from its issues through a snapshot that a nightly pull request refreshes | the change that finishes or adds an item |
| What scope does an open issue belong to? | [Milestones](https://github.com/KD5UJC-XYZ/scarab/milestones), scope buckets ([labels and milestones](#labels-and-milestones); each described in [the roadmap](roadmap.md#milestones)) | triage |
| Why is it built this way? | [Architecture decision records](adr/README.md) | a new ADR when a decision is made or reversed |
| What has the owner decided, and what would reopen it? | [decisions.md](decisions.md): one row per decision, linking its record (the ADR or the issue comment) | the change that first relies on the decision |
| How will a large piece of work be built? | [Design documents](design/README.md): approved plans, with their stages | the plan's owner, as stages land |
| What is verified, against what? | [compatibility.md](compatibility.md) (generated matrix; register in `data/compatibility.json`, pages and evidence log in `compatibility/`) | the change that adds evidence |
| What changed? | [CHANGELOG.md](../CHANGELOG.md), and the fragments in [changes/](../changes/README.md) until a release folds them in | every change a user could notice, as a fragment |
| Where is the code, and what covers it? | [codemap.md](codemap.md), generated from `docs/data/codemap.json` and `build.sbt` (CI checks it) | the change that moves a package, entry point or suite |
| How fast and how large? | [performance.md](performance.md) | runs that measure |

[questions.md](questions.md), [glossary.md](glossary.md) and the [playbooks](playbooks/README.md) are
navigation, not sources of truth: they point to the documents above and to the code, and the change
that moves what they point to updates them.

Working notes, run logs and scratch analysis stay out of the repository (contributors keep them
locally; the maintainer's sessions use the ignored `.workspace/` directory). What a reader needs
later is distilled into one of the documents above.

## Lifecycle of an item

```mermaid
flowchart LR
  idea([idea]) -->|shaped: acceptance criteria| backlog([backlog])
  backlog -->|picked up| doing([in progress])
  doing -->|gates pass, evidence recorded| done([closed: done])
  doing -->|paused on purpose| parked([parked])
  parked -->|resumed| doing
  doing -->|waits on X| blocked([blocked])
  blocked --> doing
  idea -->|not worth it| dropped([closed: not planned])
  backlog --> dropped
```

| State | How it shows | Meaning |
|---|---|---|
| idea | open issue, `status:idea` | captured, not yet shaped; anyone may add one at any time |
| backlog | open issue, `status:backlog` | shaped: has scope and acceptance criteria, a priority (`priority:P0..P2`) and an area; not started |
| in progress | `status:in-progress` | someone (a person or an agent session) is on it now |
| blocked | `status:blocked` | waits on another issue or a decision, named in a comment |
| needs owner | `status:needs-owner` | waits on a decision or an action only the owner can take |
| parked | `status:parked` | paused on purpose: started and set aside (the latest comment says what is done, where the branch is and how to resume), or shaped but held back behind other work (for example the Leios items) |
| owner review | label `owner-review` | the owner's review feedback; triaged into a fix, a rule with a sweep, a design issue or an answer, each replied to with its commit ([CONTRIBUTING.md](../CONTRIBUTING.md#owner-review-feedback)) |
| done | closed as completed | the definition of done below holds; the closing comment carries the evidence |
| not planned | closed as not planned | decided against, with the reason in a comment (and an ADR if it was a design choice) |

**Keeping the open list short.** Open an issue for work that will start within about two weeks, or that
needs a decision or a tracked gap. Work further out is a roadmap line, not an issue. Sub-items of one
epic are a checklist in the epic's issue (the Leios items in #73, the performance work in #124), not
an issue each. Fold or close a shaped issue that has not moved for a quarter, with a pointer to where
the item now lives.

**Spikes** (`kind:spike`) are time-boxed explorations. A spike ends in one of three outcomes, recorded
in its closing comment and, when it shapes the design, in an ADR: *adopt* (follow-up issues opened),
*park* (what was learned, what would restart it) or *drop*. A document produced by a spike says
"spike" in its status line until the outcome is decided.

### Now, next, later

- **Now**: issues labelled `status:in-progress`. Keep this short (about three parallel tracks); more
  than that means something should be parked.
- **Next**: the `status:backlog` issues of each milestone, in the [order of work](roadmap.md#order-of-work),
  then by priority.
- **Later**: ideas and parked issues, which carry no milestone.

### Changing course

Changing your mind is normal, not a failure of planning:

- **A new idea** becomes an issue with `status:idea` (or a line to the maintainer, who files it). It is
  shaped and placed at the next triage, not necessarily started.
- **A change of priority** is a label or milestone change, with one sentence on why.
- **An interruption** parks the current item: push or record the branch, write the resume comment,
  label it `status:parked`. Parked work is resumed from the comment, not from memory.
- **A reversed decision** gets a new ADR that supersedes the old one; the old one is kept and marked.

### Labels and milestones

Every open issue carries one or more `area:` labels, one `priority:` and one `status:` label, and a milestone unless its
status is `status:idea` or `status:parked` (owner decision, 2026-10-29):

- areas: `area:<module>` for each sbt module of the [code map](codemap.md) (so a new module is an area at
  once), and for work in no module `area:build` (the build, CI and releases), `area:scripts` (devnet, tooling,
  guards) and `area:docs`; the work stream of an issue is its roadmap item, not a label (owner decision,
  2026-10-29);
- priorities: `priority:P0`, `priority:P1`, `priority:P2`, highest first;
- statuses: the states of the table above;
- milestones are scope buckets, not versions: `reference-parity`, `mainnet-evidence`, `leios-dijkstra`
  (owner decision, 2026-10-29).

`status:in-progress` means an open PR or an active worktree exists for the issue. One not updated for 7 days
is reported as stale, and so is a `status:needs-owner` issue not updated for 14 (triage rule of [#400](https://github.com/KD5UJC-XYZ/scarab/issues/400)).
`python3 scripts/status.py --check` lists every open issue that breaks these rules, and the nightly
[triage workflow](../.github/workflows/triage.yml) writes that list into the one issue titled "Triage
report". File an issue through a form or through `python3 scripts/file_issue.py` (`--help` shows its
options), which refuses one without the three labels and a milestone. A form records the area and the
priority in the body; triage applies them as labels and sets the milestone, until
[#605](https://github.com/KD5UJC-XYZ/scarab/issues/605) applies them
automatically.

### Triage and health check

Nightly, the triage report; at each release, and whenever the list feels stale:

- the triage report is clean;
- every `status:in-progress` issue really is moving, and every `status:parked` one has its resume comment;
- [roadmap.md](roadmap.md), the issue states and the milestones agree;
- every document's status line is still true (current, proposal, spike or historical);
- CI is green, including the nightly scenarios, and dependency updates (`dependencyUpdates`) are reviewed.

## Definition of done

An item is closed as done when all of these hold:

1. The change is on `main`, the gates for its level passed
   ([CONTRIBUTING.md](../CONTRIBUTING.md#gates-and-ceremony-by-level-of-change)), and main CI is green.
   Nightly CI remains the continuing safety net, not an additional wait before closing each issue.
   A red nightly is fixed before new work lands, as CONTRIBUTING describes.
2. Evidence is recorded where its level asks: a test or fixture always; the compatibility matrix for
   consensus-critical work (level 3); the evidence log for stage-boundary runs (level 4);
   performance numbers in performance.md.
3. A changelog fragment in `changes/` if a user could notice. The roadmap item follows its issue: closing the
   issue marks it done at the next nightly refresh (items are never renumbered or deleted).
4. Every gap the work leaves is tracked: a new issue or roadmap item, linked from the closing comment.
   "Not yet" in a document always points at an item.
5. The closing comment states what was done, the commit, and the evidence.

## Documents have a status

Each design or reference document starts with a status line:

- **Current**: describes what the code does now.
- **Proposal**: a plan or estimate, not scheduled or not started.
- **Spike**: exploratory work whose outcome is not decided.
- **Historical**: a record of a run or a decision, kept as it was.

When the code changes what a current document describes, the same change updates the document.

## Findings from an independent audit

An audit or independent review of `main` produces tickets, not a side document. Each finding is one
issue with the label `audit`, a title that starts with its audit ID (`[AUD-01]`, `[AUD-02]`, ...), a
priority (`P0` consensus safety, `P1` the next stabilisation or audit-preparation work, `P2` a bounded
follow-up) and a milestone. A finding is checked against the source before its ticket is written, and the
ticket says which parts are confirmed from source, which come from an earlier reproduction and which are
consequences still to verify.

Every audit ticket states the **boundary** it concerns, the **trusted assumptions**, the **invariant**
that must hold, the **acceptance criteria** and the **evidence** required (the
[audit finding template](../.github/ISSUE_TEMPLATE/audit.yml) has these fields; the registered upstream
revisions to cite are in `docs/data/reference-pins.json`). The fix keeps the
reproduction as a regression test, and the relevant row of [compatibility.md](compatibility.md)
is updated. Where a finding concerns something a hostile peer or client could exploit, the public ticket
describes the missing property and the reference's behaviour, never how to trigger it
([SECURITY.md](../SECURITY.md)): reproduction details stay in private notes, and a private security
advisory carries anything that is actually exploitable. Findings that strengthen an existing item go into
that item as a comment, not as a duplicate. Changes to repository settings (branch protection, reviews)
are the maintainer's, never an agent's.

## Parallel work

Large items are split into stages or jumps in a design document, each small enough for one session.
Independent sessions work in separate git worktrees and separate build containers
([AGENTS.md](../AGENTS.md)), and each opens its own PR when its level's gates pass
([CONTRIBUTING.md](../CONTRIBUTING.md#landing-in-parallel)); nightly CI runs the expensive scenarios
on what landed.

Work targets `main` for now. The planned cutover follows
[the release design](design/releases.md): normal integration targets `dev`, and only same-repository
`dev` can then be a PR source for `main`. Other targets accept any source. #161 tracks branch/check enforcement; implementation completion still requires the
main integration and evidence in the definition of done above. Preparing a prerelease or stable release plan
does not close the adopted-preprod-block gate.
