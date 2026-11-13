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

## D4. Copying goes through the host, not the browser's clipboard (2026-11-12)

The page copies with `navigator.clipboard.writeText` and, when that fails, with `document.execCommand("copy")`. On the `file:` page in WebView2 the API is there (secure context, `clipboard-write` reported as granted), but Chromium only honours it while the document has focus, and for an embedded browser that depends on how focus reached the widget. The shim (`webview/host/bridge.js`) therefore replaces `writeText`: it posts `host:copy` with the text, `WebChat` puts it on the system clipboard with SWT and answers through `__kiroCopied(id, ok)`, so the page's button still says whether it worked. Only `writeText` is replaced; pasting and `Ctrl+C` on a selection stay the browser's own.

Messages whose type starts with `host:` belong to the shim. `WebChat` handles the ones it knows (`host:openExternal`, `host:copy`) and drops the rest; none reaches the chat controller.

## D5. Eclipse colours are used only where they fit the view (2026-11-12)

`theme.css` has a complete light and a complete dark set of `--vscode-*` values, chosen by the luminance of the view's background. `ChatTheme` overrides a variable only when Eclipse has a colour with the same meaning: the view's background and foreground, the dialog and text fonts, JFace's hyperlink, error and qualifier colours, and the system's list, selection and border colours.

Eclipse's dark theme restyles its own widgets and redefines JFace's colours, but the operating system's colours stay as they are. So a system colour is used only while the system's widget background is on the same side (light or dark) as the view, and a text colour only when its contrast against the view's background is at least 3:1. Everything else falls back to the stylesheet.

## D6. The controller runs on the display thread; chunks are not coalesced (2026-11-12, corrected 2026-11-13)

The reader thread only hands events over. `ChatController` does everything else on one thread, in wire order, and in Eclipse that thread is the SWT display thread (`DisplayScheduler`); tests give it a plain single-thread executor. Reducing an event, building the page's message and serialising it therefore share the thread with the rest of the workbench. A thread of its own was considered and not taken: the controller calls into the workbench (dialogs, editors, consoles) at many points, and nothing measured so far shows the display thread held up. If long replies make the workbench lag, this is the decision to revisit.

One webview message is posted per event. Bursts of `chunk` are passed on as they come: the page already batches its own rendering, and merging chunks in the host would need a timer and a flush before every other message to keep the order.

## D7. Permission cards follow their tool calls through the queue (2026-11-12, corrected 2026-11-13)

A permission request is answered on the reader thread, right after the `tool_call` it belongs to, while that `tool_call` is still waiting in the controller's queue. A change of the pending requests therefore never updates the cards where it happens: it queues a sync onto the controller thread, behind the tool call. The sync carries the ids of the requests that were pending when it was queued and shows no card for a request that only arrived later; that request has a sync of its own, queued behind its own tool call. What a card shows and whether it is still wanted is read when the sync runs.

For the same reason the kind of every announced tool call is also kept in `ToolKindIndex`, written and read on the reader thread. Kiro leaves the kind out of a permission request, and the transcripts, which live on the controller thread, have not seen the `tool_call` yet when the policy is looked up.

## D8. A proposed change is kept in memory and belongs to a session's tool call (2026-11-13)

The reference shows a proposal through two URI schemes and keys it by tool call id. Here the proposed text lives in memory behind `DiffEditors`, keyed by session and tool call: tool call ids are only unique within a session. Unsaved edits belong to the compare editor; saving makes them the proposal, and Accept in the editor or Allow on the card takes what is in the editor first, so unsaved edits count, as in the reference.

What the user made of a proposal is what gets written, by the reference's mechanism: accepting records the agent's text and the user's, and the write interceptor of `FsService` swaps them when the agent writes exactly its own text to that path.

One deliberate difference: opening the same proposal again keeps the user's saved edits. The reference overwrites them with the agent's text; here that happens only when the agent's proposal itself changed.

## D9. The `@` file search walks the disk (2026-11-13)

Suggestions come from a walk of the file system under the outermost open projects (a nested project is searched once), not from the Eclipse resource tree: the agent creates files with its own commands, and the resource tree does not know them until a refresh. Left out are the reference's four folder names (`node_modules`, `.git`, `dist`, `out`) and every folder the resource tree marks derived, team-private or hidden, which covers `bin` and `target`. Links to folders are not followed. The walk runs on a worker thread and a newer query of the same page cancels the older one.

