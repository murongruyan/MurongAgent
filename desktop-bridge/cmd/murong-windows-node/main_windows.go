//go:build windowsgui && windows

package main

import desktopbridge "github.com/murong-agent/desktop-bridge"

func main() {
	desktopbridge.RunGUI()
}
