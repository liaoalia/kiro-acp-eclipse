// One or more sample messages for every variant of the chat page protocol.
//
//   npm --prefix webview run samples     (writes tests/fixtures/protocol-samples.json)
//
// The samples are type-checked against the vendored protocol.ts (`npm run
// typecheck`): the mapped types below fail to compile when a variant is added,
// removed or changed in the reference, which is the cue to update the Java
// models in core/model. The Java tests round-trip every sample.
//
// Conventions the Java models rely on: an optional field is either present
// with a value or absent, never an explicit null; a required field that may be
// null (`sessionId`, `modes`, `oldText`, ...) appears as null at least once.
import type {
  ContextChip,
  FromWebview,
  ImageAttachment,
  PermissionView,
  SessionUsage,
  ToWebview,
  ToolCallView,
  UiState,
} from "./vendor/src/ui/protocol";

type Samples<M extends { type: string }> = { [K in M["type"]]: Array<Extract<M, { type: K }>> };

const chipFile: ContextChip = { id: "c1", kind: "file", path: "/work/app/src/app.ts", label: "src/app.ts", implicit: true };
const chipSelection: ContextChip = {
  id: "c2",
  kind: "selection",
  path: "/work/app/src/app.ts",
  label: "src/app.ts:3-9",
  startLine: 3,
  endLine: 9,
  implicit: false,
};

const image: ImageAttachment = {
  id: "i1",
  name: "shot.png",
  mimeType: "image/png",
  data: "iVBORw0KGgo=",
  size: 8,
  path: "/work/app/shot.png",
  preview: "data:image/png;base64,iVBORw0KGgo=",
};
const pastedImage: ImageAttachment = { id: "i2", name: "Pasted image 1.png", mimeType: "image/png", data: "iVBORw0KGgo=", size: 8 };

const toolRead: ToolCallView = {
  toolCallId: "call-read",
  title: "Read README.md",
  kind: "read",
  status: "completed",
  content: [{ type: "text", text: "Read README.md" }],
  locations: [{ path: "/work/app/README.md", line: 1 }, { path: "/work/app/other.md" }],
  rawInput: { path: "/work/app/README.md", nested: { list: [1, "two", null, true] } },
  rawOutput: "plain text output",
};
const toolEdit: ToolCallView = {
  toolCallId: "call-edit",
  title: "Create hello.txt",
  kind: "edit",
  status: "pending",
  content: [
    { type: "diff", path: "/work/app/hello.txt", oldText: null, newText: "hello from kiro\n" },
    { type: "diff", path: "/work/app/old.txt", oldText: "before\n", newText: "after\n" },
  ],
  locations: [],
  pendingPermissionId: "perm-1",
};
const toolExec: ToolCallView = {
  toolCallId: "call-exec",
  title: "Run echo hello",
  kind: "execute",
  status: "failed",
  content: [
    { type: "terminal", terminalId: "term-1", output: "hello\n", exitCode: 1 },
    { type: "terminal", terminalId: "term-2", output: "" },
  ],
  locations: [],
  rawInput: 42,
  failureReason: "denied",
};

const permission: PermissionView = {
  requestId: "perm-1",
  toolCallId: "call-edit",
  title: "Create hello.txt",
  kind: "edit",
  options: [
    { optionId: "accept", name: "Allow", kind: "allow_once" },
    { optionId: "always-accept", name: "Always allow", kind: "allow_always" },
    { optionId: "reject", name: "Deny", kind: "reject_once" },
    { optionId: "always-reject", name: "Always deny", kind: "reject_always" },
  ],
  hasDiff: true,
  consent: { capability: "fs_write", resource: "/work/app/hello.txt", persistableConsent: true },
  policies: [
    { scope: "kind", label: "Allow all edits this session", rejectLabel: "Reject all edits this session" },
    { scope: "capability", label: "Allow all file writes this session", rejectLabel: "Reject all file writes this session" },
    { scope: "resource", label: "Allow all changes to hello.txt this session", rejectLabel: "Reject all changes to hello.txt this session" },
  ],
};
const barePermission: PermissionView = {
  requestId: "perm-2",
  toolCallId: "call-x",
  title: "Do something",
  kind: "other",
  options: [],
  hasDiff: false,
};

const usage: SessionUsage = {
  contextPercent: 12.5,
  contextUsed: 25000,
  contextSize: 200000,
  tokensSpent: 31000,
  cost: { amount: 0.42, currency: "USD" },
};
const emptyUsage: SessionUsage = { contextPercent: null, contextUsed: null, contextSize: null, tokensSpent: null, cost: null };

