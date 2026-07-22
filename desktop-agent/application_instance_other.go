//go:build !windows

package main

type noopApplicationInstanceLock struct{}

func (noopApplicationInstanceLock) Close() {}

func acquireApplicationInstance([]string) (applicationInstanceLock, bool, error) {
	return noopApplicationInstanceLock{}, true, nil
}
