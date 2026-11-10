#!/usr/bin/env bash
# Block until Codex has finished reviewing one specific commit of a pull request, then exit 0
# and print ONE classified line the caller branches on:
#   codex completed <sha> findings=yes     a review object exists for the SHA → enumerate (§1)
#   codex completed <sha> findings=no      no review object → "nothing to act on" (§8)
# Exit 1 on timeout — a review that never came is a reportable outcome, not a reason to spin.
#
# Usage: wait-for-codex.sh [PR_NUMBER] [SHA] [TIMEOUT_SECONDS] [SINCE]
#        defaults: PR of the current branch, current HEAD, 900s, no SINCE
#
# SINCE (ISO 8601, e.g. 2026-09-28T16:11:48Z) is REQUIRED when the review was re-triggered by an
# "@codex review" comment with no new commit, and must be that comment's created_at. Such a review
# reviews a SHA that ALREADY carries one, so "a bot review exists for this SHA" would answer
# instantly with the previous round's review — observed on #438, four seconds after the re-trigger,
# pointing at a finding already replied to and resolved. With SINCE only a review submitted after
# it counts. Omit SINCE after a push: a review of a brand-new SHA can only be the one awaited.
#
# The row is not a substitute for SINCE: on #438 the comment landed at 16:11:48 and the summary row
# only flipped to Running at 16:12:01, so for thirteen seconds the row still read Completed for that
# very SHA.
#
# Run it with Bash run_in_background: it exits on its own and notifies once.
#
# A Codex review that finds nothing creates NO object under pulls/{pr}/reviews (observed on
# #334 fd850782a and #335 9ded547c0). Its only records are the summary issue comment
# (<!-- codex-pull-request-review-summary -->, edited in place, one row per review kind carrying
# the reviewed SHA and Running/Completed) and a 👍 reaction on the PR. The reaction is transient —
# the bot swaps it for 👀 when the next review starts — so the summary row is what this script
# reads; the reaction is reported as corroboration only.
set -euo pipefail

PR="${1:-$(gh pr view --json number --jq .number)}"
SHA="${2:-$(git rev-parse HEAD)}"
TIMEOUT="${3:-900}"
SINCE="${4:-}"
REPO=$(gh repo view --json nameWithOwner --jq .nameWithOwner)
BOT='chatgpt-codex-connector[bot]'     # REST spelling; GraphQL drops the [bot] suffix

# Every text extraction below runs under `set -e -o pipefail`, so a grep/sed that matches nothing
# must not kill the script: hence the `|| true` on each of them, and the here-strings feeding
# `grep -q` (an upstream writer's SIGPIPE would otherwise become the condition's status).
# The API calls are the exception: a failed request is "unknown", never "empty" — an empty
# review list obtained from a failed call would read as a clean review.

# "none" (no row for this SHA yet), "running", or "completed" (every row for this SHA is Completed).
# A failed request yields "none": the poll simply continues.
# With SINCE, a Completed row stamped at or before it is the PREVIOUS review's row and is reported
# as running: the bot edits the row in place and takes seconds to flip it back (#438: 16:11:48 comment,
# 16:12:01 flip), so accepting it would exit findings=no while the requested review had not started —
# a clean result with an unread finding behind it, the one failure worse than reporting a stale one.
summary_state() {
  local body rows row row_sha row_time state=none
  body=$(gh api --paginate "repos/$REPO/issues/$PR/comments?per_page=100" \
           --jq ".[] | select(.user.login == \"$BOT\" and (.body | test(\"codex-pull-request-review-summary\"))) | .body" \
           2>/dev/null || true)
  # A table row of the summary: `| 📝 **Code Review** | ✅ **Completed** <time> | `9ded547` | New commits |`.
  # Match the words and the backticked SHA, never the emoji; the SHA is a prefix, not 7 chars by contract.
  rows=$(grep -E '^\|.*`[0-9a-f]{7,40}`' <<< "$body" || true)
  while IFS= read -r row; do
    [ -n "$row" ] || continue
    row_sha=$(sed -E 's/.*`([0-9a-f]{7,40})`.*/\1/' <<< "$row")
    [[ "$SHA" == "$row_sha"* ]] || continue
    if grep -q '\*\*Completed\*\*' <<< "$row"; then
      # datetime="2026-09-28T16:30:28.969809Z"; absent means the row cannot be dated, so it cannot
      # be shown to postdate SINCE either. Same-zone ISO 8601 compares correctly as strings.
      row_time=$(sed -nE 's/.*datetime="([^"]+)".*/\1/p' <<< "$row")
      if [ -n "$SINCE" ] && ! [[ "$row_time" > "$SINCE" ]]; then
        state=running
      else
        [ "$state" = running ] || state=completed
      fi
    else
      state=running
    fi
  done <<< "$rows"
  echo "$state"
}

deadline=$(( $(date +%s) + TIMEOUT ))
while :; do
  # The summary is read BEFORE the reviews on purpose: the bot posts the review object, then
  # flips the row to Completed (#322: 18:43:22 vs 18:43:24). Reading in this order means a
  # Completed row is never seen without the review object that preceded it.
  state=$(summary_state)

  # --paginate + per_page=100: reviews come back oldest-first, so on a PR with many
  # rounds the one you are waiting for is NOT on the first page.
  # Only the bot's reviews count: answering a thread through the /replies endpoint wraps the reply
  # in a review object attributed to the replier, which would otherwise read as a fresh review of
  # the same commit. ISO 8601 timestamps in the same zone compare correctly as strings.
  # Classify only on a successful response: a failed call leaves the outcome unknown and the
  # loop polls again, instead of reading the empty result as "no review, hence no finding".
  if matched=$(gh api --paginate "repos/$REPO/pulls/$PR/reviews?per_page=100" \
       --jq ".[] | select(.user.login == \"$BOT\" and .commit_id == \"$SHA\"\
             $([ -n "$SINCE" ] && printf 'and .submitted_at > "%s"' "$SINCE")) | .id" 2>/dev/null); then
    if [ -n "$matched" ]; then
      echo "codex completed $SHA findings=yes"
      exit 0
    fi
  else
    echo "reviews request failed; retrying" >&2
    state=unknown
  fi
  if [ "$state" = completed ]; then
    thumbs=$(gh api "repos/$REPO/issues/$PR/reactions" \
       --jq "[.[] | select(.user.login == \"$BOT\" and .content == \"+1\")] | length" 2>/dev/null || echo 0)
    echo "codex completed $SHA findings=no thumbs_up=$([ "${thumbs:-0}" -gt 0 ] && echo yes || echo no)"
    exit 0
  fi
  if [ "$(date +%s)" -ge "$deadline" ]; then
    echo "timeout after ${TIMEOUT}s waiting for a codex review of $SHA (summary row: $state)"
    exit 1
  fi
  sleep 30
done
