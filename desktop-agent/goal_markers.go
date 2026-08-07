package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"regexp"
	"strings"
)

const completeGoalToolName = "complete_goal"

// goalControlMarkers holds the goal/plan control markers a model may place on
// their own lines inside a reply, e.g. "[goal:start] 帮我重构登录模块" or
// "[goal:complete]". Recognized marker lines are removed from the content that
// is shown to the user.
type goalControlMarkers struct {
	GoalStart        string
	Pause            bool
	Resume           bool
	Complete         bool
	PlanStart        bool
	RemainingContent string
}

var goalMarkerLinePattern = regexp.MustCompile(`(?i)^\[(goal:start|goal:update|goal:pause|goal:resume|goal:complete|plan:start)\](.*)$`)

func parseGoalControlMarkers(raw string) *goalControlMarkers {
	trimmed := strings.TrimSpace(raw)
	if trimmed == "" {
		return nil
	}
	markers := &goalControlMarkers{}
	sawMarker := false
	kept := make([]string, 0, 8)
	for _, rawLine := range strings.Split(trimmed, "\n") {
		line := strings.TrimSpace(rawLine)
		match := goalMarkerLinePattern.FindStringSubmatch(line)
		if match == nil {
			kept = append(kept, rawLine)
			continue
		}
		sawMarker = true
		action := strings.ToLower(strings.TrimSpace(match[1]))
		rest := strings.TrimSpace(match[2])
		switch action {
		case "goal:start", "goal:update":
			markers.GoalStart = rest
		case "goal:pause":
			markers.Pause = true
		case "goal:resume":
			markers.Resume = true
		case "goal:complete":
			markers.Complete = true
		case "plan:start":
			markers.PlanStart = true
		}
	}
	if !sawMarker {
		return nil
	}
	markers.RemainingContent = strings.Join(kept, "\n")
	return markers
}

// applyGoalControlMarkers mutates the session goal/plan state after an
// assistant turn and re-emits the session when anything changed.
func (app *DesktopAgentApp) applyGoalControlMarkers(sessionID string, markers *goalControlMarkers) {
	if markers == nil {
		return
	}
	var updated *ChatSession
	var err error
	switch {
	case strings.TrimSpace(markers.GoalStart) != "":
		updated, err = app.store.setSessionGoal(sessionID, markers.GoalStart)
	case markers.Pause:
		updated, err = app.store.setSessionGoalStatus(sessionID, "paused")
	case markers.Resume:
		updated, err = app.store.setSessionGoalStatus(sessionID, "active")
	case markers.Complete:
		updated, err = app.store.completeSessionGoal(sessionID)
	}
	if err == nil && updated != nil {
		app.emitSessionsChanged(updated)
	}
	if markers.PlanStart {
		if planUpdated, planErr := app.store.setSessionPlanMode(sessionID, true); planErr == nil {
			app.emitSessionsChanged(planUpdated)
		}
	}
}

type completeGoalArguments struct {
	Result string `json:"result"`
}

func completeGoalToolDefinition() any {
	return functionTool(
		completeGoalToolName,
		"在当前长期目标已经真实达成后立即结束目标模式；若存在执行计划，必须先完成并签收全部步骤。成功调用后本轮立即收口，不要再继续操作",
		map[string]any{
			"result": map[string]any{
				"type":        "string",
				"description": "目标达成结果的简短总结，说明完成了什么以及验证结果",
			},
		},
		[]string{"result"},
	)
}

func parseCompleteGoalArguments(arguments string) (completeGoalArguments, error) {
	decoder := json.NewDecoder(bytes.NewBufferString(arguments))
	decoder.DisallowUnknownFields()
	payload := completeGoalArguments{}
	if err := decoder.Decode(&payload); err != nil {
		return payload, fmt.Errorf("complete_goal 参数无效：%w", err)
	}
	var extra any
	if err := decoder.Decode(&extra); err != io.EOF {
		if err == nil {
			return payload, errors.New("complete_goal 参数只能包含一个 JSON 对象")
		}
		return payload, fmt.Errorf("complete_goal 参数尾部无效：%w", err)
	}
	payload.Result = strings.TrimSpace(payload.Result)
	if payload.Result == "" || len([]rune(payload.Result)) > 2_000 {
		return payload, errors.New("complete_goal 的 result 为空或超过 2000 字符")
	}
	return payload, nil
}

func (app *DesktopAgentApp) executeCompleteGoal(ctx context.Context, sessionID string, call modelToolCall) (string, error) {
	if err := ctx.Err(); err != nil {
		return "", err
	}
	payload, err := parseCompleteGoalArguments(call.Function.Arguments)
	if err != nil {
		return "", err
	}
	session := app.store.getSession(sessionID)
	if session == nil {
		return "", errors.New("会话不存在")
	}
	if strings.TrimSpace(session.Goal) == "" {
		return "", errors.New("当前没有正在进行的目标")
	}
	if plan := session.WorkflowPlan; plan != nil && plan.Status != workflowPlanCompleted {
		return "", fmt.Errorf("计划尚未完成，不能结束目标；当前进度 %d/%d", plan.CurrentStepIndex, len(plan.Steps))
	}
	updated, err := app.store.completeSessionGoalWithResult(sessionID, payload.Result)
	if err != nil {
		return "", err
	}
	app.emitSessionsChanged(updated)
	return marshalToolResult(map[string]any{
		"success":   true,
		"completed": true,
		"result":    payload.Result,
	}), nil
}
