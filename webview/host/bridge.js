// Eclipse host shim for the vendored VS Code webview bundle.
//
// The bundle expects `acquireVsCodeApi()` and receives host messages as window
// "message" events. Here both ends are wired to the SWT Browser: `__kiroHost`
// is a BrowserFunction installed by the Java side, and Java calls
// `__kiroReceive` / `__kiroTheme` through Browser.execute.
(function () {
  "use strict";

  // Every chat view loads this same file, so the origin's storage is shared:
  // the host names the view in the URL and each one keeps its own state.
  var view = "main";
  try {
    view = new URLSearchParams(window.location.search).get("view") || "main";
  } catch (e) {
    view = "main";
  }
  var STATE_KEY = "kiroAcp.webviewState." + view;
  var state;
  try {
    var raw = window.localStorage.getItem(STATE_KEY);
    state = raw ? JSON.parse(raw) : undefined;
  } catch (e) {
    state = undefined;
  }

  var pending = [];

  function send(json) {
    if (typeof window.__kiroHost !== "function") return false;
    window.__kiroHost(json);
    return true;
  }

  function flush() {
    while (pending.length > 0 && send(pending[0])) pending.shift();
  }

  var api = {
    postMessage: function (message) {
      var json = JSON.stringify(message);
      flush();
      if (pending.length > 0 || !send(json)) pending.push(json);
    },
    getState: function () {
      return state;
    },
    setState: function (next) {
      state = next;
      try {
        window.localStorage.setItem(STATE_KEY, JSON.stringify(next));
      } catch (e) {
        // Persisting UI state is a convenience only.
      }
      return next;
    },
  };

  var acquired = false;
  window.acquireVsCodeApi = function () {
    if (acquired) throw new Error("An instance of the host API has already been acquired");
    acquired = true;
    return api;
  };

  // Copying goes through the host. The page calls navigator.clipboard.writeText,
  // which Chromium refuses unless the document has focus, and whether an
  // embedded browser's document counts as focused is not ours to decide; the
  // host's clipboard has no such condition. Only writeText is replaced: pasting
  // stays the browser's own.
  var copies = {};
  var copySeq = 0;
  function writeText(text) {
    return new Promise(function (resolve, reject) {
      var id = String(++copySeq);
      copies[id] = { resolve: resolve, reject: reject };
      api.postMessage({ type: "host:copy", id: id, text: String(text) });
    });
  }
  try {
    if (navigator.clipboard) navigator.clipboard.writeText = writeText;
    else Object.defineProperty(navigator, "clipboard", { configurable: true, value: { writeText: writeText } });
  } catch (e) {
    // The page then falls back to what the browser offers by itself.
  }

  /** Host -> webview: the outcome of one "host:copy". */
  window.__kiroCopied = function (id, ok) {
    var copy = copies[id];
    if (!copy) return;
    delete copies[id];
    if (ok) copy.resolve();
    else copy.reject(new Error("The host could not copy"));
  };

  // VS Code's webview host intercepts link clicks; here the shim does. The page
  // itself must never navigate (the host refuses it anyway), so web links are
  // handed to the host to open outside and every other target is dropped.
  document.addEventListener(
    "click",
    function (event) {
      var node = event.target;
      while (node && node.nodeType === 1 && node.tagName !== "A") node = node.parentNode;
      if (!node || node.nodeType !== 1 || !node.hasAttribute("href")) return;
      var href = node.getAttribute("href") || "";
      if (href.charAt(0) === "#") return;
      event.preventDefault();
      if (/^https?:\/\//i.test(href)) api.postMessage({ type: "host:openExternal", url: href });
    },
    true,
  );

  /** Host -> webview: one protocol message (already a JS object). */
  window.__kiroReceive = function (message) {
    window.dispatchEvent(new MessageEvent("message", { data: message }));
  };

  /** Host asks for anything posted before the BrowserFunction existed. */
  window.__kiroFlush = flush;

  /**
   * Host -> webview: theme. `kind` is "light" or "dark"; `vars` overrides
   * individual `--vscode-*` variables with colours and fonts taken from Eclipse.
   */
  var themed = [];
  window.__kiroTheme = function (kind, vars) {
    var apply = function () {
      var body = document.body;
      body.classList.remove("vscode-light", "vscode-dark");
      body.classList.add(kind === "dark" ? "vscode-dark" : "vscode-light");
      // A variable the host no longer sets goes back to the stylesheet's default.
      for (var i = 0; i < themed.length; i++) body.style.removeProperty(themed[i]);
      themed = [];
      for (var name in vars || {}) {
        if (Object.prototype.hasOwnProperty.call(vars, name)) {
          body.style.setProperty(name, vars[name]);
          themed.push(name);
        }
      }
    };
    if (document.body) apply();
    else document.addEventListener("DOMContentLoaded", apply);
  };
})();
