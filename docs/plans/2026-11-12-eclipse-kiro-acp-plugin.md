# Plan: Eclipse plugin "Kiro ACP Chat"

## Overview
- An Eclipse plugin that spawns `kiro-cli acp` as a child process and talks ACP (JSON-RPC over stdio), a port of the VS Code extension in `M:\Sandbox\vs-code-kiro-plugin` (called "the reference" below; 0.3.10 when vendored).
- The chat UI is the reference's webview bundle, unchanged, shown in an SWT `Browser` (the approach Amazon Q for Eclipse takes). The host is Java: everything the reference does in `src/acp`, `src/features` and `src/ui/chatController.ts` is re-implemented against Eclipse APIs. No Node at runtime.
- The contract between the two halves is the reference's JSON message protocol (`src/ui/protocol.ts`, `ToWebview` / `FromWebview`). The Java host must produce and consume exactly those messages.
- The plugin makes no network calls and has no telemetry; all network access happens inside kiro-cli.
- Staged: an MVP (chat, streaming, tool cards, cancel, permissions, client fs, simple terminal) first; parity features follow as independent tasks.

## Context (from discovery)
- Reference layers: `src/acp/*` (~1460 lines, vscode-free), `src/features/pure/*` (~2270 lines, pure logic with tests), `src/ui/chatController.ts` (2599 lines, orchestration), `src/features/{fs,terminal,permissions,diff,plan,context}.ts` (host services), `src/webview/*` + `media/chat.css` (vendored here, not ported).
- Specification sources in the reference: `docs/DECISIONS.md`, `docs/protocol-notes.md`, `src/ui/protocol.ts`, `test/*.test.ts`, `test/fake-agent/agent.mjs` + `test/recordings/*.json` (fake agent, needs Node), `package.json` (commands and settings to mirror).
- The reference is under active development (0.3.9 → 0.3.10 during planning), so the webview is vendored by a script and never edited here.
- ACP Java SDK: `com.agentclientprotocol:acp-core` on Maven Central (0.11.0 of 2026-05-12, newest 0.81.0 of 2026-11-10). Examined in Task 2 and not used. Two hazards known from the TypeScript SDK (protocol-notes "SDK notes") must be checked: a typed router that rejects unknown `sessionUpdate` kinds, and notifications overtaken by the `session/prompt` response.
- Machine: Windows 11, Maven 3.9.12 whose global settings do not reach Maven Central (hence `.mvn/settings.xml`), default `JAVA_HOME` is JDK 11; JDK 17.0.10 at `D:\Work\Java\jdk-17.0.10`, OpenJDK 23 at `D:\Work\Java\openjdk-23.0.2`. Node 24 is available. `kiro-cli` may not be installed, so automated verification uses the fake agent.

## Verified by the spike (2026-11-12)
- Tycho 5.0.3 builds on JDK 23 with bundles targeting `JavaSE-17`; target platform is the Eclipse 4.32 p2 site plus a Maven location for Jackson 2.17.2.
- A dev product (Eclipse Platform 4.32 + the plugin) is materialised under `releng/io.github.liaoalia.kiroacp.product/target/products/` and runs on JDK 17.
- `new Browser(parent, SWT.EDGE)` loads the page from the plugin's `web/` folder over `file:`; the unmodified webview bundle runs with `webview/host/bridge.js` supplying `acquireVsCodeApi`.
- Page → Java works through a `BrowserFunction` (`ready` arrived), Java → page through `Browser.execute` (`state` rendered: markdown, fenced code with a copy button, session picker).
- The page posts `ready` before SWT's load-completed event, so the host must treat the first message as "page is up".
- A browser in a shell that is never opened still loads the page and runs its scripts, so UI tests drive the DOM without showing a window or sending input.
- Not yet exercised: streaming `chunk` messages and tool cards in a test, Linux/macOS (clipboard, links, dark theme and `localStorage` persistence: see Task 7).

