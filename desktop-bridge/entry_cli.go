//go:build !windowsgui

package desktopbridge

import "os"

func RunCLI() error {
	return runCLI(os.Args[1:], os.Stdin, os.Stdout)
}
