//go:build !windows

package main

func startApplicationTray(func(), func()) (applicationTray, error) {
	return nil, nil
}
