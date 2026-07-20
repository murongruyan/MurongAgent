package main

import (
	"context"
	"encoding/json"
	"strings"
	"testing"
	"time"
)

func TestSubagentBackgroundJobPersistsAndRestoresAsInterrupted(t *testing.T) {
	t.Setenv("MURONG_DESKTOP_DATA_DIR", t.TempDir())
	store, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	session, err := store.createSession("后台任务")
	if err != nil {
		t.Fatal(err)
	}
	_, err = store.putSubagentBackgroundJob(session.ID, SubagentBackgroundJob{
		ID: "subagent_job_test", Label: "并行审查", ParentGoal: "审查项目", Status: "running",
		StatusMessage: "正在运行", TaskCount: 2, CreatedAt: time.Now().UnixMilli(), StartedAt: time.Now().UnixMilli(),
	})
	if err != nil {
		t.Fatal(err)
	}

	reloaded, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	loaded := reloaded.getSession(session.ID)
	if loaded == nil || len(loaded.BackgroundSubagentJobs) != 1 {
		t.Fatalf("background job was not restored: %#v", loaded)
	}
	job := loaded.BackgroundSubagentJobs[0]
	if job.Status != "interrupted" || job.StatusMessage != subagentInterruptedText || job.FinishedAt <= 0 {
		t.Fatalf("active job was not safely interrupted: %#v", job)
	}

	reloadedAgain, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	if persisted := reloadedAgain.getSession(session.ID).BackgroundSubagentJobs[0]; persisted.Status != "interrupted" {
		t.Fatalf("interrupted state was not persisted: %#v", persisted)
	}
}

func TestCancelSubagentJobPropagatesToActiveContext(t *testing.T) {
	t.Setenv("MURONG_DESKTOP_DATA_DIR", t.TempDir())
	store, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	session, err := store.createSession("取消后台任务")
	if err != nil {
		t.Fatal(err)
	}
	_, err = store.putSubagentBackgroundJob(session.ID, SubagentBackgroundJob{
		ID: "subagent_job_cancel", Label: "取消测试", ParentGoal: "等待取消", Status: "running",
		StatusMessage: "正在运行", TaskCount: 1, CreatedAt: time.Now().UnixMilli(), StartedAt: time.Now().UnixMilli(),
	})
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	app := &DesktopAgentApp{
		store: store,
		activeSubagentJobs: map[string]activeSubagentJob{
			"subagent_job_cancel": {SessionID: session.ID, Cancel: cancel},
		},
	}
	updated, err := app.CancelSubagentJob(CancelSubagentJobRequest{SessionID: session.ID, JobID: "subagent_job_cancel"})
	if err != nil {
		t.Fatal(err)
	}
	select {
	case <-ctx.Done():
	case <-time.After(time.Second):
		t.Fatal("background subagent context did not receive cancellation")
	}
	if len(updated.BackgroundSubagentJobs) != 1 || updated.BackgroundSubagentJobs[0].Status != "cancelling" {
		t.Fatalf("cancelling status was not persisted: %#v", updated.BackgroundSubagentJobs)
	}
}

func TestWindowsSubagentPlanStartsAllIndependentTasks(t *testing.T) {
	plan := subagentExecutionPlan{Label: "six", ParentGoal: "six", Tasks: make([]subagentTaskSpec, maxSubagentBatchTasks)}
	for index := range plan.Tasks {
		plan.Tasks[index] = subagentTaskSpec{Index: index + 1, Label: "task", Goal: "work"}
	}
	if concurrency := subagentPlanConcurrency(plan); concurrency != maxSubagentBatchTasks {
		t.Fatalf("Windows product entry limited a six-task batch to %d", concurrency)
	}
	started := make(chan struct{}, maxSubagentBatchTasks)
	release := make(chan struct{})
	done := make(chan []subagentTaskResult, 1)
	go func() {
		done <- executeSubagentDependencyPlan(context.Background(), plan, subagentPlanConcurrency(plan), func(context.Context, subagentTaskSpec) (string, error) {
			started <- struct{}{}
			<-release
			return `{"success":true}`, nil
		})
	}()
	for index := 0; index < maxSubagentBatchTasks; index++ {
		select {
		case <-started:
		case <-time.After(time.Second):
			t.Fatalf("only %d of %d independent tasks started concurrently", index, maxSubagentBatchTasks)
		}
	}
	close(release)
	select {
	case results := <-done:
		for _, result := range results {
			if result.Status != "completed" {
				t.Fatalf("unexpected result: %#v", result)
			}
		}
	case <-time.After(time.Second):
		t.Fatal("six-task batch did not finish")
	}
}

func TestSubagentJobsToolListsSummariesAndGetsOneResult(t *testing.T) {
	t.Setenv("MURONG_DESKTOP_DATA_DIR", t.TempDir())
	store, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	session, err := store.createSession("结果查询")
	if err != nil {
		t.Fatal(err)
	}
	_, err = store.putSubagentBackgroundJob(session.ID, SubagentBackgroundJob{
		ID: "subagent_job_result", Label: "结果", ParentGoal: "读取结果", Status: "completed",
		StatusMessage: "完成", TaskCount: 1, Completed: 1, CreatedAt: time.Now().UnixMilli(), FinishedAt: time.Now().UnixMilli(),
		Results: []subagentTaskResult{{Index: 1, Label: "任务", Goal: "读取", Status: "completed", Output: "FULL_RESULT_MARKER"}},
	})
	if err != nil {
		t.Fatal(err)
	}
	app := &DesktopAgentApp{store: store}
	listRaw := map[string]json.RawMessage{"action": json.RawMessage(`"list"`)}
	listed, err := app.executeSubagentJobsTool(context.Background(), session.ID, desktopConfig{ApprovalMode: approvalYolo}, modelToolCall{}, listRaw)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(listed, "FULL_RESULT_MARKER") {
		t.Fatal("list returned full background output instead of compact summaries")
	}
	getRaw := map[string]json.RawMessage{
		"action": json.RawMessage(`"get"`), "jobId": json.RawMessage(`"subagent_job_result"`),
	}
	detail, err := app.executeSubagentJobsTool(context.Background(), session.ID, desktopConfig{ApprovalMode: approvalYolo}, modelToolCall{}, getRaw)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(detail, "FULL_RESULT_MARKER") {
		t.Fatal("get did not return the selected background result")
	}
}
