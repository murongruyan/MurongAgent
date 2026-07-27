//go:build !windows

package main

import (
	"context"
	"encoding/json"
	"errors"
)

func guiPlatformSupported() bool {
	return false
}

func guiPlatformObserve(context.Context, int, bool) (guiObservation, error) {
	return guiObservation{}, errors.New("当前桌面平台尚未实现原生 GUI 自动化")
}

func guiPlatformAction(context.Context, string, map[string]json.RawMessage) (string, error) {
	return "", errors.New("当前桌面平台尚未实现原生 GUI 自动化")
}

func guiPlatformScreenshot(context.Context, map[string]json.RawMessage) (guiScreenshot, error) {
	return guiScreenshot{}, errors.New("当前桌面平台尚未实现原生 GUI 截图")
}
