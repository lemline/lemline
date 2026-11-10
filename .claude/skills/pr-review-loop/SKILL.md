---
name: pr-review-loop
description: >-
  Use this skill whenever a pull request of this repo has review feedback waiting: Codex (or a
  human) left comments and they need to be triaged, fixed, answered on GitHub, and the threads
  resolved — then the next automated review awaited and the cycle repeated until the reviewer
  stops finding things. Trigger it on a pasted PR or review URL with "traite ça", on "traite les
  commentaires de la PR", "réponds à codex", "boucle sur la review", "clear the review threads",
  or any request to make a PR's review feedback go away — even when the loop is not named. Do NOT
  use it to produce a review of code (that is /code-review), to merge or release, or to decide
  whether a change is allowed at all: tinystaff-change-control still governs every fix produced
  here.
---

# Answering a PR review, with a workflow: round after round

One round is: **enumerate → judge → fix → verify → commit/push → reply → resolve → wait**.
Repeat until a round finds nothing to act on, or a stop condition (§8) fires.

The loop is autonomous inside a round, but not across rounds it should not have started: it never
implements a finding the project gates forbid without asking, and it never spins on a
disagreement. If the user asked only to "answer the current comments", run one round and stop
before §7 — the waiting is what makes it a loop, and it is opt-in.

---

## 0. Resolve the target and check the ground

```bash
# Accepts a number, a PR URL, or a review permalink (…/pull/144#pullrequestreview-4967165301)
PR=$(printf '%s' "${1:-}" | sed -E 's#.*/pull/([0-9]+).*#\1#; s/[^0-9]//g')
PR=${PR:-$(gh pr view --json number --jq .number)}     # default: the PR of the current branch
REPO=$(gh repo view --json nameWithOwner --jq .nameWithOwner)   # owner/name, for the REST paths below
gh pr view "$PR" --json headRefName,state,url
git status --short && git diff --stat HEAD
```

Three preconditions, all blocking:

- **The checkout must be on the PR's `headRefName`.** Answering a review from another branch
  commits the fixes somewhere nobody is reviewing.
- **Pre-existing working-tree changes get surfaced to the user before anything is committed**
  (AGENTS.md, Commit Hygiene). Never bundle them silently into a review-fix commit.
- **Another session may already be looping on this PR.** Modified files matching the paths of open
  review threads, or commits on the branch you did not make, are the tell. Ask before writing.

---

## 1. Enumerate the worklist — unresolved threads, not "the last review"

The naive version of this loop reads the comments of the most recent review. That silently drops
findings: Codex reviews **per commit**, so a finding raised three commits ago and still unfixed
does not necessarily reappear in the newest review. The durable worklist is the set of
**unresolved review threads**.

```bash
.claude/skills/pr-review-loop/scripts/pr-threads.sh "$PR"      # one JSON object per unresolved thread
```

Each line carries the two identifiers the round needs, and they are not interchangeable:

| field | shape | used by |
|---|---|---|
| `threadId` | `PRRT_kwDO…` node id | the `resolveReviewThread` mutation (§6) |
| `replyToId` | integer `databaseId` | the `/replies` REST endpoint (§5) |

`lastAuthor` decides whether a thread is really pending. **An unresolved thread whose last comment
is already yours was answered by a round that died before resolving it** — resolve it, do not
answer it twice. An unresolved thread whose last comment is the bot's *rebuttal of your reply* is
a new finding, not the old one.

**Thread enumeration does not see everything.** Also read, once per round:

```bash
gh api --paginate "repos/$REPO/pulls/$PR/reviews?per_page=100" \
  --jq '.[] | select(.user.login | test("codex")) | {commit_id, body}'
gh api --paginate "repos/$REPO/issues/$PR/comments?per_page=100" --jq '.[] | {user: .user.login, body}'
```

The Codex review **body** carries findings with no inline anchor, and humans leave plain issue
comments. Both belong to the worklist even though no thread exists for them. Pagination is not
optional: these endpoints return 30 items oldest-first by default, so on a PR with a few rounds
the newest content is on a page you did not fetch.

`isOutdated: true` means the line moved, **not** that the issue is gone. Re-derive the location at
current HEAD and judge the substance.

---

## 2. Judge each finding — the comment is a hypothesis

Open the cited `path:line` **at current HEAD** and read the real code before agreeing with
anything. A bot finding can be stale (already fixed by a later commit in the same PR), can misread
control flow, or can be right about the symptom and wrong about the cause.

Codex prefixes each finding with a severity badge (`P1` / `P2` / `P3`). Treat it as a hint about
attention order, never as the decision — a P1 about code that does not do what the comment claims
is still a decline.

Route each finding into exactly one of four outcomes:

