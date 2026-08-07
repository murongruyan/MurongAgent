package main

import (
	"context"
	"strings"
	"testing"
)

func TestParseCompleteGoalArgumentsRequiresResult(t *testing.T) {
	if _, err := parseCompleteGoalArguments(`{"result":""}`); err == nil {
		t.Fatal("empty result must be rejected")
	}
	payload, err := parseCompleteGoalArguments(`{"result":"已完成并通过验证"}`)
	if err != nil || payload.Result != "已完成并通过验证" {
		t.Fatalf("unexpected complete_goal payload: %#v, %v", payload, err)
	}
}

func TestCompleteGoalClearsActiveGoalImmediately(t *testing.T) {
	t.Setenv("MURONG_DESKTOP_DATA_DIR", t.TempDir())
	store, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	session, err := store.createSession("目标测试")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.setSessionGoal(session.ID, "完成目标测试"); err != nil {
		t.Fatal(err)
	}
	app := &DesktopAgentApp{store: store}
	result, err := app.executeCompleteGoal(context.Background(), session.ID, modelToolCall{
		Function: modelToolFunction{Arguments: `{"result":"测试已通过"}`},
	})
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(result, `"completed":true`) {
		t.Fatalf("unexpected tool result: %s", result)
	}
	updated := store.getSession(session.ID)
	if updated == nil || updated.Goal != "" || updated.GoalStatus != "" {
		t.Fatalf("goal was not cleared: %#v", updated)
	}
	if len(updated.Messages) == 0 || !strings.Contains(updated.Messages[len(updated.Messages)-1].Content, "测试已通过") {
		t.Fatalf("completion result was not persisted: %#v", updated.Messages)
	}
}

func TestParseGoalControlMarkersPlainReplyReturnsNil(t *testing.T) {
	if parseGoalControlMarkers("好的，我继续推进。") != nil {
		t.Fatal("plain reply must not be treated as control markers")
	}
	if parseGoalControlMarkers("") != nil {
		t.Fatal("empty reply must not be treated as control markers")
	}
}

func TestParseGoalControlMarkersGoalStartStripsMarkerLine(t *testing.T) {
	markers := parseGoalControlMarkers("[goal:start] 帮我重构登录模块并补测试\n好的，我先阅读现有实现。")
	if markers == nil {
		t.Fatal("expected goal:start markers")
	}
	if markers.GoalStart != "帮我重构登录模块并补测试" {
		t.Fatalf("unexpected goal start: %q", markers.GoalStart)
	}
	if got := markers.RemainingContent; got != "好的，我先阅读现有实现。" {
		t.Fatalf("unexpected remaining content: %q", got)
	}
	if markers.Complete {
		t.Fatal("complete must be false")
	}
}

func TestParseGoalControlMarkersCompleteKeepsExplanation(t *testing.T) {
	markers := parseGoalControlMarkers("[goal:complete]\n登录模块重构完成，测试全部通过。")
	if markers == nil || !markers.Complete {
		t.Fatal("expected goal:complete marker")
	}
	if got := markers.RemainingContent; got != "登录模块重构完成，测试全部通过。" {
		t.Fatalf("unexpected remaining content: %q", got)
	}
}

func TestParseGoalControlMarkersPauseResumeAndPlanStart(t *testing.T) {
	pause := parseGoalControlMarkers("[goal:pause]\n先等我确认。")
	if pause == nil || !pause.Pause {
		t.Fatal("expected goal:pause marker")
	}
	resume := parseGoalControlMarkers("[goal:resume]\n继续。")
	if resume == nil || !resume.Resume {
		t.Fatal("expected goal:resume marker")
	}
	plan := parseGoalControlMarkers("我先梳理思路。\n[plan:start]\n然后执行。")
	if plan == nil || !plan.PlanStart {
		t.Fatal("expected plan:start marker")
	}
	if got := plan.RemainingContent; got != "我先梳理思路。\n然后执行。" {
		t.Fatalf("unexpected remaining content: %q", got)
	}
}

func TestParseGoalControlMarkersCombined(t *testing.T) {
	markers := parseGoalControlMarkers("[goal:start] 修复支付流程\n[plan:start]\n[goal:complete]")
	if markers == nil {
		t.Fatal("expected markers")
	}
	if markers.GoalStart != "修复支付流程" || !markers.PlanStart || !markers.Complete {
		t.Fatalf("unexpected markers: %+v", markers)
	}
	if got := markers.RemainingContent; got != "" {
		t.Fatalf("expected empty remaining content, got %q", got)
	}
}
