// Bundles the vendored chat webview and the Eclipse host shim into the UI bundle's web/ folder.
import * as esbuild from "esbuild";
import { cpSync, mkdirSync, rmSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const out = join(here, "..", "bundles", "io.github.liaoalia.kiroacp.ui", "web");

rmSync(out, { recursive: true, force: true });
mkdirSync(out, { recursive: true });

await esbuild.build({
  entryPoints: [join(here, "vendor", "src", "webview", "main.ts")],
  bundle: true,
  platform: "browser",
  target: "es2022",
  format: "iife",
  outfile: join(out, "webview.js"),
  sourcemap: false,
  minify: false,
  logLevel: "info",
  tsconfig: join(here, "tsconfig.json"),
});

cpSync(join(here, "vendor", "media", "chat.css"), join(out, "chat.css"));
for (const f of ["index.html", "bridge.js", "theme.css"]) cpSync(join(here, "host", f), join(out, f));
