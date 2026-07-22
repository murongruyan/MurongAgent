package main

import (
	"embed"
	"log"
	"os"

	"github.com/wailsapp/wails/v2"
	"github.com/wailsapp/wails/v2/pkg/options"
	"github.com/wailsapp/wails/v2/pkg/options/assetserver"
	"github.com/wailsapp/wails/v2/pkg/options/linux"
	"github.com/wailsapp/wails/v2/pkg/options/mac"
	"github.com/wailsapp/wails/v2/pkg/options/windows"
)

//go:embed all:frontend/dist
var frontendAssets embed.FS

//go:embed frontend/dist/app-icon.jpg
var applicationIcon []byte

func main() {
	prepareApplicationIdentity()
	instanceLock, primary, lockErr := acquireApplicationInstance(os.Args[1:])
	if lockErr != nil {
		log.Printf("Murong 单实例检查：%v", lockErr)
	}
	if !primary {
		return
	}
	defer instanceLock.Close()
	app, err := newDesktopAgentApp()
	if err != nil {
		log.Fatal(err)
	}
	app.setInitialLaunchArgs(os.Args[1:])
	err = wails.Run(&options.App{
		Title:                    "Murong",
		Width:                    1440,
		Height:                   900,
		MinWidth:                 1080,
		MinHeight:                700,
		Frameless:                true,
		BackgroundColour:         options.NewRGB(245, 247, 253),
		AssetServer:              &assetserver.Options{Assets: frontendAssets},
		OnStartup:                app.startup,
		OnShutdown:               app.shutdown,
		OnBeforeClose:            app.beforeClose,
		Bind:                     []interface{}{app},
		EnableDefaultContextMenu: true,
		SingleInstanceLock: &options.SingleInstanceLock{
			UniqueId: "murong-desktop-agent-v1",
			OnSecondInstanceLaunch: func(data options.SecondInstanceData) {
				app.handleExternalLaunch(data.Args, true)
			},
		},
		Windows: &windows.Options{
			Theme:                             windows.Light,
			BackdropType:                      windows.Mica,
			WindowClassName:                   "MurongDesktopAgentWindow",
			DisableFramelessWindowDecorations: false,
			EnableSwipeGestures:               false,
			DisableWindowIcon:                 false,
		},
		Mac: &mac.Options{
			TitleBar:          mac.TitleBarHiddenInset(),
			Appearance:        mac.DefaultAppearance,
			About:             &mac.AboutInfo{Title: "Murong", Message: "Murong Desktop Agent", Icon: applicationIcon},
			ContentProtection: true,
		},
		Linux: &linux.Options{
			Icon:             applicationIcon,
			ProgramName:      "murong-desktop-agent",
			WebviewGpuPolicy: linux.WebviewGpuPolicyOnDemand,
		},
	})
	if err != nil {
		log.Fatal(err)
	}
}
