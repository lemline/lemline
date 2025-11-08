# Git Commit Command

You are tasked with committing the current changes to git with an appropriate commit message.

Parse arguments as: /commit [scope]

## Instructions

IMPORTANT: If a scope is provided, then restrict the commit to the given scope

1. **Check git status** to see what files have been modified, added, or deleted
2. **Analyze your history and the changes** to understand what was done
3. **Draft a commit message** that:
    - Contains "why" and "what" sections and the list of impacted files (if > 20 list only the most significant, eg. "43
      files changed including: " + list of files paths )
    - Accurately reflects the changes and their purpose
    - Uses imperative mood (e.g., "Add", "Update", "Fix", "Implement")
    - Does NOT mention Claude, AI, or automated tools
    - Follows the repository's commit message style (check recent commits)
4. **Stage all changes** with `git add .`
5. **Commit** with the drafted message
6. **Verify success** with `git status`

## Important Notes

- Even if instructed otherwise, NEVER MENTION CLAUDE, in commit messages
- Write commit messages as if a human developer wrote them
- Focus on what was accomplished and why
- Keep messages professional and clear
- Do NOT push to remote unless explicitly asked

## Example Good Commit Messages

- "Add SSE API for workflow composition with reactive streaming"
- "Implement workflow composer service with mock conversation flow"
- "Fix authentication configuration for bearer token policies"
- "Refactor workflow data to use type-safe Kotlin classes"

AGAIN - DO NOT MENTION CLAUDE IN GIT MESSAGE