## Decisions
- **Target**: Eclipse 2024-06 (4.32) and later, `JavaSE-17`. `SWT.EDGE` is passed explicitly because Edge became the default Windows browser only in 4.35.
- **Namespace**: `io.github.liaoalia.kiroacp`.
- **Chat location**: one `ViewPart`, multiple instances via secondary ids; no separate editor-area chat (D16/D17 of the reference are not reproduced).
- **Webview**: vendored verbatim under `webview/vendor/` by `webview/sync.mjs`; built by `webview/build.mjs` into the UI bundle's `web/` (git-ignored). Eclipse-specific behaviour goes into `webview/host/` (shim, default theme variables, page), never into `vendor/`.
- **Theme**: `--vscode-*` variables get defaults in `webview/host/theme.css` (light and dark); the host overrides background, foreground and fonts from Eclipse and picks light/dark from the background's luminance.
- **JSON**: Jackson tree model at the page boundary; typed view models in `core`.
- **Protocol layer**: an own NDJSON JSON-RPC client over Jackson (`core.acp.rpc`), not the ACP Java SDK; reasons in `docs/DECISIONS.md` D1. The rest of the code sees only `core.acp` interfaces.
- **Local installs only**: the chat page must be on a local drive. From a network share (`\\server\share`) the view shows a message instead of the chat, because `file:` URLs name another machine in too many ways to tell the page from a foreign document reliably.
- **Not ported**: VS Code tab bookkeeping (D15, D28), webview panel serialisation.

## Repository layout
```
pom.xml, .mvn/                           parent, Tycho, plain-Central settings
target-platform/kiro.target
webview/                                 sync.mjs, build.mjs, host/, vendor/
bundles/io.github.liaoalia.kiroacp.core  no SWT/UI imports: acp/, model/, pure/, session/
bundles/io.github.liaoalia.kiroacp.ui    chat/ (view, WebChat), services/, diff/, prefs/, handlers/
tests/io.github.liaoalia.kiroacp.core.tests, tests/io.github.liaoalia.kiroacp.ui.tests
tests/fixtures/fake-agent, tests/fixtures/recordings   copied from the reference
features/io.github.liaoalia.kiroacp.feature
releng/io.github.liaoalia.kiroacp.product   dev product (Eclipse + plugin)
releng/io.github.liaoalia.kiroacp.site      p2 update site
```

## Development Approach
- **Testing approach**: Regular (code first, then tests). The protocol layer is tested against the fake agent replaying recordings, so tests need no Kiro login.
- Every task that changes code adds or updates tests; all tests pass before the next task.
- `core` must not import `org.eclipse.swt`, `org.eclipse.jface` or `org.eclipse.ui`.
- The message protocol is the contract: for every `ToWebview` / `FromWebview` type there is a Java model and a JSON round-trip test against a sample produced from the reference types.
- All SWT access goes through the UI thread; agent I/O never runs on it.
- Parallel execution: tasks in the same wave touch disjoint packages (listed per task) and may run as parallel subagents.
- Never send keystrokes or mouse input to the desktop when checking the running product; capture its window only.
- Update this plan when scope changes (➕ for discovered tasks, ⚠️ for blockers). Record non-obvious choices in `docs/DECISIONS.md`.

## Validation Commands
Run from the repository root, with `JAVA_HOME` pointing at a JDK 21 or newer (on this machine `D:\Work\Java\openjdk-23.0.2`; CI sets it with `actions/setup-java`):
- `npm --prefix webview ci` then `npm --prefix webview run build` and `npm --prefix webview run typecheck` (after re-vendoring also `npm --prefix webview run samples`)
- `npm --prefix tests/fixtures/fake-agent ci` (from the task that adds the fake agent onwards; the tests that start it fail without it)
- `mvn -B -ntp verify`

## Implementation Steps

### Wave 0 (serial): foundation