| Outcome | When | Action |
|---|---|---|
| **Apply** | Mechanical, local, behaviour-preserving, or a plain bug with an obvious correct fix | Fix it in this round |
| **Apply at the root** | The finding is real but names a symptom | Fix the layer that produced the invalid state, then reply explaining the redirection |
| **Decline** | The code does not do what the comment says, the case is unreachable, or the suggestion contradicts the intended contract | Reply with the evidence (`file:line`, actual behaviour). No code change |
| **Ask the user** | Any gate trigger below | Reply that it is understood and pending arbitration, leave the thread open, keep going on the others |

A **human** comment is not a bot finding: a person knows the intent behind the PR. When you
disagree with a human reviewer, that is a conversation to have, not a decline to post — reply with
the objection and let them arbitrate.

### The gate triggers — findings a loop must not apply on its own

`tinystaff-change-control` governs every fix this loop produces, and a review comment is not an
approval. Escalate instead of implementing when the suggested change:

- **adds a fallback, retry, default value, silent refresh, or defensive recovery path** —
  Root-Cause-First / No-Unapproved-Fallbacks. Bots suggest these constantly, and they are exactly
  the changes the repo forbids without a case-by-case decision. Reply with the root cause you
  found and let the user arbitrate the fallback.
- **adds or moves an abstraction** (helper, service, hook, shared util) — Complexity Budget, and
  past a certain size the Architecture Decision Gate.
- **changes an API contract, a persistence model, authorization, or crosses a domain boundary.**
- **changes what a test asserts about intended behaviour** — that implies a different product
  expectation and needs confirmation (Test Change Confirmation). Adding coverage does not.
- **you have a demonstrable objection to.** Say it before editing, not after.

---

## 3. Fix, then verify

Group the round's fixes into one coherent change. Fix the cause, not the comment: several findings
often share a single root, and one correct fix can close three threads.

Run the tests of every domain touched before pushing (`tinystaff-validation-and-qa` says which).
A round that pushes a red build turns the next automated review into noise, and the loop then
spends the following round cleaning up after itself.

### Two checks the tests do not perform

A green suite says nothing about either of these, and both have produced P1 findings a `grep`
would have caught first. Run them on the round's own diff, before the commit.

**Everything you added is reachable, and everything you asserted is true.** A method written and
never called is not a fix — `revokeForExecution` and `deleteExpiredBefore` both shipped that way,
each leaving the capability it was meant to end alive for a fortnight. A comment claiming
behaviour that does not exist is worse than no comment, because it stops the next reader looking:
"the report filters them at read time" was written beside a reader that filtered nothing.

```bash
# what this round declared, and then: who calls each of them, its own file aside
git diff HEAD -U0 | grep -E '^\+ *(internal |private )?(suspend )?fun [a-zA-Z]'
grep -rn '<symbol>' --include='*.kt' --include='*.ts' . | grep -v '/build/'

# every comment this round added — read them against the code they sit beside
git diff HEAD -U0 | grep -E '^\+ *(//|\*)'
```

**A fix that changes state has other readers.** Fixing the producer without re-reading its
consumers is how one correct local change breaks a state machine: retaining the cron ids whose
disable failed was right, and it silently turned the episode guard that consumes that same trace
into a permanent block on ever retrying them. Before pushing, `grep` the field you changed and
open every consumer — the guards that read it are the ones that will be wrong, not the writers.

---

## 4. Commit and push

Quote the real file list first — `git status --short && git diff --stat HEAD` — never describe the
scope from memory. Commit message in English, in the repo's style: the subject states what is now
true, not "address review comments".

The reply step comes after the push precisely so each reply can name the SHA that fixes the
finding.

---

## 5. Reply to every thread

Every thread on the worklist gets a reply — applied, declined, or escalated. A silent thread is
indistinguishable from a missed one, both for the next round and for a human reader.

Write the body to a scratch file first; replies are multi-line and inline quoting mangles them.

```bash
gh api --method POST "repos/$REPO/pulls/$PR/comments/$REPLY_TO_ID/replies" \
  -f body="$(cat reply.md)" --jq '{id, in_reply_to_id}'
```

`in_reply_to_id` in the response must equal the comment you answered. If it is null, the reply
landed as a new top-level thread — delete it and retry with the right id.

A good reply is two or three sentences: whether the finding was right, **what the real cause was**
when it differs from the comment, and the SHA that fixes it. No thanking, no restating the comment
back to its author.

**Applied:** `Right, and it is the most damaging of the three — fixed in de588e85d. A draft
carried across a sheet change was saved against the capability the user had switched away from.`

**Declined:** `The path is unreachable: sanitizeWorkspaceRelativePath rejects the traversal before
the resolve, AgentPersonaHostStorageService.kt:54. Leaving as is.`

---

## 6. Resolve what has been dealt with

```bash
gh api graphql -f query='mutation($t:ID!){ resolveReviewThread(input:{threadId:$t}){ thread{ isResolved } } }' \
  -f t="$THREAD_ID"
```

Resolve the threads **applied or declined with evidence**. Leave open the ones escalated to the
user: an open thread is how the next round knows arbitration is still pending.

---

