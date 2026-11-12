// Copies the fake agent and its recordings from the reference VS Code extension.
//   node tests/fixtures/sync.mjs [path to the reference repository]
// The copies are verbatim: change them in the reference, then sync again.
import { cpSync, existsSync, mkdirSync, readFileSync, readdirSync, rmSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const source = resolve(process.argv[2] ?? join(here, "..", "..", "..", "vs-code-kiro-plugin"));
const agent = join(source, "test", "fake-agent", "agent.mjs");
const recordings = join(source, "test", "recordings");
if (!existsSync(agent) || !existsSync(recordings)) {
  console.error(`Not the reference repository (no test/fake-agent/agent.mjs or test/recordings): ${source}`);
  process.exit(1);
}

mkdirSync(join(here, "fake-agent"), { recursive: true });
cpSync(agent, join(here, "fake-agent", "agent.mjs"));
// Recordings deleted in the reference must not linger here.
rmSync(join(here, "recordings"), { recursive: true, force: true });
cpSync(recordings, join(here, "recordings"), { recursive: true });

const wanted = JSON.parse(readFileSync(join(source, "package.json"), "utf8"));
const sdk = wanted.dependencies?.["@agentclientprotocol/sdk"] ?? wanted.devDependencies?.["@agentclientprotocol/sdk"];
const pinned = JSON.parse(readFileSync(join(here, "fake-agent", "package.json"), "utf8")).dependencies["@agentclientprotocol/sdk"];
console.log(`fake agent and ${readdirSync(join(here, "recordings")).length} recordings copied from ${source}`);
if (sdk !== pinned) {
  console.warn(`The reference uses @agentclientprotocol/sdk ${sdk}, tests/fixtures/fake-agent/package.json pins ${pinned}: update it and run npm install there.`);
}
