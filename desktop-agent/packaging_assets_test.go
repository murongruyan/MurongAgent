package main

import (
	"encoding/json"
	"image/png"
	"os"
	"testing"
)

func TestDesktopPackagingAssets(t *testing.T) {
	icon, err := os.Open("build/appicon.png")
	if err != nil {
		t.Fatal(err)
	}
	configuration, err := png.DecodeConfig(icon)
	closeErr := icon.Close()
	if err != nil {
		t.Fatal(err)
	}
	if closeErr != nil {
		t.Fatal(closeErr)
	}
	if configuration.Width != 1024 || configuration.Height != 1024 {
		t.Fatalf("desktop packaging icon must be 1024x1024, got %dx%d", configuration.Width, configuration.Height)
	}

	data, err := os.ReadFile("wails.json")
	if err != nil {
		t.Fatal(err)
	}
	var configurationFile struct {
		Name           string `json:"name"`
		OutputFilename string `json:"outputfilename"`
		Info           struct {
			ProductName string `json:"productName"`
		} `json:"info"`
	}
	if err := json.Unmarshal(data, &configurationFile); err != nil {
		t.Fatal(err)
	}
	if configurationFile.Name != "Murong Desktop Agent" || configurationFile.OutputFilename != "murong-desktop-agent" || configurationFile.Info.ProductName != "Murong" {
		t.Fatalf("unexpected Wails package identity: %#v", configurationFile)
	}
}