const fullState: UiState = {
  connection: {
    status: "ready",
    agentName: "Kiro",
    agentVersion: "3.1.0",
    canBrowseSessions: true,
    acceptsImages: true,
  },
  acceptsPromptsDuringTurn: true,
  sessionId: "sess-1",
  sessions: [
    { id: "sess-1", title: "Fix the build", status: "running", updatedAt: 1762950000000 },
    { id: "sess-2", title: "New session", status: "idle", updatedAt: 1762940000000 },
    { id: "sess-3", title: "Waiting", status: "needs_input", updatedAt: 1762930000000 },
    { id: "sess-4", title: "Broken", status: "error", updatedAt: 1762920000000 },
  ],
  turnActive: true,
  items: [
    { type: "message", message: { id: "m1", role: "user", segments: [{ type: "text", text: "Fix the build" }], delivery: "queued" } },
    { type: "message", message: { id: "m2", role: "thought", segments: [{ type: "text", text: "Thinking." }] } },
    {
      type: "message",
      message: {
        id: "m3",
        role: "agent",
        segments: [
          { type: "text", text: "Here is **the fix**." },
          { type: "resource", uri: "file:///work/app/src/app.ts", label: "src/app.ts" },
          { type: "image", mimeType: "image/png", name: "shot.png", src: "data:image/png;base64,iVBORw0KGgo=" },
          { type: "image", mimeType: "image/jpeg" },
        ],
      },
    },
    { type: "tool", toolCallId: "call-read" },
  ],
  toolCalls: { "call-read": toolRead, "call-edit": toolEdit, "call-exec": toolExec },
  plan: [
    { content: "Inspect the workspace", priority: "high", status: "completed" },
    { content: "Run a command", priority: "medium", status: "in_progress" },
    { content: "Create hello.txt", priority: "low", status: "pending" },
  ],
  permissions: [permission],
  modes: {
    currentModeId: "code",
    availableModes: [
      { id: "code", name: "Code", description: "Write code" },
      { id: "plan", name: "Plan" },
    ],
  },
  configOptions: [
    {
      id: "model",
      name: "Model",
      description: "The model to use",
      category: "model",
      type: "select",
      currentValue: "auto",
      options: [
        { value: "auto", name: "Auto", description: "Picks for you", group: "Recommended" },
        { value: "big", name: "Big" },
      ],
    },
    { id: "thinking", name: "Thinking", type: "boolean", currentValue: true },
  ],
  models: {
    currentModelId: "auto",
    availableModels: [{ modelId: "auto", name: "Auto", description: "Picks for you" }, { modelId: "big", name: "Big" }],
  },
  commands: [
    { name: "plan", description: "Make a plan", hint: "what to plan" },
    { name: "clear", description: "Clear the conversation" },
  ],
  chips: [chipFile, chipSelection],
  notices: [
    { id: "n1", severity: "info", text: "Connected" },
    { id: "n2", severity: "warning", text: "The answer was cut off" },
    { id: "n3", severity: "error", text: "The agent crashed" },
  ],
  usage,
  lastActivityAt: 1762950000123,
  prefs: { showSettings: true, sessionPanelCollapsed: false },
};

const emptyState: UiState = {
  connection: {
    status: "auth_required",
    message: "Sign in to Kiro",
    authMethods: [
      { id: "kiro-cli-login", name: "Log in to Kiro", description: "Runs `kiro-cli login` in a terminal", terminal: true },
      { id: "token", name: "Token", terminal: false },
    ],
  },
  sessionId: null,
  sessions: [],
  turnActive: false,
  items: [],
  toolCalls: {},
  plan: [],
  permissions: [],
  modes: null,
  configOptions: [],
  models: null,
  commands: [],
  chips: [],
  notices: [],
  prefs: { showSettings: false },
};

const minimalState: UiState = {
  connection: { status: "stopped" },
  sessionId: null,
  sessions: [],
  turnActive: false,
  items: [],
  toolCalls: {},
  plan: [],
  permissions: [],
  modes: null,
  configOptions: [],
  models: null,
  commands: [],
  chips: [],
  notices: [],
};

