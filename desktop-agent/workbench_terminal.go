package main

import (
	"context"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"os"
	"strings"
	"sync"
	"time"

	ptylib "github.com/aymanbagabas/go-pty"
)

type WorkbenchTerminalStartRequest struct {
	ClientID   string `json:"clientId"`
	TerminalID string `json:"terminalId"`
	Directory  string `json:"directory"`
	Columns    int    `json:"columns"`
	Rows       int    `json:"rows"`
}

type WorkbenchTerminalSessionInfo struct {
	SessionID string `json:"sessionId"`
	ClientID  string `json:"clientId"`
	Terminal  string `json:"terminal"`
	Label     string `json:"label"`
	Version   string `json:"version,omitempty"`
	Directory string `json:"directory"`
}

type WorkbenchTerminalWriteRequest struct {
	SessionID string `json:"sessionId"`
	Data      string `json:"data"`
}

type WorkbenchTerminalResizeRequest struct {
	SessionID string `json:"sessionId"`
	Columns   int    `json:"columns"`
	Rows      int    `json:"rows"`
}

type workbenchTerminalOutput struct {
	SessionID string `json:"sessionId"`
	ClientID  string `json:"clientId"`
	Base64    string `json:"base64"`
}

type workbenchTerminalExit struct {
	SessionID string `json:"sessionId"`
	ClientID  string `json:"clientId"`
	ExitCode  int    `json:"exitCode"`
}

type workbenchTerminalSession struct {
	id       string
	clientID string
	terminal ptylib.Pty
	command  *ptylib.Cmd
	cancel   context.CancelFunc
	close    sync.Once
	ready    chan struct{}
	readySet sync.Once
}

type workbenchTerminalManager struct {
	mu       sync.Mutex
	sessions map[string]*workbenchTerminalSession
	emit     func(string, any)
}

func newWorkbenchTerminalManager(emit func(string, any)) *workbenchTerminalManager {
	return &workbenchTerminalManager{sessions: map[string]*workbenchTerminalSession{}, emit: emit}
}

func (app *DesktopAgentApp) ensureWorkbenchTerminalManager() *workbenchTerminalManager {
	app.mu.Lock()
	defer app.mu.Unlock()
	if app.workbenchTerminals == nil {
		app.workbenchTerminals = newWorkbenchTerminalManager(app.emit)
	}
	return app.workbenchTerminals
}

