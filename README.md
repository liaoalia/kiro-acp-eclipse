# Kiro ACP Chat for Eclipse

Chat with the Kiro agent inside Eclipse over the Agent Client Protocol (ACP). The plugin starts `kiro-cli acp` as a local process and talks to it over stdio; the plugin itself makes no network calls.

It is a port of the VS Code extension of the same name. The chat page is that extension's webview, unchanged, shown in an SWT `Browser`; the host side is Java.

**Status: early.** The chat view renders and exchanges messages with a stand-in host that gives canned replies. There is no agent behind it yet. The plan is in `docs/plans/`.

## Requirements

- Eclipse 2024-06 (4.32) or later, running on Java 17 or later.
- Windows: the WebView2 runtime (part of Windows 11). Linux and macOS builds exist but are untested.
- Eclipse and the plugin installed on a local drive. From a network share the view shows a message instead of the chat.

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