const toWebview: Samples<ToWebview> = {
  state: [
    { type: "state", state: fullState },
    { type: "state", state: emptyState },
    { type: "state", state: minimalState },
  ],
  connection: [
    { type: "connection", connection: { status: "starting" } },
    { type: "connection", connection: { status: "error", message: "kiro-cli was not found" } },
    { type: "connection", connection: fullState.connection },
  ],
  session: [
    { type: "session", sessionId: "sess-2" },
    { type: "session", sessionId: null },
  ],
  sessions: [
    { type: "sessions", sessions: fullState.sessions },
    { type: "sessions", sessions: [] },
  ],
  turn: [
    { type: "turn", active: true },
    { type: "turn", active: false, stopReason: "end_turn" },
  ],
  chunk: [
    { type: "chunk", messageId: "m3", role: "agent", segment: { type: "text", text: "Hello " } },
    { type: "chunk", messageId: "m2", role: "thought", segment: { type: "text", text: "Hm." } },
    { type: "chunk", messageId: "m1", role: "user", segment: { type: "resource", uri: "file:///work/app/a.ts", label: "a.ts" } },
    { type: "chunk", messageId: "m1", role: "user", segment: { type: "image", mimeType: "image/png", name: "shot.png" } },
  ],
  messageDelivery: [
    { type: "messageDelivery", messageId: "m1", delivery: "steering" },
    { type: "messageDelivery", messageId: "m1", delivery: "queued" },
    { type: "messageDelivery", messageId: "m1", delivery: null },
  ],
  toolCall: [
    { type: "toolCall", call: toolRead },
    { type: "toolCall", call: toolEdit },
    { type: "toolCall", call: toolExec },
    {
      type: "toolCall",
      call: { toolCallId: "call-min", title: "Search", kind: "search", status: "in_progress", content: [], locations: [] },
    },
  ],
  plan: [
    { type: "plan", entries: fullState.plan },
    { type: "plan", entries: [] },
  ],
  permission: [
    { type: "permission", request: permission },
    { type: "permission", request: barePermission },
  ],
  permissionResolved: [{ type: "permissionResolved", requestId: "perm-1" }],
  modes: [
    { type: "modes", modes: fullState.modes },
    { type: "modes", modes: null },
  ],
  configOptions: [
    { type: "configOptions", options: fullState.configOptions },
    { type: "configOptions", options: [] },
  ],
  models: [
    { type: "models", models: fullState.models },
    { type: "models", models: null },
  ],
  commands: [{ type: "commands", commands: fullState.commands }],
  chips: [
    { type: "chips", chips: [chipFile, chipSelection] },
    { type: "chips", chips: [] },
  ],
  fileSuggestions: [
    { type: "fileSuggestions", query: "app", items: [{ path: "/work/app/src/app.ts", label: "src/app.ts" }] },
    { type: "fileSuggestions", query: "", items: [] },
  ],
  notice: [{ type: "notice", notice: { id: "n9", severity: "error", text: "Something failed" } }],
  clearNotice: [{ type: "clearNotice", id: "n9" }],
  usage: [
    { type: "usage", usage },
    { type: "usage", usage: emptyUsage },
    { type: "usage", usage: null },
  ],
  focusInput: [{ type: "focusInput" }],
  imagesAttached: [
    { type: "imagesAttached", images: [image, pastedImage] },
    { type: "imagesAttached", images: [] },
  ],
};

const fromWebview: Samples<FromWebview> = {
  ready: [{ type: "ready" }],
  prompt: [
    { type: "prompt", text: "Fix the build", chips: [] },
    { type: "prompt", text: "Look at this", chips: [chipFile, chipSelection], images: [image, pastedImage] },
  ],
  cancel: [{ type: "cancel" }],
  pickImages: [{ type: "pickImages" }],
  dropFiles: [{ type: "dropFiles", uris: ["file:///work/app/shot.png", "file:///work/app/src/app.ts"] }],
  newSession: [{ type: "newSession" }],
  selectSession: [{ type: "selectSession", sessionId: "sess-2" }],
  closeSession: [{ type: "closeSession", sessionId: "sess-2" }],
  browseSessions: [{ type: "browseSessions" }],
  restart: [{ type: "restart" }],
  login: [{ type: "login", methodId: "kiro-cli-login" }],
  permissionResponse: [{ type: "permissionResponse", requestId: "perm-1", optionId: "accept" }],
  permissionAlways: [
    { type: "permissionAlways", requestId: "perm-1", decision: "allow" },
    { type: "permissionAlways", requestId: "perm-1", decision: "reject", scope: "resource" },
    { type: "permissionAlways", requestId: "perm-1", decision: "allow", scope: "capability" },
    { type: "permissionAlways", requestId: "perm-1", decision: "allow", scope: "kind" },
  ],
  setMode: [{ type: "setMode", modeId: "plan" }],
  setConfigOption: [
    { type: "setConfigOption", configId: "model", value: "big" },
    { type: "setConfigOption", configId: "thinking", value: false },
  ],
  setModel: [{ type: "setModel", modelId: "big" }],
  queryFiles: [{ type: "queryFiles", query: "app" }],
  removeChip: [{ type: "removeChip", id: "c1" }],
  openFile: [
    { type: "openFile", path: "/work/app/src/app.ts", line: 12 },
    { type: "openFile", path: "/work/app/src/app.ts" },
  ],
  openDiff: [{ type: "openDiff", toolCallId: "call-edit", index: 0 }],
  showTerminal: [{ type: "showTerminal", terminalId: "term-1" }],
  openPlan: [{ type: "openPlan" }],
  showLog: [{ type: "showLog" }],
};

console.log(JSON.stringify({ toWebview, fromWebview }, null, 2));
