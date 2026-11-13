# What was verified with the Java client

The ACP client here is our own (`core/acp`, D1). This file says what it was run against; the protocol itself is described in the reference extension's `docs/protocol-notes.md`.

## Against the fake agent (every build)

The fake agent (`tests/fixtures/fake-agent/agent.mjs`) is the reference's, copied verbatim, with its recordings. Against it the tests cover:

- the handshake, `session/new`, prompts, streamed updates in wire order, cancel, the Stop watchdog and a late answer after it;
- every recorded scenario replayed through the transcript reducer;
- permission requests with and without a tool kind, policies per session and per resource;
- `fs/read_text_file` and `fs/write_text_file`, including unsaved editor text and the substituted text of an accepted proposal;
- `terminal/*` with real processes through the platform shell;
- `session/list` and `session/load`, mode, model and config options (v2 and v3 shapes), `_session/steer` accepted and refused, `_kiro/session/context`, `_kiro/openExternalUrl`;
- sign-in required at start and from an expired token, and the restart that follows a sign-in;
- a crash of the agent process and a deliberate stop.

The mapping of the reference's verification rows (its e2e tests F1 to F16) to tests here is in the plan under Task 17. F17 (agent link) is not ported.

## Against a real `kiro-cli`

By hand only, by the owner, on Windows with `kiro-cli` 3.2.1:

- signed out: the plugin found the CLI, started it, showed "Sign in required", and the sign-in button opened the terminal sign-in (release 0.1.0);
- signed in, on a second machine: conversations, the session list, the mode and model pickers and agent commands in the console (release 0.1.0, seen in a screenshot).

Not seen with a real CLI: everything added in 0.2.0 (proposals in the compare editor, `@` context and images, past sessions, queued prompts, slash commands, usage, the plan document), whether the CLI honours the `_meta.kiro` hints on `session/new`, and whether `fs/write_text_file` carries the session id the permission request had. Linux and macOS were never run.
