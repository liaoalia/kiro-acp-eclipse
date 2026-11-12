# Decisions

Choices that are not obvious from the code, with the reason for each. Decisions of the VS Code extension this plugin is ported from are cited as "reference Dn".

## D1. The protocol layer is our own JSON-RPC client, not the ACP Java SDK (2026-11-12)

`core.acp.rpc.JsonRpcConnection` speaks newline-delimited JSON-RPC over the agent's stdio and keeps every message as a Jackson tree. It is about 300 lines and has no dependency beyond Jackson.

**Why not `com.agentclientprotocol:acp-core` 0.11.0**, the version the plan named. Read from its sources and run in a probe against a scripted agent:

- A `session/update` whose kind, enum value or content type the SDK does not know is dropped whole (Jackson `InvalidTypeIdException`, logged). Kiro sends such updates, and the reference relies on receiving them.
- An unknown `stopReason` fails the `session/prompt` call.
- It reads the agent's stdout in the platform charset, which on Windows with Java 17 is an ANSI code page, so non-ASCII text arrives corrupted.
- It writes the discriminator twice in tagged objects (`{"type":"text","type":"text",…}`); whether kiro-cli tolerates that was not tested.
- One line on stdout that is not JSON ends the reader, and when the agent exits pending requests wait for a timeout instead of failing.
- It only talks to a process it spawns itself, with no working directory setting, so the launch rules the plugin needs (`.cmd` through `cmd.exe`, PATH lookup) would mean replacing its transport anyway.

**Why not 0.81.0**, the newest version (published 2026-11-10). The same probe shows it fixes all of the above. Against it:

- It still spawns the process itself and has no catch-all for unknown methods, so Kiro's `_kiro/*` methods must each be registered by exact name.
- It brings Project Reactor (about 5 MB with its other dependencies), and two of its jars are not OSGi bundles and would have to be wrapped or embedded; its JSON mapper is found through `ServiceLoader`, which needs extra care under OSGi. None of that was tried inside Equinox.
- The API broke between 0.18 and 0.80, and the release was two days old.

The reference took the same route for the same reason: it keeps the TypeScript SDK but intercepts messages at stream level, because the typed router rejected unknown update kinds (protocol notes, "SDK notes").

**What this costs.** ACP request and response shapes are not checked by a library; the typed models of this plugin (plan Task 3) and the tests against the fake agent carry that. Revisit if the SDK gains a transport over caller-supplied streams and a raw-message hook.

**What holds it in place.** `FakeAgentProtocolTest` runs the reference's recordings through the connection: a streamed answer, the `tour` (permission round-trip, `fs/*`, `terminal/*`, an unknown update kind, a `_kiro/*` notification, `_meta` fields), an unknown request answered with `-32601`, a crash mid-turn, and five runs of the `slow` recording asserting that every chunk is handled before the prompt's response. `JsonRpcConnectionTest` covers the wire rules against a scripted peer.

## D2. Ordering comes from one reader thread (2026-11-12)

The reader thread handles each message completely before reading the next: it calls the handler for a request or notification and completes the future for a response. So when a prompt's future completes, every update the agent wrote before the response has been handled. Handlers therefore must not block that thread; a request that takes time (a permission the user has to answer) is answered later through its `Responder`, from any thread.

## D3. The chat page must be on a local drive (2026-11-12)

See the plan, "Decisions". `file:` URLs name another machine in too many ways to tell the chat page from a foreign document reliably, and the bridge to the Java host must never serve a foreign document. From a network share the view shows a message instead of the chat.
