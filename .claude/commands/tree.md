# Git Worktree Management

Manage git worktrees for isolated feature development.

## Arguments

$ARGUMENTS

## Instructions

Parse the first argument to determine the subcommand:

### If first argument is NOT "stop":

1. **Extract the task description** from arguments

2. **Generate a concise branch name** from the task description:
    - Extract 2-3 key words that capture the essence of the task
    - Use lowercase kebab-case format
    - Keep it short (15-30 characters ideal)
    - Examples:
        - "implement JWT authentication" → "jwt-auth"
        - "fix login timeout in Safari" → "fix-login-timeout"
        - "add dark mode support" → "dark-mode"
        - "refactor API endpoints" → "refactor-api"

3. **Enhance the task description** with project context:
    - Identify relevant technologies (React, Kotlin, KOOG, etc.)
    - Reference specific patterns from CLAUDE.md if applicable
    - Keep the enhanced description clear and actionable
    - Preserve the user's original intent

4. **Output the command for the user to run**:

   Display a message with the generated branch name, enhanced description, and the command to execute:

   ```
   Branch name: <generated-branch-name>

   Enhanced task description:
   <enhanced-description>

   Run this command to start the worktree:
   ./scripts/tree-start.sh <generated-branch-name> "<enhanced-description>"
   ```

   This will:
    - Create a new git worktree in the parent directory
    - Create a branch named `worktree/<name>-<timestamp>` from main
    - Start Claude Code in the new worktree with the task description

### If first argument is "stop":

Execute the following checks and operations in order:

#### Step 1: Check if in a worktree

```bash
# Get git root and common dir
git rev-parse --show-toplevel
git rev-parse --git-common-dir
```

If they are the same (after normalizing paths), we're in the main repo, not a worktree.

**If NOT in a worktree:**

- ❌ Stop and message: "You are in the main repository, not a git worktree. This command should only be run from within a
  worktree directory."
- Exit without doing anything

#### Step 2: Check for uncommitted changes

```bash
git status --porcelain
```

**If there are uncommitted changes:**

- ❌ Stop and message: "You have uncommitted changes. Please commit them first:"
- Show the output of `git status --short`
- Suggest: "Run: `git add -A && git commit -m 'your message'`"
- Exit without doing anything

#### Step 3: Prepare for merge

```bash
# Get current branch name
CURRENT_BRANCH=$(git branch --show-current)

# Get main repo path
MAIN_REPO=$(git rev-parse --git-common-dir | sed 's/\.git$//')

# Save current worktree path
WORKTREE_PATH=$(pwd)
```

#### Step 4: checkout main

```bash
# Determine main branch name (main or master)
MAIN_BRANCH=$(git symbolic-ref refs/remotes/origin/HEAD 2>/dev/null | sed 's@^refs/remotes/origin/@@' || echo "main")

# Checkout main
git checkout "$MAIN_BRANCH"

# Pull latest changes
git pull origin "$MAIN_BRANCH"
```

#### Step 5: Attempt merge

```bash
git merge "$CURRENT_BRANCH" --no-ff -m "Merge worktree branch: $CURRENT_BRANCH"
```

**If merge fails (exit code != 0):**

- Show conflicts: `git diff --name-only --diff-filter=U`
- Open the conflicted files for the user to review
- Provide guidance: "I found merge conflicts in these files. Let me help you resolve them."
- Work with the user to fix conflicts
- After fixing, run: `git add -A && git commit`

**If merge succeeds:**

- ✅ Message: "Merge successful!"

#### Step 6: Cleanup (only if merge was successful)

```bash
# go to main repo
cd "$MAIN_REPO"

# Remove the worktree
git worktree remove "$WORKTREE_PATH" --force

# Delete the branch
git branch -D "$CURRENT_BRANCH"
```

- ✅ Message: "Worktree cleanup complete! Branch '$CURRENT_BRANCH' has been merged into '$MAIN_BRANCH' and deleted."
- Show current location: `pwd`

## Usage Examples

```
/tree implement JWT authentication with refresh tokens
```

Creates branch `worktree/jwt-auth-<timestamp>` and starts Claude with the task.

```
/tree stop
```

Checks conditions, merges current worktree back to main, handles conflicts if any, and cleans up.

