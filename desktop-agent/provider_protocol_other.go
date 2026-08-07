//go:build !windows

package main

import "errors"

type ProviderImportProtocolState struct {
	Supported                   bool   `json:"supported"`
	MurongRegistered            bool   `json:"murongRegistered"`
	CCSwitchCompatibility       bool   `json:"ccSwitchCompatibilityEnabled"`
	CCSwitchCurrentHandlerLabel string `json:"ccSwitchCurrentHandlerLabel"`
}

func ensureNativeProviderImportProtocol() error { return nil }

func getProviderImportProtocolState() ProviderImportProtocolState {
	return ProviderImportProtocolState{Supported: false, CCSwitchCurrentHandlerLabel: "当前系统暂未提供应用内协议切换"}
}

func setCCSwitchProtocolCompatibility(bool) (ProviderImportProtocolState, error) {
	return getProviderImportProtocolState(), errors.New("当前系统暂未提供应用内 CC Switch 协议切换")
}
