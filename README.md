# Kiro ACP Chat for Eclipse

Chat with the Kiro agent inside Eclipse over the Agent Client Protocol (ACP). The plugin starts `kiro-cli acp` as a local process and talks to it over stdio; the plugin itself makes no network calls.

It is a port of the VS Code extension of the same name. The chat page is that extension's webview, unchanged, shown in an SWT `Browser`; the host side is Java.

**Status: early.** The features of the VS Code extension are ported and tested against the test agent, with the gaps the plan lists (among them dropping files onto the chat and a few commands): chat with streaming, tool cards, permissions, file access, agent terminals, modes and models, diff review in the compare editor, file, selection and image context (`@`; `Ctrl+Alt+K` sends the selection), past sessions, prompts queued during a turn, sign-in, slash commands, usage and the plan document. With a real `kiro-cli` 3.2 on Windows, signing in and plain conversations were tried by hand. The parts that need a running Eclipse (compare and plan editors, dialogs, commands, menus) were added after that and have not been seen in one yet; Linux and macOS have never been run. The plan is in `docs/plans/`.

## Requirements

- Eclipse 2024-06 (4.32) or later, running on Java 17 or later.
- Windows: the WebView2 runtime (part of Windows 11). Linux and macOS builds exist but are untested.
- Eclipse and the plugin installed on a local drive. From a network share the view shows a message instead of the chat.

## Install

1. Download `kiro-acp-eclipse-<version>.zip` from the repository's Releases page. Do not unpack it.
2. In Eclipse: *Help > Install New Software… > Add… > Archive…*, choose the zip, *Add*.
3. Tick *Kiro ACP Chat*, then *Next* and *Finish*. The plugin is not signed, so Eclipse asks whether to trust unsigned content: select it and confirm. Restart when asked.
4. Open the view with *Window > Show View > Other… > Kiro > Kiro Chat*. Settings are under *Window > Preferences > Kiro ACP Chat*.

To update, install a newer zip the same way. To remove: *Help > About Eclipse IDE > Installation Details*, select *Kiro ACP Chat*, *Uninstall…*.

A release is made by pushing a tag: `git tag v0.2.1 && git push origin v0.2.1` runs `.github/workflows/release.yml`, which builds, tests and attaches the zip.

## Settings

*Window > Preferences > Kiro ACP Chat*. They are the settings of the VS Code extension, with the same defaults.

| Setting | Default | What it does |
|---|---|---|
| Kiro CLI | `kiro-cli` | The command or path of the CLI. A bare name is looked up on `PATH` and where the installers put it. |
| Extra arguments | empty | Added after `acp`, for example `--agent-engine=v3`. |
| Attach the active file | off | Every prompt carries the file of the active editor as a chip. |
| Open proposed changes | when a permission is required | Also: always, never. |
| Terminal | one console per session | Also: one per command, hidden. Agent commands show in the Console view. |
| Terminal output limit | 1048576 bytes | What is kept of a command's output for the agent. |
| Log the protocol | off | Every message to and from the agent goes to the "Kiro ACP" console. |
| Show mode and model pickers | off | The page starts with its settings row open. |
| Session list collapsed | off | The page starts with its session list folded. |
| Remember session options | on | Mode, model and options chosen in one session are given to the next. |

A changed CLI path or arguments apply when the agent is next started (*Restart Agent Process*).

## Commands

In the chat view's menu and toolbar, and through *Quick Access* (`Ctrl+3`):

| Command | |
|---|---|
| Focus Kiro Chat | Opens or activates the chat view. |
| New Kiro Session | A fresh session in the chat view. |
| New Kiro Chat View | One more chat view; each view has its own sessions. |
| Cancel Current Turn | |
| Restart Agent Process | |
| Log In to Kiro | Runs the CLI's sign-in in a terminal window. |
| Send Selection to Kiro Chat | `Ctrl+Alt+K` in a text editor (`Cmd+Alt+K` on a Mac), also in the editor's context menu. |
| Open Plan, Approve Plan, Reject Plan | The agent's plan as a read-only document. |
| Show Protocol Log | |
| Forget Remembered Session Options | |

Not ported from the extension: the chat as an editor tab (`chatLocation`), the Accept/Reject commands of a proposed change (they are buttons above the compare panes), "Open Past Session" as a command (the chat's history button opens the list), dropping files onto the chat (use `@`), and the experimental agent link. `docs/DECISIONS.md` has the reasons.

## Running it against the test agent

The tests talk to a fake agent that replays recorded sessions; the plugin can be pointed at it by hand. Set *Kiro CLI* to the wrapper next to it, as an absolute path:

- Windows: `<repository>\tests\fixtures\fake-agent\kiro-cli-fake.cmd`
- elsewhere: `<repository>/tests/fixtures/fake-agent/kiro-cli-fake`

and restart the agent. `npm --prefix tests/fixtures/fake-agent ci` must have been run once, and `node` must be on `PATH`. A prompt of the form `/scenario tour` plays the recording `tests/fixtures/recordings/tour.json`; other prompts get a short canned answer. *Extra arguments* take the fake agent's flags, for example `--auth-required`.

## Build

Needs Node 24, Maven 3.9 and a JDK 21 or newer in `JAVA_HOME` (Tycho 5 does not run on older ones; the bundles still target Java 17).

```
npm --prefix webview ci
npm --prefix webview run build
npm --prefix tests/fixtures/fake-agent ci
mvn -B -ntp verify
```

- The update site is `releng/io.github.liaoalia.kiroacp.site/target/repository`. Install it through *Help > Install New Software… > Add… > Local…*.
- `.mvn/settings.xml` makes the build use Maven Central whatever the machine's global Maven settings say.
- The tests start a real browser widget in a window that is never shown, and the fake agent with `node`.

## Try it in a throwaway Eclipse

`scripts\run-dev.ps1` builds everything and starts a development product (Eclipse Platform plus the plugin) with its workspace in `.dev-workspace`. Windows only.

```
$env:KIROACP_BUILD_JDK = "C:\path\to\jdk-21-or-newer"
$env:KIROACP_RUN_JDK = "C:\path\to\jdk-17-or-newer"   # optional, defaults to the build JDK
scripts\run-dev.ps1            # add -SkipBuild to start the last build again
```

Without `KIROACP_BUILD_JDK` the script uses `JAVA_HOME`. Close that Eclipse before building again: it locks the files the build replaces.

## Files taken from the VS Code extension

Both sets are copied verbatim by a script and never edited here.

| What | Where | Refresh |
|---|---|---|
| The chat webview | `webview/vendor/` | `npm --prefix webview run sync` |
| The fake agent and its recordings | `tests/fixtures/` | `node tests/fixtures/sync.mjs` |

Both scripts copy the reference's working tree, so run them against a checkout without uncommitted changes. After a sync, `npm --prefix webview run samples` regenerates `tests/fixtures/protocol-samples.json`; if the page protocol changed, the Java records in `core/model` have to follow, and `PageProtocolCoverageTest` fails until every new message type has a handler.

Each script takes the path of the extension's repository as an argument and defaults to `../vs-code-kiro-plugin`. Eclipse-specific page code lives in `webview/host/`.

## Licence

MIT, see `LICENSE`.
