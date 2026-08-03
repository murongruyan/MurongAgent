package main

import (
	"regexp"
	"strings"
)

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
