# Kiro ACP Chat for Eclipse

Chat with the Kiro agent inside Eclipse over the Agent Client Protocol (ACP). The plugin starts `kiro-cli acp` as a local process and talks to it over stdio; the plugin itself makes no network calls.

It is a port of the VS Code extension of the same name. The chat page is that extension's webview, unchanged, shown in an SWT `Browser`; the host side is Java.

**Status: early.** Chat with streaming, tool cards, permissions, file access, agent terminals, modes and models work against the test agent. Not ported yet: diff review, file and image context, session history, steering, slash commands, the sign-in button. It has not been run against a real `kiro-cli` yet. The plan is in `docs/plans/`.

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

A release is made by pushing a tag: `git tag v0.1.0 && git push origin v0.1.0` runs `.github/workflows/release.yml`, which builds, tests and attaches the zip.

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

Each script takes the path of the extension's repository as an argument and defaults to `../vs-code-kiro-plugin`. Eclipse-specific page code lives in `webview/host/`.

## Licence

MIT, see `LICENSE`.
