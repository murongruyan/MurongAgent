//go:build !windowsgui

package main

import (
	"fmt"
	"os"

	desktopbridge "github.com/murong-agent/desktop-bridge"
)

func main() {
	if err := desktopbridge.RunCLI(); err != nil {
		_, _ = fmt.Fprintln(os.Stderr, "Error:", err)
		os.Exit(1)
	}
}
