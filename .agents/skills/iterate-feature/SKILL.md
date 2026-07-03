---
name: iterate-feature
description: Use when the user wants Codex to continue, revise, polish, debug, or iterate on the feature or fix already being worked on, while staying on the current git branch and avoiding branch creation or branch switching.
---

# Iterate Feature

Use this workflow to continue work already in progress without changing branches.

## Workflow

1. Inspect current state.
   - Check `git status --short --branch`.
   - Identify the current branch and keep working on it.
   - Review relevant diffs and files before editing.

2. Preserve branch context.
   - Do not create a new branch.
   - Do not switch branches.
   - Do not pull or rebase unless the user explicitly asks.
   - If another workflow would normally create or switch branches, skip that part and continue on the current branch.

3. Iterate on the existing work.
   - Focus on the user's requested refinement, bug fix, test failure, review comment, or follow-up.
   - Keep edits scoped to the existing feature or fix.
   - Preserve unrelated user changes.

4. Verify before finishing.
   - Run the smallest meaningful checks first, then broader project checks when warranted.
   - For this repo, prefer `./build.sh` when a full build is needed.
   - Report any check that could not be run or failed.

5. Commit in the repository's existing style and cadence when committing is requested.
   - Stay on the current branch; do not create or switch branches for the commit.
   - Inspect recent commit history before the first commit.
   - Match the local style: short imperative subject lines such as `Add ...`, `Fix ...`, `Improve ...`, or `Move ...`.
   - Commit after coherent, buildable units of work rather than after every tiny edit.
   - Before every commit, run the project's build command. For this repo, prefer `./build.sh` when available; otherwise use the established Gradle build command.
   - Do not commit if the build fails. Fix the issue or report the blocker.

## Guardrails

- Never discard or rewrite user changes unless explicitly requested.
- Never force-push unless explicitly requested and the branch is known to be Codex-owned.
- If committing is requested, commit on the current branch only.