## 7. Wait for the next review — keyed on the SHA you pushed

Codex reviews a commit. Waiting for "a newer review" or sleeping a fixed delay both misfire; wait
for Codex to finish with the SHA now at the head of the branch.

```bash
.claude/skills/pr-review-loop/scripts/wait-for-codex.sh "$PR" "$(git rev-parse HEAD)" 900
```

Run it with Bash `run_in_background`: it exits on its own and notifies once. Its last line is
the outcome, and the two outcomes lead to different places:

| printed | meaning | next |
|---|---|---|
| `codex completed <sha> findings=yes` | a review object exists for the SHA | §1: enumerate the new threads and the review body |
| `codex completed <sha> findings=no` | Codex finished and found nothing — the 👍 round | §8: **Nothing to act on**, once every thread is resolved |

`findings=no` is what the end of a loop looks like: a clean review creates **no object** under
`pulls/{pr}/reviews`, only a `✅ Completed` row for the SHA in the bot's summary comment and a 👍
on the PR. The script reads that row; do not wait for a review that will never be posted.

It exits 1 on timeout — report that rather than looping on a review that never came.

### Re-triggering a review with no new commit

A round that only replied pushes nothing, so no review will come — that is the stop condition
"No commit this round" in §8, and it is usually the right place to end. When the loop is instead
asked to continue on an unchanged HEAD, ask for the review with a comment and **pass its
`created_at` as a fourth argument**:

```bash
SINCE=$(gh api --method POST "repos/$REPO/issues/$PR/comments" -f body="@codex review" --jq .created_at)
.claude/skills/pr-review-loop/scripts/wait-for-codex.sh "$PR" "$(git rev-parse HEAD)" 900 "$SINCE"
```

Without it the wait answers in under a second with the review the SHA **already** carries — the
previous round's, whose findings you have just replied to and resolved. The summary row is no
substitute: the bot edits it in place and takes seconds to flip it back to Running (#438: comment
at 16:11:48, row still reading `Completed` for that same SHA until 16:12:01), so for that window
both the old review and a stale `Completed` are visible at once. `SINCE` is what distinguishes the
review you asked for; omit it after a push, where a review of a brand-new SHA can only be yours.

---

## 8. Stop conditions

Exit the loop, and say which condition fired:

- **Nothing to act on** — every thread resolved and the wait on the head SHA ended
  `findings=no` (§7). The success exit. A Codex review with no finding is not a review object
  that is empty; it is the absence of one, plus the 👍 — do not keep waiting for it.
- **No commit this round** — replies only, nothing pushed. No new review will ever come, because
  Codex reviews commits. Exit instead of waiting.
- **A finding survives two rounds** — either the fix did not land or the disagreement is real.
  Escalate; do not spin.
- **A round is mostly about the previous round's fixes** — not a stop condition but the signal that
  precedes one, and the one this loop misses most often. It does not mean the loop is converging;
  it means the fixes are too local, and the reviewer is walking the blast radius you did not. When
  it happens, widen §3's second check before the next push instead of pushing faster: the next
  round will otherwise be about the fix you are writing now.
- **A gate trigger is pending arbitration** and nothing else is left to do.
- **Round budget reached** — default 5. Report where it stopped and what remains open.

---

## Traps

**A review comment is not an approval.** The failure mode of this loop is committing, round after
round, changes the user never sanctioned because a bot suggested them. §2's gate table is the
brake; a round that applied everything the reviewer said has skipped it.

**The bot's login is spelled differently by the two APIs.** REST says
`chatgpt-codex-connector[bot]`, GraphQL says `chatgpt-codex-connector`. An exact-match filter
written for one API silently matches nothing in the other — and "nothing" reads as "the review is
clear".

**An empty result is ambiguous.** A wrong repo argument, an unfetched page, or the wrong login
spelling all return zero items, which looks exactly like a clean PR. Before believing the loop is
done, confirm on the PR page or by count that there is genuinely nothing open.

**"Reviewed commit" in the review body is prose; `commit_id` is data.** Read the field.

**The 👍 is transient; the summary row is the record.** The bot removes its 👍 and posts 👀 the
moment a new review starts, and a 👍 carries no SHA. A reaction seen right after a push can be the
previous round's. `wait-for-codex.sh` keys on the `Completed` row for the SHA and reports the 👍
as corroboration only; do the same when checking by hand.

**Outdated ≠ fixed.** Threads here have been resolved by a human reply while still carrying
`isOutdated: true`. The flag describes the diff, not the defect.

**Do not open a new review to answer one.** Replies go through the `/replies` REST endpoint;
creating a review to answer a comment produces an orphan thread and loses the anchor.

**One round, one commit, one push.** Pushing mid-round makes Codex review a half-finished state,
and the next worklist fills with findings that were about to be fixed anyway.

**The conversation with the user is in French; everything written to GitHub and to files is in
English** (AGENTS.md Language Policy — commit messages, replies, code, and this skill).
