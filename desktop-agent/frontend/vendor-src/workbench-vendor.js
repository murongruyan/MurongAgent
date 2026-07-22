import { Terminal } from "@xterm/xterm";
import { FitAddon } from "@xterm/addon-fit";
import "@xterm/xterm/css/xterm.css";

import { basicSetup } from "codemirror";
import { Compartment, EditorState } from "@codemirror/state";
import { EditorView } from "@codemirror/view";
import { defaultHighlightStyle, StreamLanguage, syntaxHighlighting } from "@codemirror/language";
import { javascript } from "@codemirror/lang-javascript";
import { html } from "@codemirror/lang-html";
import { css } from "@codemirror/lang-css";
import { json } from "@codemirror/lang-json";
import { markdown } from "@codemirror/lang-markdown";
import DOMPurify from "dompurify";
import { marked } from "marked";
import { python } from "@codemirror/lang-python";
import { java } from "@codemirror/lang-java";
import { cpp } from "@codemirror/lang-cpp";
import { go } from "@codemirror/lang-go";
import { rust } from "@codemirror/lang-rust";
import { sql } from "@codemirror/lang-sql";
import { yaml } from "@codemirror/lang-yaml";
import { xml } from "@codemirror/lang-xml";
import { shell } from "@codemirror/legacy-modes/mode/shell";

const editorTheme = EditorView.theme({
  "&": { height: "100%", backgroundColor: "#ffffff", color: "#252932" },
  ".cm-scroller": { overflow: "auto", fontFamily: '"Cascadia Mono", Consolas, monospace', fontSize: "12px", lineHeight: "1.6" },
  ".cm-content": { caretColor: "#bf3f7a", padding: "10px 0" },
  ".cm-cursor, .cm-dropCursor": { borderLeftColor: "#bf3f7a" },
  ".cm-gutters": { backgroundColor: "#f8f9fb", color: "#a0a7b4", borderRight: "1px solid #e7e9ee" },
  ".cm-activeLine, .cm-activeLineGutter": { backgroundColor: "#fff5fa" },
  ".cm-selectionBackground, &.cm-focused .cm-selectionBackground": { backgroundColor: "#f1c9dc" },
  ".cm-focused": { outline: "none" }
});

function languageForPath(path) {
  const name = String(path || "").toLowerCase();
  const extension = name.includes(".") ? name.slice(name.lastIndexOf(".")) : "";
  if ([".js", ".jsx", ".mjs", ".cjs"].includes(extension)) return javascript({ jsx: true });
  if ([".ts", ".tsx", ".mts", ".cts"].includes(extension)) return javascript({ jsx: extension === ".tsx", typescript: true });
  if (extension === ".json" || name.endsWith(".code-workspace")) return json();
  if ([".html", ".htm", ".vue", ".svelte"].includes(extension)) return html();
  if ([".css", ".scss", ".less"].includes(extension)) return css();
  if ([".md", ".markdown"].includes(extension)) return markdown();
  if (extension === ".py") return python();
  if ([".java", ".kt", ".kts"].includes(extension)) return java();
  if ([".c", ".h", ".cc", ".cpp", ".cxx", ".hpp"].includes(extension)) return cpp();
  if (extension === ".go") return go();
  if (extension === ".rs") return rust();
  if ([".sql", ".ddl"].includes(extension)) return sql();
  if ([".yaml", ".yml"].includes(extension)) return yaml();
  if ([".xml", ".svg", ".plist"].includes(extension)) return xml();
  if ([".sh", ".bash", ".zsh", ".fish", ".ps1", ".bat", ".cmd"].includes(extension)) return StreamLanguage.define(shell);
  return [];
}

function createCodeEditor(parent, onChange) {
  const controller = {
    view: null,
    suppress: false,
    language: new Compartment(),
    editable: new Compartment()
  };
  controller.view = new EditorView({
    parent,
    state: EditorState.create({
      doc: "",
      extensions: [
        basicSetup,
        editorTheme,
        syntaxHighlighting(defaultHighlightStyle, { fallback: true }),
        controller.language.of([]),
        controller.editable.of([EditorState.readOnly.of(true), EditorView.editable.of(false)]),
        EditorView.updateListener.of((update) => {
          if (update.docChanged && !controller.suppress) onChange(update.state.doc.toString());
        })
      ]
    })
  });
  return controller;
}

function setCodeEditorDocument(controller, content, path, editable) {
  if (!controller?.view) return;
  controller.suppress = true;
  controller.view.dispatch({
    changes: { from: 0, to: controller.view.state.doc.length, insert: String(content || "") },
    effects: [
      controller.language.reconfigure(languageForPath(path)),
      controller.editable.reconfigure([
        EditorState.readOnly.of(!editable),
        EditorView.editable.of(Boolean(editable))
      ])
    ]
  });
  controller.suppress = false;
}

window.MurongWorkbenchVendor = {
  Terminal,
  FitAddon,
  createCodeEditor,
  setCodeEditorDocument,
  focusCodeEditor(controller) { controller?.view?.focus(); },
  renderMarkdown(source) {
    const rendered = marked.parse(String(source || ""), {
      async: false,
      breaks: true,
      gfm: true
    });
    return DOMPurify.sanitize(rendered, {
      USE_PROFILES: { html: true }
    });
  }
};