func (app *DesktopAgentApp) StartWorkbenchTerminalSession(request WorkbenchTerminalStartRequest) (WorkbenchTerminalSessionInfo, error) {
	workspace, err := app.workbenchWorkspace()
	if err != nil {
		return WorkbenchTerminalSessionInfo{}, err
	}
	backend, ok := terminalByID(app.terminals, strings.TrimSpace(request.TerminalID))
	if !ok && len(app.terminals) > 0 {
		backend, ok = app.terminals[0], true
	}
	if !ok {
		return WorkbenchTerminalSessionInfo{}, errors.New("当前电脑没有可用终端")
	}
	directory := strings.TrimSpace(request.Directory)
	if directory == "" {
		directory = "."
	}
	resolvedDirectory, err := workspace.resolveExisting(directory, true)
	if err != nil {
		return WorkbenchTerminalSessionInfo{}, err
	}
	executable, arguments, err := interactiveTerminalCommand(backend, resolvedDirectory)
	if err != nil {
		return WorkbenchTerminalSessionInfo{}, err
	}
	columns, rows := normalizedTerminalSize(request.Columns, request.Rows)
	terminal, err := ptylib.New()
	if err != nil {
		return WorkbenchTerminalSessionInfo{}, fmt.Errorf("无法创建系统终端：%w", err)
	}
	if err := terminal.Resize(columns, rows); err != nil {
		_ = terminal.Close()
		return WorkbenchTerminalSessionInfo{}, fmt.Errorf("无法设置终端尺寸：%w", err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	command := terminal.CommandContext(ctx, executable, arguments...)
	command.Dir = resolvedDirectory
	command.Env = os.Environ()
	if err := command.Start(); err != nil {
		cancel()
		_ = terminal.Close()
		return WorkbenchTerminalSessionInfo{}, fmt.Errorf("无法启动 %s：%w", backend.Label, err)
	}
	clientID := strings.TrimSpace(request.ClientID)
	if clientID == "" || len(clientID) > 160 {
		clientID = newID("terminal-client")
	}
	session := &workbenchTerminalSession{
		id: newID("terminal"), clientID: clientID, terminal: terminal, command: command, cancel: cancel,
		ready: make(chan struct{}),
	}
	manager := app.ensureWorkbenchTerminalManager()
	manager.mu.Lock()
	manager.sessions[session.id] = session
	manager.mu.Unlock()
	go manager.read(session)
	go manager.wait(session)
	return WorkbenchTerminalSessionInfo{
		SessionID: session.id, ClientID: clientID, Terminal: backend.ID, Label: backend.Label,
		Version: backend.Version, Directory: resolvedDirectory,
	}, nil
}

func (app *DesktopAgentApp) WriteWorkbenchTerminalSession(request WorkbenchTerminalWriteRequest) error {
	if len(request.Data) > 256*1024 {
		return errors.New("单次终端输入超过 256 KiB")
	}
	session, err := app.ensureWorkbenchTerminalManager().get(request.SessionID)
	if err != nil {
		return err
	}
	select {
	case <-session.ready:
	case <-time.After(2 * time.Second):
	}
	_, err = session.terminal.Write([]byte(request.Data))
	return err
}

func (app *DesktopAgentApp) ResizeWorkbenchTerminalSession(request WorkbenchTerminalResizeRequest) error {
	session, err := app.ensureWorkbenchTerminalManager().get(request.SessionID)
	if err != nil {
		return err
	}
	columns, rows := normalizedTerminalSize(request.Columns, request.Rows)
	return session.terminal.Resize(columns, rows)
}

func (app *DesktopAgentApp) CloseWorkbenchTerminalSession(sessionID string) error {
	return app.ensureWorkbenchTerminalManager().Close(strings.TrimSpace(sessionID))
}

func normalizedTerminalSize(columns, rows int) (int, int) {
	if columns < 2 {
		columns = 80
	}
	if rows < 2 {
		rows = 24
	}
	if columns > 1000 {
		columns = 1000
	}
	if rows > 500 {
		rows = 500
	}
	return columns, rows
}

func interactiveTerminalCommand(backend TerminalBackend, directory string) (string, []string, error) {
	switch backend.Kind {
	case terminalPowerShell7, terminalWindowsPowerShell:
		return backend.Executable, []string{"-NoLogo"}, nil
	case terminalCMD:
		return backend.Executable, []string{"/d", "/q"}, nil
	case "wsl":
		if strings.TrimSpace(backend.Distribution) == "" {
			return "", nil, errors.New("WSL 发行版为空")
		}
		return backend.Executable, []string{"-d", backend.Distribution, "--cd", directory}, nil
	case "posix-shell":
		return backend.Executable, []string{"-i"}, nil
	default:
		return "", nil, fmt.Errorf("不支持的终端类型：%s", backend.Kind)
	}
}

func (manager *workbenchTerminalManager) get(sessionID string) (*workbenchTerminalSession, error) {
	manager.mu.Lock()
	defer manager.mu.Unlock()
	session := manager.sessions[strings.TrimSpace(sessionID)]
	if session == nil {
		return nil, errors.New("终端会话不存在或已经结束")
	}
	return session, nil
}

func (manager *workbenchTerminalManager) read(session *workbenchTerminalSession) {
	buffer := make([]byte, 32*1024)
	for {
		count, err := session.terminal.Read(buffer)
		if count > 0 {
			session.readySet.Do(func() {
				time.AfterFunc(75*time.Millisecond, func() { close(session.ready) })
			})
			if manager.emit != nil {
				manager.emit("workbench:terminal-output", workbenchTerminalOutput{
					SessionID: session.id, ClientID: session.clientID,
					Base64: base64.StdEncoding.EncodeToString(buffer[:count]),
				})
			}
		}
		if err != nil {
			if !errors.Is(err, io.EOF) {
				// The exit event is authoritative; closing a PTY commonly interrupts Read.
			}
			return
		}
	}
}

func (manager *workbenchTerminalManager) wait(session *workbenchTerminalSession) {
	exitCode := 0
	if err := session.command.Wait(); err != nil {
		exitCode = -1
		if session.command.ProcessState != nil {
			exitCode = session.command.ProcessState.ExitCode()
		}
	} else if session.command.ProcessState != nil {
		exitCode = session.command.ProcessState.ExitCode()
	}
	manager.mu.Lock()
	delete(manager.sessions, session.id)
	manager.mu.Unlock()
	session.closeResources(false)
	if manager.emit != nil {
		manager.emit("workbench:terminal-exit", workbenchTerminalExit{
			SessionID: session.id, ClientID: session.clientID, ExitCode: exitCode,
		})
	}
}

func (manager *workbenchTerminalManager) Close(sessionID string) error {
	manager.mu.Lock()
	session := manager.sessions[sessionID]
	delete(manager.sessions, sessionID)
	manager.mu.Unlock()
	if session == nil {
		return nil
	}
	session.closeResources(true)
	return nil
}

func (manager *workbenchTerminalManager) CloseAll() {
	manager.mu.Lock()
	sessions := make([]*workbenchTerminalSession, 0, len(manager.sessions))
	for _, session := range manager.sessions {
		sessions = append(sessions, session)
	}
	manager.sessions = map[string]*workbenchTerminalSession{}
	manager.mu.Unlock()
	for _, session := range sessions {
		session.closeResources(true)
	}
}

func (session *workbenchTerminalSession) closeResources(kill bool) {
	session.close.Do(func() {
		session.cancel()
		if kill && session.command.Process != nil {
			_ = session.command.Process.Kill()
		}
		_ = session.terminal.Close()
	})
}
