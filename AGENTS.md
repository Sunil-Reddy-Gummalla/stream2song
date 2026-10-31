# Stream2Song Codex Workflow

Stream2Song is a hackathon monorepo with an Angular/TypeScript frontend and a Java 21 Spring Boot/Maven backend. Read the relevant README sections and inspect the current implementation before changing it. Preserve existing Twitch OAuth, EventSub, stream detection, chat collection, chat highlights, clip discovery, tests, and user work.

## Required agent sequence for substantial work

Use the project agents in `.codex/agents/` through actual Codex subagent delegation. Keep the stages sequential so only one agent edits files at a time:

1. Ask `planner` to inspect the request and repository read-only and return an ordered plan, affected files, risks, acceptance criteria, and verification commands.
2. Review the plan and criteria. Ask the user only when a material decision cannot safely be inferred; otherwise resolve routine choices and proceed.
3. Send the reviewed plan to `developer`. Wait for implementation and its report before continuing.
4. Send `tester` the original request, acceptance criteria, and Developer's changed-file summary. Wait for an independent review and actual verification results.
5. If Tester identifies actionable defects, send those specific findings to Developer, wait for the fixes, then ask Tester to verify. Repeat as useful, with no more than three fix cycles.
6. Do not declare completion while required checks are failing or unverified. The primary agent collects and summarizes all reports; never ask the user to copy reports between conversations.

For small documentation-only or other trivial changes, use judgment; the full three-stage workflow is required for new features and substantial changes. Use actual delegated agents rather than claiming a role was invoked in prose. Never run multiple agents that edit the same files simultaneously.

## Project safeguards

- Never read, overwrite, print, commit, or expose `backend/.env` or other local secret files. Do not place credentials in frontend configuration.
- Preserve existing code and uncommitted changes. Do not kill processes, discard user changes, commit, or push unless the user explicitly asks.
- Use the actual supported stack: Java 21, Spring Boot, Maven, Angular, and the Node version documented in README. Inspect the repo for real commands instead of assuming them.
- Mock Twitch and other external APIs in automated tests. Do not trigger live Twitch or production operations as part of verification.
- Do not claim a test, build, browser check, or live integration passed unless it actually ran and passed.
- Keep work incremental: implement only the user's requested milestone, then report the objective, summary, files changed, commands actually run and results, Tester verdict, and remaining issues.
