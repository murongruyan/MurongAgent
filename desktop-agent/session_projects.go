package main

import (
	"errors"
	"fmt"
	"strings"
)

func (app *DesktopAgentApp) sessionExecutionConfig(sessionID string, bindLegacy bool) (desktopConfig, *ChatSession, error) {
	sessionID = strings.TrimSpace(sessionID)
	session := app.store.getSession(sessionID)
	if session == nil {
		return desktopConfig{}, nil, errors.New("会话不存在")
	}
	config := app.store.rawConfig()
	projectPath := strings.TrimSpace(session.ProjectPath)
	if projectPath == "" {
		projectPath = strings.TrimSpace(config.ProjectPath)
		if projectPath == "" {
			return desktopConfig{}, session, errors.New("当前任务尚未绑定项目，请先打开或新建项目文件夹")
		}
	}
	resolved, err := normalizeExistingProjectPath(projectPath)
	if err != nil {
		if strings.TrimSpace(session.ProjectPath) != "" {
			return desktopConfig{}, session, fmt.Errorf("当前任务绑定的项目不可用：%w；请重新选择项目", err)
		}
		return desktopConfig{}, session, fmt.Errorf("当前项目不可用：%w；请重新选择项目", err)
	}
	if bindLegacy && (strings.TrimSpace(session.ProjectPath) == "" || !sameWorkspacePath(session.ProjectPath, resolved)) {
		session, err = app.store.bindSessionProject(sessionID, resolved)
		if err != nil {
			return desktopConfig{}, session, err
		}
	}
	config.ProjectPath = resolved
	return config, session, nil
}

func (app *DesktopAgentApp) BindSessionProject(request SessionProjectBindingRequest) (*ChatSession, error) {
	request.SessionID = strings.TrimSpace(request.SessionID)
	request.ProjectPath = strings.TrimSpace(request.ProjectPath)
	if request.SessionID == "" {
		return nil, errors.New("会话不能为空")
	}
	if app.sessionRunActive(request.SessionID) {
		return nil, errors.New("当前任务仍在运行，停止后才能切换项目")
	}
	previous := app.store.getSession(request.SessionID)
	if previous == nil {
		return nil, errors.New("会话不存在")
	}
	bound, err := app.store.bindSessionProject(request.SessionID, request.ProjectPath)
	if err != nil {
		return nil, err
	}
	var config PublicDesktopConfig
	if request.ProjectPath == "" {
		config, err = app.store.closeProject()
	} else {
		config, err = app.store.activateProject(bound.ProjectPath)
	}
	if err != nil {
		_, rollbackErr := app.store.bindSessionProject(request.SessionID, previous.ProjectPath)
		if rollbackErr != nil {
			return nil, fmt.Errorf("切换项目失败：%w；任务绑定回滚也失败：%v", err, rollbackErr)
		}
		return nil, err
	}
	app.selectRemoteSession(bound.ID)
	app.emitProjectChanged(config)
	app.emitSessionsChanged(bound)
	return bound, nil
}

func (app *DesktopAgentApp) SelectSession(id string) (SessionSelection, error) {
	id = strings.TrimSpace(id)
	session := app.store.getSession(id)
	if session == nil {
		return SessionSelection{}, errors.New("会话不存在")
	}
	app.selectRemoteSession(session.ID)
	selection := SessionSelection{Session: session, Config: app.store.publicConfig()}
	projectPath := strings.TrimSpace(session.ProjectPath)
	if projectPath == "" {
		projectPath = strings.TrimSpace(selection.Config.ProjectPath)
	}
	if projectPath == "" {
		return selection, nil
	}
	resolved, err := normalizeExistingProjectPath(projectPath)
	if err != nil {
		selection.ProjectError = err.Error()
		if config, closeErr := app.store.closeProject(); closeErr == nil {
			selection.Config = config
			app.emitProjectChanged(config)
		}
		return selection, nil
	}
	selection.ProjectAvailable = true
	if strings.TrimSpace(session.ProjectPath) == "" || sameWorkspacePath(selection.Config.ProjectPath, resolved) {
		return selection, nil
	}
	config, err := app.store.activateProject(resolved)
	if err != nil {
		selection.ProjectAvailable = false
		selection.ProjectError = err.Error()
		return selection, nil
	}
	selection.Config = config
	app.emitProjectChanged(config)
	return selection, nil
}
