#!/usr/bin/env bash
# List the UNRESOLVED review threads of a pull request, one compact JSON object per line.
#
# Usage: pr-threads.sh [PR_NUMBER]        (defaults to the PR of the current branch)
#
# Each line carries both identifiers the loop needs, which is the point of this script:
#   .threadId   PRRT_…  node id      → resolveReviewThread mutation
#   .replyToId  integer databaseId   → POST /pulls/{pr}/comments/{id}/replies
# It also carries .lastAuthor, so a thread already answered but left unresolved is
# recognisable instead of being answered twice.
set -euo pipefail

PR="${1:-$(gh pr view --json number --jq .number)}"
REPO=$(gh repo view --json nameWithOwner --jq .nameWithOwner)

gh api graphql --paginate \
  -F owner="${REPO%/*}" -F repo="${REPO#*/}" -F pr="$PR" -f query='
query($owner:String!,$repo:String!,$pr:Int!,$endCursor:String){
  repository(owner:$owner,name:$repo){ pullRequest(number:$pr){
    reviewThreads(first:50, after:$endCursor){
      pageInfo{ hasNextPage endCursor }
      nodes{
        id isResolved isOutdated path line
        comments(first:30){ nodes{ databaseId author{login} body } }
      } } } } }' \
  --jq '.data.repository.pullRequest.reviewThreads.nodes[]
        | select(.isResolved | not)
        | {threadId: .id,
           replyToId: .comments.nodes[0].databaseId,
           path: .path,
           line: .line,
           outdated: .isOutdated,
           lastAuthor: .comments.nodes[-1].author.login,
           comments: [.comments.nodes[] | {author: .author.login, body: .body}]}'