The check of a prompt's images is a prompt interceptor and must be installed before the slash commands' interceptor, because the reference checks images first; `KiroChat` installs them in that order.

## D10. Remembered session options are workspace preferences; past sessions are picked in a dialog (2026-11-13)

Mode, model and config options chosen by the user are stored per workspace (`InstanceScope`) in a child node `memory` of the ui bundle's preferences, in the reference's JSON shape. A child node, so that writing it does not count as a settings change and "Restore Defaults" on the preference page leaves it alone. They are applied to new sessions only, never to a session that is loaded.

Past sessions are chosen in a modal dialog with a filter box (every typed word must occur in the title, time or folder) instead of VS Code's quick pick.

## D11. Nothing is sent to a session that is closing or being replaced (2026-11-13)

A prompt typed during a turn is steered (`_session/steer`) without asking whether the agent advertises it; any refusal marks the session as unable to steer and the prompt is queued. The queue drains one prompt per turn after any stop reason, and is dropped with a notice when the turn ends in an error, the agent goes away, or the session is closed or replaced.

`/clear` replaces a session through `ChatController.replaceSession`: the old one is marked closing, the new one is created, then the old one is closed. That nothing reaches the old session meanwhile is enforced in three places: `Turns.send` waits for the replacement, `Turns.dispatch` checks again after the prompt's files were read, and `Steering` checks before it queues, drains or handles a refusal.

## D12. Sign-in runs in an external terminal window (2026-11-13)

On every platform. TM Terminal is not in the target platform and many installs lack it, and its exit code would have to be read through optional API; the generated script with a status file works everywhere. Nothing of the Eclipse process environment is written into the script; a script that has to set variables of the agent's auth method deletes its own file as its first command.

After the sign-in restart the sessions that had a conversation are loaded back into their views, the focused one first. Which views were closed is remembered per restore, not for ever as in the reference: an Eclipse view keeps its id when it is closed and opened again.

## D13. Slash commands are prompt interceptors; the plan is a document per session (2026-11-13)

Client-side commands are handled by a prompt interceptor installed after the one that checks a prompt's images, which is the reference's order; `KiroChat.install` is the one place that fixes the order, and a test runs it. An interceptor sees a prompt before the steering does, so a command typed during a turn runs at once.

The plan is shown as plain Markdown text in a read-only editor of its own, one per session, refreshed as the agent revises it; Approve and Reject for a `switch_mode` request are buttons inside that editor. The reference has one document that follows the focused session.

A usage poll (`_kiro/session/context`) the agent rejects is not repeated for that agent process.

## D14. What is not ported from the extension (2026-11-13)

Checked against the reference at 0.3.11; `ReferenceCommandsTest` and `PreferenceSettingsTest` pin the commands and settings that are.

- **`kiroAcp.chatLocation` and the chat as an editor tab.** The chat is an Eclipse view; where it sits is the user's perspective layout, not a setting. "New Kiro Chat View" opens a further view where the reference opens a further tab (`kiroAcp.openInEditor`).
- **`kiroAcp.newSessionInSidebar`, `kiroAcp.openPastSessionInSidebar`.** There is one kind of chat view, so there is nothing to tell apart.
- **`kiroAcp.openPastSession` as a workbench command.** The page's history button opens the dialog of past sessions.
- **`kiroAcp.acceptDiff` / `kiroAcp.rejectDiff`.** Accept and Reject are buttons above the panes of the compare editor.
- **Dropping files onto the chat.** A file dropped on the embedded page arrives as bytes without a path; the page attaches images itself and refuses other files. `@` attaches a file.
- **Agent link** (`kiroAcp.agentLink.enabled`, reference D30). Experimental and uncommitted in the reference when this was checked.
- **The prompt "Kiro CLI settings changed. Restart the agent to apply."** A changed path or arguments apply when the agent is next started; the preference page says so.
- **Hiding "Cancel Current Turn" while no turn runs.** The command is always in the view menu and does nothing without a turn.

One setting behaves as in the reference only since 0.2.1: the extra CLI arguments follow `acp`. Before, they replaced it, so the preference page's own example started the CLI without `acp`. A setting written for the old reading, one that itself begins with `acp`, is taken as it is.

