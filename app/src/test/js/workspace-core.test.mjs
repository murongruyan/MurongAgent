import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import vm from "node:vm";

const source = await readFile(
  new URL("../../main/assets/lan_web/workspace-core.js", import.meta.url),
  "utf8"
);
const sandbox = {};
sandbox.globalThis = sandbox;
vm.runInNewContext(source, sandbox, { filename: "workspace-core.js" });
const core = sandbox.MurongWorkspaceCore;

function expectPathFailure(path, allowRoot, expectedCode) {
  assert.throws(
    () => core.validateWorkspacePath(path, allowRoot),
    error => error.workspaceCode === expectedCode
  );
}

test("path validation accepts only normalized descendants", () => {
  assert.equal(core.validateWorkspacePath(".", true), ".");
  assert.equal(core.validateWorkspacePath("src/main.js", false), "src/main.js");
  assert.equal(core.validateWorkspacePath("cafe\u0301.txt", false), "caf\u00e9.txt");

  expectPathFailure(".", false, "invalid_path");
  expectPathFailure("../secret", false, "path_outside_workspace");
  expectPathFailure("src/../secret", false, "path_outside_workspace");
  expectPathFailure("/etc/passwd", false, "invalid_path");
  expectPathFailure("C:/secret", false, "invalid_path");
  expectPathFailure("src\\secret", false, "invalid_path");
  expectPathFailure("src//secret", false, "path_outside_workspace");
  expectPathFailure("src/\u0000secret", false, "invalid_path");
  expectPathFailure("segment/".repeat(64) + "file", false, "path_outside_workspace");
  expectPathFailure("x".repeat(256), false, "path_outside_workspace");
});

test("diff preview is bounded and labels creations", () => {
  assert.equal(core.buildWorkspaceDiff("same", "same", false), "(内容没有变化)");
  const created = core.buildWorkspaceDiff("", "hello\nworld", true);
  assert.match(created, /^--- \/dev\/null\n\+\+\+ after/);
  assert.match(created, /\+ hello/);
  assert.ok(core.buildWorkspaceDiff("a\n".repeat(10000), "b\n".repeat(10000), false).length <= 64 * 1024);
});

test("snapshot diff reports created modified and deleted entries", () => {
  const previous = {
    entries: new Map([
      ["src/old.txt", "file:3:1"],
      ["src/change.txt", "file:3:1"],
      ["src/kept", "directory"]
    ]),
    partial: false
  };
  const next = {
    entries: new Map([
      ["src/change.txt", "file:4:2"],
      ["src/kept", "directory"],
      ["src/new.txt", "file:1:2"]
    ]),
    partial: false
  };

  assert.deepEqual(
    JSON.parse(JSON.stringify(core.diffWorkspaceSnapshots(previous, next))),
    [
      { path: "src/change.txt", kind: "modified", directory: false },
      { path: "src/new.txt", kind: "created", directory: false },
      { path: "src/old.txt", kind: "deleted", directory: false }
    ]
  );
});