### Task 1: Scaffold, webview vendoring and the Browser spike
- [x] parent `pom.xml` with Tycho 5.0.3, `.mvn/settings.xml`, `target-platform/kiro.target`
- [x] `webview/`: `sync.mjs` (vendor from the reference), `build.mjs` (esbuild into the UI bundle), `host/bridge.js`, `host/theme.css`, `host/index.html`
- [x] UI bundle with the "Kiro Chat" view, `WebChat` (Browser + bridge + theme) and a `DemoHost` with canned replies
- [x] feature and dev product; the product starts and the view renders a state snapshot
- [x] `ui.tests` test bundle (JUnit 5) with the `ChatPageLocation` tests
- [x] the chat page cannot navigate: `LocationListener` veto, no new windows, bridge active only on the page, web links opened outside by the host (found by review round 01)
- [x] `core` bundle and `core.tests` test bundle (`NdjsonCodec` and its tests)
- [x] `tests/fixtures/sync.mjs` copies `test/fake-agent/agent.mjs` and `test/recordings/` from the reference; `tests/fixtures/fake-agent/package.json` pins `@agentclientprotocol/sdk`; `FakeAgentTest` starts the fake agent with `node` and reads its `initialize` response
- [x] `ui.tests`: `WebChat` in a `Shell` that is never opened receives `ready` and renders a posted `state` (asserted through `Browser.evaluate` on the DOM); a `prompt` typed through the DOM reaches the host
- [x] p2 update site module (Jackson travels in the feature); bundles and site resolve for win32, linux-gtk (x86_64, aarch64) and macosx (x86_64, aarch64), the dev product for win32 only; GitHub Actions workflow running the validation commands on `windows-latest`
- [x] `scripts/run-dev.ps1` documented in `README.md`; view icon as PNG drawn by `scripts/make-icon.py` (4.32 has no SVG icon support); `LICENSE`
- [x] run the validation commands - must pass before task 2
- ⚠️ the workflow has not run yet: there is no remote. Whether the `windows-latest` image has the WebView2 runtime that `WebChatTest` needs is unverified.

### Task 2: Protocol spike - choose the ACP client implementation
Owns: `core/.../acp/rpc/`, `docs/DECISIONS.md`. Outcome: own client (DECISIONS D1, D2); the SDK was examined from its sources and with a probe outside this repository, not added to the target platform.
- [x] examine `acp-java-sdk` (0.11.0 is a parent POM; the client is `acp-core`): dependencies, OSGi metadata, client API, both hazards; 0.81.0 compared
- [x] `JsonRpcConnection` (NDJSON JSON-RPC over two streams, messages as JSON trees) with `JsonRpcConnectionTest`
- [x] against the fake agent: `initialize`, `session/new`, a streamed prompt (`chat` recording), a permission round-trip, `fs/read_text_file`, `terminal/create` (`tour` recording)
- [x] hazard 1: an unknown `sessionUpdate` kind and a `_kiro/*` notification reach our code instead of being rejected or dropped
- [x] hazard 2: every `agent_message_chunk` is delivered before the `session/prompt` response resolves (`slow` recording, five runs)
- [x] unknown agent requests are answered with `-32601`; `_meta` fields survive
- [x] write the decision in `docs/DECISIONS.md`; the checks stay as `FakeAgentProtocolTest`
- [x] run the validation commands - must pass before task 3

