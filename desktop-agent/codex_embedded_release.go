//go:build embedded_codex

package main

import _ "embed"

//go:embed runtime/codex-runtime.tgz
var embeddedCodexArchive []byte

func embeddedCodexArchiveBytes() []byte { return embeddedCodexArchive }
