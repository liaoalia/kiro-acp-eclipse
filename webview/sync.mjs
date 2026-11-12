// Copies the chat webview sources verbatim from the VS Code extension repository.
// The files under vendor/ are never edited here; change them in the source repo and re-run.
//   node sync.mjs [path-to-vs-code-kiro-plugin]
import { cpSync, existsSync, mkdirSync, readFileSync, renameSync, rmSync, writeFileSync } from "node:fs";
import { execFileSync } from "node:child_process";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const source = resolve(process.argv[2] ?? join(here, "..", "..", "vs-code-kiro-plugin"));
const vendor = join(here, "vendor");

const required = [
  join("src", "webview", "main.ts"),
  join("src", "ui", "protocol.ts"),
  join("src", "ui", "images.ts"),
  join("media", "chat.css"),
  "package.json",
];
const missing = required.filter((f) => !existsSync(join(source, f)));
if (missing.length > 0) {
  console.error(`not a kiro-acp-vscode checkout: ${source}\n  missing: ${missing.join(", ")}`);
  process.exit(1);
}

// Copy into a staging folder first: vendor/ is only replaced by a complete copy.
const staging = join(here, "vendor.tmp");
rmSync(staging, { recursive: true, force: true });
mkdirSync(join(staging, "src", "ui"), { recursive: true });
cpSync(join(source, "src", "webview"), join(staging, "src", "webview"), { recursive: true });
for (const f of ["protocol.ts", "images.ts"]) cpSync(join(source, "src", "ui", f), join(staging, "src", "ui", f));
mkdirSync(join(staging, "media"), { recursive: true });
cpSync(join(source, "media", "chat.css"), join(staging, "media", "chat.css"));

const version = JSON.parse(readFileSync(join(source, "package.json"), "utf8")).version;
let commit = "unknown";
try {
  commit = execFileSync("git", ["-C", source, "rev-parse", "HEAD"], { encoding: "utf8" }).trim();
} catch {
  // not a git checkout
}
writeFileSync(
  join(staging, "VENDORED.json"),
  JSON.stringify({ from: "kiro-acp-vscode", version, commit }, null, 2) + "\n",
);
// Swap by renaming, so vendor/ is whole at every step: the old copy is moved
// aside, put back if the new one cannot take its place, and only then removed.
const previous = join(here, "vendor.old");
rmSync(previous, { recursive: true, force: true });
const hadVendor = existsSync(vendor);
if (hadVendor) renameSync(vendor, previous);
try {
  renameSync(staging, vendor);
} catch (error) {
  if (hadVendor) renameSync(previous, vendor);
  throw error;
}
rmSync(previous, { recursive: true, force: true });
console.log(`vendored webview ${version} (${commit.slice(0, 7)})`);