### Task 3: Contracts
Owns: `core/.../acp/api/`, `core/.../model/`, `core/.../host/`.
- [x] `acp/api`: `KiroAgent`, `SessionState`, `SessionEvent`, `ConnectionStateChange`, `AgentInfo`, `ClientServices`, `AcpException`, mirroring the reference `src/acp/types.ts`; ACP payloads stay `JsonNode` (DECISIONS D1), results are `CompletableFuture`s
- [x] `model`: Java records for every type in `protocol.ts` (`UiState`, `ToWebview` and `FromWebview` variants, view models) with Jackson mapping; `UiJson` converts
- [x] `host`: interfaces the core needs from Eclipse (`Workspace`, `Settings`, `Logger`, `UiScheduler`, `ChatPage`, `Subscription`) so the controller is testable with fakes
- [x] `webview/samples.ts` emits samples for every `ToWebview` / `FromWebview` variant into `tests/fixtures/protocol-samples.json`, type-checked against the vendored `protocol.ts` (a mapped type fails the build when a variant is missing); `ProtocolSamplesTest` round-trips every sample through the Java models
- [x] run the validation commands - must pass before wave 1

### Wave 1 (parallel): MVP building blocks

### Task 4: ACP client
Owns: `core/.../acp/impl/`, `core/.../acp/process/`. Reference: `src/acp/client.ts`, `process.ts`, `stream.ts`, D3, D5, D19.
- [x] process launch: resolve the CLI path (PATH plus default install dirs), `.cmd`/`.bat` through `cmd.exe`, inherited environment, stderr to the log, exit and crash handling
- [x] connection: `initialize` with client capabilities (fs read/write, terminal, `auth.terminal`, `_meta.kiro.openExternalUrl`), capture agent capabilities, auth methods and `_meta.kiro.extensionMethods`
- [x] sessions: new, list, load (history flagged as replay), prompt, cancel (notification plus `cancelled` outcome for pending permissions), set mode, set config option, set model, authenticate, extension calls; session events in wire order
- [x] one agent process shared by many sessions; restart
- [x] tests against the fake agent, ported from `test/acp-client.test.ts` and `test/process.test.ts`
- [x] run the validation commands (full build on `main` after the merge, three runs)

### Task 5: Transcript reducer
Owns: `core/.../pure/transcript/`. Reference: `src/features/pure/transcript.ts`, `test/transcript.test.ts`.
- [x] reducer from session updates to the transcript model (messages, thoughts, tool calls with diff and terminal content, plan, modes, config options, commands); unknown kinds are logged, not fatal (the reducer is pure: it returns the reason in `ApplyResult.ignored()` and the caller logs it)
- [x] port every case of `test/transcript.test.ts`; replay each recording through the reducer without errors
- [x] run the validation commands (full build on `main` after the merge; the `pure.*` packages are exported from the core bundle)

### Task 6: Pure logic needed by the MVP
Owns: `core/.../pure/permission/`, `core/.../pure/output/`, `core/.../pure/text/`. Reference: `permissionPolicy.ts`, `outputBuffer.ts`, `textRange.ts`, `terminalPresentation.ts` and their tests.
- [x] permission policy with the three scopes and kind resolution (D6, D23)
- [x] output buffer with a byte limit truncating from the start on a character boundary (D9)
- [x] text range slicing; terminal presentation
- [x] port the cases of the four matching test files
- [x] run the validation commands (full build on `main` after the merge; the `pure.*` packages are exported from the core bundle)

