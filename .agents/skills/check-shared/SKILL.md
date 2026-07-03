---
name: check-shared
description: Use when the user asks Codex to inspect files from the shared artifacts folder, currently /home/debian/Artifacts/android, including screenshots, logs, APKs, reports, or other files they mention by name or describe as shared artifacts.
---

# Check Shared

Use this workflow to inspect user-mentioned files from the shared artifacts folder.

## Shared Location

- Treat `/home/debian/Artifacts/android` as the shared folder.
- Resolve `~/Artifacts/android` to `/home/debian/Artifacts/android`.
- When the user names a file without a full path and says it is in shared artifacts, look for it under that folder first.

## Workflow

1. Identify the artifact the user wants checked.
   - Use the exact file name, partial name, extension, timestamp, or description from the user's request.
   - If the file is ambiguous, list likely matches from the shared folder and choose the most relevant one when context is clear.

2. Read or inspect the artifact with the right tool.
   - For screenshots and other local images, use `view_image`.
   - For text-like files such as logs, JSON, XML, Markdown, or plain text, read the relevant parts with shell tools such as `sed`, `rg`, `head`, or `tail`.
   - For APKs or binary files, inspect metadata or structure with available local tools before drawing conclusions.

3. Report findings in task context.
   - Tie observations back to the user's current request.
   - Mention the exact artifact path inspected.
   - If a file cannot be found, say what location and search pattern were checked.

## Guardrails

- Do not assume screenshots or artifacts are inside the repository.
- Do not modify shared artifacts unless the user explicitly asks.
- Do not copy artifacts into the repo unless needed for the task and clearly justified.
