package main

import (
	"errors"
	"fmt"
	"log"
	"os"
	"regexp"
	goruntime "runtime"
	"strings"

	"github.com/wailsapp/wails/v2/pkg/runtime"
)

type externalWorkflowLaunch struct {
	WorkflowID string
}

var externalWorkflowIDPattern = regexp.MustCompile(`^[A-Za-z0-9._:-]{1,128}$`)

func parseExternalWorkflowLaunch(args []string) (*externalWorkflowLaunch, error) {
	workflowID := ""
	for index := 0; index < len(args); index++ {
		argument := strings.TrimSpace(args[index])
		value := ""
		switch {
		case argument == "--run-workflow":
			if index+1 >= len(args) {
				return nil, errors.New("--run-workflow 后必须提供保存的工作流 ID")
			}
			index++
			value = strings.TrimSpace(args[index])
		case strings.HasPrefix(argument, "--run-workflow="):
			value = strings.TrimSpace(strings.TrimPrefix(argument, "--run-workflow="))
		default:
			continue
		}
		if value == "" {
			return nil, errors.New("保存的工作流 ID 不能为空")
		}
		if workflowID != "" {
			return nil, errors.New("一次启动只能指定一个保存的工作流")
		}
		if !externalWorkflowIDPattern.MatchString(value) {
			return nil, errors.New("保存的工作流 ID 无效")
		}
		workflowID = value
	}
	if workflowID == "" {
		return nil, nil
	}
	return &externalWorkflowLaunch{WorkflowID: workflowID}, nil
}

func (app *DesktopAgentApp) setInitialLaunchArgs(args []string) {
	app.mu.Lock()
	app.initialLaunchArgs = append([]string(nil), args...)
	app.mu.Unlock()
}

func (app *DesktopAgentApp) handleExternalLaunch(args []string, focusWindow bool) {
	if focusWindow && app.ctx != nil {
		runtime.WindowUnminimise(app.ctx)
		runtime.WindowShow(app.ctx)
	}
	launch, err := parseExternalWorkflowLaunch(args)
	if err == nil && launch != nil {
		err = app.runExternalWorkflow(launch.WorkflowID)
	}
	if launch == nil && err == nil {
		return
	}
	payload := map[string]any{"success": err == nil}
	if launch != nil {
		payload["workflowId"] = launch.WorkflowID
	}
	if err != nil {
		payload["message"] = err.Error()
		log.Printf("外部工作流调用失败：%v", err)
	} else {
		payload["message"] = "外部工作流已加入执行队列"
	}
	app.emit("external-workflow:status", payload)
}

func (app *DesktopAgentApp) runExternalWorkflow(id string) error {
	id = strings.TrimSpace(id)
	workflow, ok := app.workflows.store.get(id)
	if !ok {
		return errors.New("保存的工作流不存在")
	}
	if validation := validateSavedWorkflow(workflow); len(validation) > 0 {
		return errors.New(strings.Join(validation, "；"))
	}
	if !savedWorkflowBackgroundSafe(workflow) {
		return errors.New("外部调用只允许固定只读或网络只读工作流；该工作流必须回到 Murong 前台逐次确认")
	}
	_, err := app.workflows.RunNow(RunSavedWorkflowRequest{ID: workflow.ID})
	return err
}

func (app *DesktopAgentApp) GetSavedWorkflowExternalCommand(id string) (string, error) {
	id = strings.TrimSpace(id)
	workflow, ok := app.workflows.store.get(id)
	if !ok {
		return "", errors.New("保存的工作流不存在")
	}
	if !savedWorkflowBackgroundSafe(workflow) {
		return "", errors.New("这个工作流需要前台确认，不能生成外部调用命令")
	}
	executable, err := os.Executable()
	if err != nil {
		return "", fmt.Errorf("无法定位 Murong 桌面程序：%w", err)
	}
	if !externalWorkflowIDPattern.MatchString(workflow.ID) {
		return "", errors.New("工作流 ID 不能安全用于外部命令")
	}
	return quoteExternalExecutable(executable) + " --run-workflow " + workflow.ID, nil
}

func quoteExternalExecutable(executable string) string {
	if goruntime.GOOS == "windows" {
		return `"` + executable + `"`
	}
	return `'` + strings.ReplaceAll(executable, `'`, `'"'"'`) + `'`
}

func (app *DesktopAgentApp) CopySavedWorkflowExternalCommand(id string) error {
	command, err := app.GetSavedWorkflowExternalCommand(id)
	if err != nil {
		return err
	}
	if app.ctx == nil {
		return errors.New("Murong 窗口尚未就绪")
	}
	return runtime.ClipboardSetText(app.ctx, command)
}