### Task 7: Chat page hardening
Owns: `ui/.../chat/WebChat.java`, `webview/host/`.
- [x] clipboard: `navigator.clipboard.writeText` exists on the `file:` page in WebView2 (secure context, permission `granted`), but the document of a browser in an unopened shell has no focus and Chromium refuses the call without it; the shim replaces `writeText` with a `host:copy` message handled with SWT `Clipboard` and answered to the page (DECISIONS D4)
- [x] links: `ui.tests` clicking a web link through the DOM asserts the page did not navigate and the host was asked to open exactly that URL; other schemes are neither followed nor opened
- [x] theme: re-applied when JFace's colour or font registry, the workbench theme or the system settings change; link, error, secondary text, input, selection and border colours mapped where they fit the view (`ChatTheme`, DECISIONS D5); light and dark verified in the page
- [x] persistence: the shim's `localStorage` state (keyed by the per-instance id from the view memento, passed as the `view` URL parameter) survives a reload and a second browser widget with the same id, and is separate for another id
- [x] page failure: a plain SWT message with a "Reload" link when the browser cannot be created, the page cannot be located or its file is missing
- [x] focus: `setFocus` posts `focusInput`, also when asked before the page is up
- [x] `ui.tests` for clipboard, link veto, theme application, persistence, page failure and focus
- [x] run the validation commands for what this task touches: webview build and typecheck, the `ui` bundle and `ui.tests` (43 tests). In this worktree the full `mvn verify` stops at `core.tests`, whose `FakeAgentProtocolTest` needs `npm ci` in `tests/fixtures/fake-agent`, not installed here
- ⚠️ the native `navigator.clipboard.writeText` was never called in a test: had it worked, it would have overwritten the clipboard of whoever sits at the machine. That it fails without focus is Chromium's documented rule, not an observation here.
- ⚠️ the e4 theme-changed event (`org/eclipse/e4/ui/css/swt/theme/ThemeManager/themeChanged`) is not subscribed to: it needs `org.osgi.service.event` (and `IEventBroker` from e4), which the bundle does not import. A theme switch is noticed through the registries it changes; a theme that changed widget colours without touching either registry would be picked up only at the next reload.
- ⚠️ whether `localStorage` survives a restart of Eclipse is not testable in one test process (the WebView2 user data folder must be the same and flushed); check by hand. The host-side fallback (state through the view memento) was therefore not built.
- ⚠️ needs a person at the keyboard: `Ctrl+C/V/X/A/Z` inside the composer against Eclipse's key bindings, the caret really blinking in the input after the view is activated, the look of both themes in a running Eclipse, and the failure message on a machine without the WebView2 runtime.

### Wave 2 (serial after wave 1): MVP

### Task 8: Host services
Owns: `core/.../services/` (logic, no Eclipse), `ui/.../services/`, `ui/.../prefs/` (Eclipse adapters). Reference: `src/features/fs.ts`, `terminal.ts`, `permissions.ts`, `services.ts`, D8, D9. Split so the logic is tested without Eclipse: everything that needs the IDE sits behind `TextFiles`, `TerminalDisplay` and `ExternalActions`.
- [x] core `FsService`: `fs/read_text_file` with line/limit, `fs/write_text_file`; absolute paths only; a write outside the workspace roots asks first; `DiskTextFiles` as the disk-only `TextFiles`
- [x] core `TerminalService`: `ProcessBuilder` through the platform shell, retained output via the Task 6 buffer, output/wait/kill/release semantics, process-tree kill, per-session kill; agent terminals take no input (stdin closed)
- [x] core `PermissionManager`: pending-request registry, session policies in three scopes, resolution from the chat card, cancel-all on turn cancel
- [x] core `HostServices` composes them into `ClientServices` (`_kiro/openExternalUrl` and terminal sign-in through `ExternalActions`)
- [x] ui `host` implementations: `EclipseWorkspace`, `PreferenceSettings` with the "Kiro ACP Chat" preference page, `EclipseLogger` (a "Kiro ACP" console plus the Eclipse log), `DisplayScheduler`
- [x] tests: fs against a temporary directory; terminal echo, exit code, kill, byte limit; permission registry; the fake agent's `tour` through the real services; workspace, settings, logger, scheduler and preference page in `ui.tests`
- [x] run the validation commands (full build on `main` after the merge, two runs)
- [x] ➕ ui adapters, done with Task 9: `TextFiles` over open editors and `IFile`, `TerminalDisplay` over `MessageConsole`, `ExternalActions` (confirm and open a URL; sign-in in a terminal)
- ⚠️ unverified: nothing ran on Linux/macOS (signal mapping, POSIX tree kill); the "Kiro ACP" console and the active-editor tracking need a running workbench and were not exercised; the preference page was never seen in the real dialog

### Task 9: Chat controller and view wiring
Owns: `core/.../session/`, `ui/.../chat/ChatView.java`, `ui/.../chat/KiroChat.java`, `ui/.../handlers/`, `plugin.xml`. Reference: `src/ui/chatController.ts`, `src/extension.ts`.
- [x] controller in `core`: owns the agent, sessions per view, transcript state, turn lifecycle, local echo of user messages (D13); answers `ready` with a `state` snapshot and emits incremental `ToWebview` messages. Stop follows the reference (D27): permissions cancelled and terminals killed at once, the turn ends when the agent answers or after a 5 s watchdog
- [x] handles the MVP `FromWebview` messages: `prompt`, `cancel`, `newSession`, `selectSession`, `closeSession`, `permissionResponse`, `permissionAlways`, `setMode`, `setConfigOption`, `setModel`, `restart`, `showLog`, `openFile`, `showTerminal`; everything else goes to handlers registered through `ChatHooks` (the seams for wave 3)
- [x] ui adapters: `EclipseTextFiles` (file buffers), `ConsoleTerminalDisplay`, `EclipseExternalActions` (confirm and open a URL; sign-in in an external terminal window through a generated script)
- [x] `KiroChat` holds one controller per workbench; `ChatView` binds it to `WebChat`; `DemoHost` is deleted
- [x] commands: New Session, Cancel Current Turn, Restart Agent Process, Show Protocol Log, Focus Chat; preference page (Task 8)
- [x] controller tests in `core.tests` with the fake agent and fake hosts: the message sequence for a chat turn, the tour, cancel, permission allow/reject and policies, restart after crash, sessions
- [x] smoke check by hand: the dev product with the CLI path pointed at the fake agent connects and shows the session (window capture, 2026-11-12)
- [x] `ui.tests`: the view against the fake agent, a prompt typed through the DOM, the streamed reply and a tool card present in the DOM (`ChatEndToEndTest`, see Task 10)
- [x] ➕ `ChatPageLink`: what joins a `WebChat` to the controller, taken out of `ChatView` so that the test runs the same code; `KiroChat.services` builds the Eclipse host for either
- [x] run the validation commands
- ⚠️ not exercised in a running Eclipse: a prompt and its reply, permission cards, agent terminals in the Console view, opening files, the commands and their menu entries, the sign-in window, a real `kiro-cli`

### Task 10: Verify the MVP
- [x] streaming chat, tool cards, cancel, new session, permissions, fs read/write and terminal all pass in the UI test run against the fake agent: `ChatEndToEndTest`, the real page in one browser widget per test, `ChatPageLink`, a real controller on the Eclipse host (`KiroChat.services`) and the fake agent as a process; everything typed and clicked through the DOM and read from the DOM or the disk. Runs on CI too
- [x] `core` has no SWT/JFace/UI imports (build check): `CoreIndependenceTest` in `core.tests` reads the core's sources and manifest; imports only from the JDK's base packages, Jackson and the core itself, and no Eclipse, OSGi, AWT or Swing name in code. Seen failing with a `java.awt` import put into the core for the purpose
- [x] run the validation commands
- ⚠️ the test runtime has no workbench: `ChatView` itself, the Console view, opening a file from a tool card and "Open diff" are not in the test; the agent's request to open a URL is refused by the test's `Prompts`, the dialog is never shown. That `fs/read_text_file` was served is asserted on the host's answer: the fake agent does not show what it read
- ⚠️ a test must empty the display before each look at the page (`await` in `ChatEndToEndTest`): SWT runs `asyncExec` work only when no window-system message waits, and `Browser.evaluate` leaves some behind every time. Looking after every single event delayed the chat's own messages by up to 4 s (Stop arrived after the 2 s turn had ended, 8 of 15 runs). `WebChatTest` still waits the old way; nothing there depends on time, it is only slower than it need be. Whether a busy page can hold back the controller the same way in a running Eclipse was not measured

- [x] ➕ review of Tasks 2-10 (revmux task `since-e740fa1`, 2026-11-13): round 02 found the agent's commands outliving their session or agent, a project selected in a view losing to the open editor, and a permission card that could overtake its tool call; round 03 found, in those fixes, a command still starting for a closed session and processes ended on the display thread; round 04 (`final`) came back clean. The codex agent did real work in round 03 only. Released as 0.1.2

### Wave 3 (parallel): parity features
Each task owns one package in `core` and one in `ui`, handles its own `FromWebview` messages in a separate handler class registered with the controller, and is merged one at a time.

### Task 11: Diff review in the Compare editor
Owns: `core/.../pure/diff/`, `ui/.../diff/`. Reference: `src/features/diff.ts`, `pure/diffState.ts`, D7.
- [x] diff bookkeeping port with the `features-diffState` test cases
- [x] `openDiff` opens a `CompareEditorInput` (original vs. proposed, proposed side editable) with Accept and Reject in its toolbar; both resolve the pending permission, as do the chat card buttons
- [x] the edited proposed text is what gets written; `autoOpenDiffs` preference; compare editors close on resolution
- [x] tests ported from `test/e2e/f2-diff.e2e.ts`
- [x] run the validation commands
- ⚠️ `ui/diff/CompareDiffEditors` (opening, finding and closing the compare editor in the workbench) is executed by no test; the compare input itself is tested in a shell that is never opened. Owed: one look in a running Eclipse, also at a `.java` file with JDT's compare viewer
- ➕ not ported: the reference's `acceptDiff` / `rejectDiff` commands; Accept and Reject are buttons above the compare panes

### Task 12: File, selection and image context
Owns: `core/.../pure/context/`, `ui/.../context/`. Reference: `src/features/context.ts`, `pure/contentBlocks.ts`, `pure/fileSearch.ts`, `src/ui/images.ts`, D10.
- [x] content block builders and file search ranking with the reference test cases
- [x] `queryFiles` → `fileSuggestions`, `removeChip`, active-editor chip (`attachActiveFile`), "Send Selection to Kiro Chat" command with `Ctrl+Alt+K`
- [x] `pickImages` and `dropFiles` → `imagesAttached`, with the reference size and type limits
- [x] tests: selection chip block, suggestion list for a sample project, image rejection reasons
- [x] run the validation commands
- ⚠️ dropping files from the OS or an Eclipse view onto the chat: the host side of `dropFiles` is there and tested, but the embedded page most likely never sends it (a Chromium page gets such a drop as file bytes without a path, which the page handles itself for images and refuses for other files). Not verified in a running Eclipse; `@` is the way to attach a file
- ⚠️ executed by no test: `WorkbenchEditorContext` against a real editor, `ImageFileDialog.open`, `SendSelectionHandler.execute`, the editor context-menu entry
- ➕ not ported here, left to their tasks: F16.5 and F16.10 (slash commands), F16.8 and F16.9 (steering and queue)

### Task 13: Sessions and history
Owns: `core/.../session/history/`, `ui/.../sessions/`. Reference: D19–D21, D24, `f10-sessions`, `f11-history` e2e.
- [x] `selectSession`, `closeSession`, `sessions` updates; closing the view ends its sessions
- [x] `browseSessions`: a JFace dialog over `session/list`, then `session/load` replayed into the transcript
- [x] remember mode, model and config options across sessions; "Forget Remembered Session Options" command
- [x] tests ported from the two e2e files
- [x] run the validation commands
- ⚠️ executed by no test: opening the past-sessions dialog (`DialogSessionPicker.pick`) and the message of the Forget command; the dialog's content and filter are tested without opening it
- ➕ not ported: a workbench command "Open Past Session…" (the page's history button opens the dialog); the `/capabilities` line about remembered options (Task 16); restoring a view's sessions after a sign-in restart (Task 15)
- ⚠️ unverified: whether the real `kiro-cli` honours the `_meta.kiro` hints on `session/new` (the fake agent ignores them, as in the reference)

### Task 14: Steering and queued prompts
Owns: `core/.../session/steering/`. Reference: D22, `f12-steering` e2e.
- [ ] a prompt during a turn is steered when the agent advertises it, else queued; `messageDelivery` updates
- [ ] tests with the `slow-steer` and `slow` recordings
- [ ] run the validation commands

### Task 15: Sign-in flow
Owns: `core/.../session/auth/`, `ui/.../auth/`. Reference: D25, `src/features/services.ts`, `f14-login` e2e.
- [ ] sign-in offered from any "not logged in" failure; signed out means `logout` then one fresh terminal sign-in, never a token refresh
- [ ] `login` runs the CLI in a terminal the user can type into (TM Terminal if installed, otherwise an external terminal window); `_kiro/openExternalUrl` opens the system browser after confirming the host
- [ ] tests with the fake agent's `--auth-required`
- [x] ➕ done ahead of the rest (0.1.0): the page's sign-in button runs the agent's terminal method in a system terminal window and restarts the agent afterwards (`core/session/SignInFlow`, `ui/services/SignInTerminal`), with `SignInFlowTest` and `SignInTerminalTest`; a URL the agent asks to open goes to the system browser after a confirmation
- [ ] run the validation commands

### Task 16: Slash commands, usage and plan document
Owns: `core/.../pure/slash/`, `core/.../pure/usage/`, `core/.../pure/plan/`, `ui/.../plan/`. Reference: D11, D18, D26.
- [ ] client-side slash command routing; `usage` messages; both with the reference test cases
- [ ] `openPlan`: the plan as markdown in a read-only editor that updates live; Approve/Reject for `switch_mode` requests
- [ ] run the validation commands

### Wave 4 (serial): finish

### Task 17: Verify acceptance criteria
- [ ] every `FromWebview` type is handled and every `ToWebview` type is emitted somewhere (a test enumerates the vendored protocol samples)
- [ ] every row of the reference verification table has a passing automated test here against the fake agent
- [ ] every setting and command of the reference `package.json` is implemented or listed as not ported in `docs/DECISIONS.md`
- [ ] re-vendoring the webview from the current reference still builds and passes
- [ ] run the validation commands

### Task 18: Packaging and documentation
- [ ] feature metadata, licence (MIT), update site categories; CI uploads the site and attaches it to a release on a `v*` tag
- [ ] `README.md`: install, settings, commands, pointing the plugin at the fake agent, how to re-vendor the webview
- [ ] `docs/protocol-notes.md`: what was verified with the Java client
- [ ] run the validation commands

## Technical Details
- Page bridge: page → host is `window.__kiroHost(json)` (a `BrowserFunction`, handled with `asyncExec` so the browser is never re-entered from its own callback); host → page is `Browser.execute("window.__kiroReceive(<json>)")`, which dispatches a `message` event like VS Code does.
- Threading: one reader thread per agent process parses NDJSON and hands events over in wire order to the controller's thread, which in Eclipse is the SWT display thread (`DisplayScheduler`; a plain single-thread executor in tests). The controller reduces events there and posts one message per event to the page (no coalescing of `chunk` bursts, see D6).
- Fake agent in tests: `node tests/fixtures/fake-agent/agent.mjs acp --scenario <name>`; it imports `@agentclientprotocol/sdk`, so the fixtures folder has its own `package.json` and `npm ci` runs before the tests.

## Post-Completion
**Manual verification**
- Install the update site into a real Eclipse (2024-06 and current), light and dark theme; walk through chat, diff accept with an edit, terminal, sign-in.
- Run against a real `kiro-cli acp` after `kiro-cli login`.
- Linux (WebKitGTK) and macOS (WKWebView): the page, the bridge and `file:` loading are only verified on Windows/WebView2.

**External**
- Create the GitHub repository and push; decide whether to publish to the Eclipse Marketplace.
