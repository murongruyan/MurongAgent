package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sort"
	"strings"
)

const (
	maxSubagentBatchTasks      = 64
	maxSubagentTaskResultRunes = 12_000
)

type subagentPolicy struct {
	Label            string
	Goal             string
	Model            string
	ReasoningEffort  string
	TemplateID       string
	TemplateTitle    string
	EnabledBuiltins  []string
	EnabledFileOps   []string
	AllowedToolNames map[string]bool
}

type subagentTaskSpec struct {
	Index     int
	Label     string
	Goal      string
	DependsOn []int
}

type subagentExecutionPlan struct {
	Label      string
	ParentGoal string
	Tasks      []subagentTaskSpec
}

type subagentTaskResult struct {
	Index         int    `json:"index"`
	Label         string `json:"label"`
	Goal          string `json:"goal"`
	DependsOn     []int  `json:"dependsOn,omitempty"`
	TemplateID    string `json:"templateId,omitempty"`
	TemplateTitle string `json:"templateTitle,omitempty"`
	Status        string `json:"status"`
	Output        string `json:"output,omitempty"`
	Error         string `json:"error,omitempty"`
}

type subagentTaskOutcome struct {
	Index  int
	Output string
	Err    error
}

var subagentReadOnlyKnowledgeTools = map[string]bool{
	"memory_list": true, "memory_search": true, "memory_read": true, "read_skill": true,
}

func (app *DesktopAgentApp) executeSubagentTool(
	ctx context.Context, sessionID string, config desktopConfig, workspace *localWorkspace, call modelToolCall, raw map[string]json.RawMessage,
) (string, error) {
	runInBackground := rawBool(raw, "background", false)
	plan, err := parseSubagentExecutionPlan(raw)
	if err != nil {
		return "", err
	}
	if plan != nil {
		policies := make([]subagentPolicy, len(plan.Tasks))
		for index, task := range plan.Tasks {
			policy, policyErr := resolveSubagentPolicy(config, call.Function.Name, subagentRawForTask(raw, task.Goal))
			if policyErr != nil {
				return "", fmt.Errorf("子代理任务 %d 无效：%w", task.Index, policyErr)
			}
			policies[index] = policy
		}
		risk := subagentPoliciesRisk(policies)
		if err := app.authorizeTool(ctx, config, ApprovalRequest{
			ID: newID("approval"), SessionID: sessionID, ToolName: call.Function.Name,
			Summary: fmt.Sprintf("启动子代理批次 · %s（%d 项）", plan.Label, len(plan.Tasks)),
			Detail:  buildSubagentPlanApprovalDetail(*plan, policies, risk), Arguments: call.Function.Arguments, Risk: risk,
		}); err != nil {
			return "", err
		}

		// A batch receives one explicit approval for its complete, bounded tool budget.
		// Children then run without competing approval dialogs; workspace and tool-budget guards still apply.
		batchConfig := config
		batchConfig.ApprovalMode = approvalYolo
		if runInBackground {
			return app.queueBackgroundSubagentPlan(sessionID, batchConfig, *plan, policies)
		}
		results := executeSubagentDependencyPlan(ctx, *plan, subagentPlanConcurrency(*plan), func(taskContext context.Context, task subagentTaskSpec) (string, error) {
			return app.runSubagentPolicy(taskContext, sessionID, batchConfig, workspace, policies[task.Index-1])
		})
		annotateSubagentTaskResults(results, policies)
		return marshalSubagentPlanResult(*plan, results), nil
	}

	policy, err := resolveSubagentPolicy(config, call.Function.Name, raw)
	if err != nil {
		return "", err
	}
	risk := subagentPolicyRisk(policy)
	if err := app.authorizeTool(ctx, config, ApprovalRequest{
		ID: newID("approval"), SessionID: sessionID, ToolName: call.Function.Name,
		Summary: "启动 " + policy.Label + " 子代理", Detail: buildSingleSubagentApprovalDetail(policy, risk, runInBackground), Arguments: call.Function.Arguments, Risk: risk,
	}); err != nil {
		return "", err
	}
	if runInBackground {
		backgroundConfig := config
		backgroundConfig.ApprovalMode = approvalYolo
		plan := subagentExecutionPlan{
			Label: policy.Label, ParentGoal: policy.Goal,
			Tasks: []subagentTaskSpec{{Index: 1, Label: policy.Label, Goal: policy.Goal}},
		}
		return app.queueBackgroundSubagentPlan(sessionID, backgroundConfig, plan, []subagentPolicy{policy})
	}
	return app.runSubagentPolicy(ctx, sessionID, config, workspace, policy)
}

func (app *DesktopAgentApp) runSubagentPolicy(
	ctx context.Context, sessionID string, config desktopConfig, workspace *localWorkspace, policy subagentPolicy,
) (string, error) {
	childConfig := config
	childConfig.EnabledBuiltinTools = policy.EnabledBuiltins
	childConfig.EnabledFileOperations = policy.EnabledFileOps
	profilePointer := findProviderProfile(childConfig.ProviderProfiles, childConfig.ActiveProviderProfileID)
	if profilePointer == nil || profilePointer.ProtectedAPIKey == "" {
		return "", errors.New("子代理无法读取当前模型连接")
	}
	profile := *profilePointer
	if policy.Model != "" {
		profile.Model = policy.Model
	}
	if policy.ReasoningEffort != "" {
		profile.ReasoningEffort = policy.ReasoningEffort
	}
	plainKey, err := unprotectSecret(profile.ProtectedAPIKey)
	if err != nil {
		return "", fmt.Errorf("子代理无法解密 API Key：%w", err)
	}
	defer clearBytes(plainKey)
	allTools := app.toolDefinitions(childConfig)
	tools := make([]any, 0, len(allTools))
	allowedNames := map[string]bool{}
	for _, definition := range allTools {
		name := functionToolName(definition)
		if !isSubagentDirectToolAllowed(name) {
			continue
		}
		if isKnowledgeToolName(name) && !subagentReadOnlyKnowledgeTools[name] {
			continue
		}
		if len(policy.AllowedToolNames) > 0 && !policy.AllowedToolNames[name] && !subagentReadOnlyKnowledgeTools[name] {
			continue
		}
		tools = append(tools, definition)
		allowedNames[name] = true
	}
	if len(tools) == 0 {
		return "", errors.New("子代理没有可用工具")
	}
	system := app.systemPrompt(childConfig) + fmt.Sprintf(`

You are an isolated Murong %s subagent.
Complete only the delegated goal below. Do not ask the user questions and do not launch more subagents.
Use tools for evidence. Stay within the selected project and the tool budget. Return a self-contained result for the parent agent.
Runtime child model: %s; reasoning effort: %s.`, policy.Label, profile.Model, profile.ReasoningEffort)
	messages := []modelMessage{
		{Role: "system", Content: system},
		{Role: "user", Content: "Delegated goal:\n" + policy.Goal},
	}
	client := newModelClientWithGeneration(childConfig.Temperature, childConfig.MaxTokens)
	trace := []string{}
	lastContent := ""
	maxIterations := childConfig.MaxToolIterations
	if maxIterations < 1 || maxIterations > 32 {
		maxIterations = 32
	}
	for iteration := 1; iteration <= maxIterations; iteration++ {
		if err := ctx.Err(); err != nil {
			return "", err
		}
		result, err := client.streamChat(ctx, profile, string(plainKey), messages, tools, nil)
		if err != nil {
			return "", err
		}
		if err := app.store.recordModelUsage(sessionID, profile, result.Usage); err != nil {
			return "", err
		}
		messages = append(messages, modelMessage{Role: "assistant", Content: result.Content, ToolCalls: result.ToolCalls})
		if strings.TrimSpace(result.Content) != "" {
			lastContent = result.Content
		}
		if len(result.ToolCalls) == 0 {
			return marshalToolResult(map[string]any{
				"success": true, "preset": policy.Label, "model": profile.Model,
				"reasoningEffort": profile.ReasoningEffort, "templateId": policy.TemplateID, "templateTitle": policy.TemplateTitle,
				"iterations": iteration, "toolsUsed": trace, "result": result.Content,
			}), nil
		}
		for _, innerCall := range result.ToolCalls {
			trace = append(trace, innerCall.Function.Name)
			var output string
			var toolErr error
			if !allowedNames[innerCall.Function.Name] {
				toolErr = fmt.Errorf("子代理工具 %s 不在本次预算内", innerCall.Function.Name)
			} else {
				output, toolErr = app.executeTool(ctx, sessionID, childConfig, workspace, innerCall)
			}
			if toolErr != nil {
				output = marshalToolResult(map[string]any{"success": false, "error": toolErr.Error()})
			}
			messages = append(messages, modelMessage{Role: "tool", ToolCallID: innerCall.ID, Name: innerCall.Function.Name, Content: output})
		}
	}
	return marshalToolResult(map[string]any{
		"success": false, "preset": policy.Label, "templateId": policy.TemplateID, "templateTitle": policy.TemplateTitle,
		"iterations": maxIterations, "toolsUsed": trace,
		"partialResult": lastContent, "error": "子代理达到 32 次工具迭代上限",
	}), nil
}

func isSubagentDirectToolAllowed(name string) bool {
	return name != "" && name != askUserToolName && name != completeStepToolName && !isSubagentToolName(name)
}

func subagentPolicyRisk(policy subagentPolicy) string {
	if subagentStringSliceContains(policy.EnabledBuiltins, "shell") || subagentStringSliceContains(policy.EnabledBuiltins, "code_edit") ||
		subagentStringSliceContains(policy.EnabledFileOps, "write") || subagentStringSliceContains(policy.EnabledFileOps, "delete") || subagentStringSliceContains(policy.EnabledFileOps, "chmod") {
		return "high"
	}
	return "low"
}

func subagentStringSliceContains(values []string, wanted string) bool {
	for _, value := range values {
		if value == wanted {
			return true
		}
	}
	return false
}

func subagentPoliciesRisk(policies []subagentPolicy) string {
	for _, policy := range policies {
		if subagentPolicyRisk(policy) == "high" {
			return "high"
		}
	}
	return "low"
}

func parseSubagentExecutionPlan(raw map[string]json.RawMessage) (*subagentExecutionPlan, error) {
	batchData, hasBatch := raw["batchGoals"]
	parallelData, hasParallel := raw["parallelTasks"]
	hasBatch = hasBatch && strings.TrimSpace(string(batchData)) != "" && strings.TrimSpace(string(batchData)) != "null"
	hasParallel = hasParallel && strings.TrimSpace(string(parallelData)) != "" && strings.TrimSpace(string(parallelData)) != "null"
	if !hasBatch && !hasParallel {
		return nil, nil
	}
	if hasBatch && hasParallel {
		return nil, errors.New("batchGoals 与 parallelTasks 不能同时使用")
	}
	parentGoal := rawString(raw, "goal", "")
	if parentGoal == "" || len([]rune(parentGoal)) > 20_000 {
		return nil, errors.New("子代理批次总目标为空或超过 20000 字符")
	}
	label := rawString(raw, "batchLabel", "")
	if len([]rune(label)) > 100 {
		return nil, errors.New("子代理批次名称超过 100 字符")
	}

	plan := &subagentExecutionPlan{ParentGoal: parentGoal}
	if hasBatch {
		var goals []string
		if err := json.Unmarshal(batchData, &goals); err != nil {
			return nil, errors.New("batchGoals 必须是字符串数组")
		}
		if len(goals) < 2 || len(goals) > maxSubagentBatchTasks {
			return nil, fmt.Errorf("batchGoals 必须包含 2 到 %d 个子目标", maxSubagentBatchTasks)
		}
		plan.Label = label
		if plan.Label == "" {
			plan.Label = fmt.Sprintf("并行批次 %d 项", len(goals))
		}
		for index, goal := range goals {
			goal = strings.TrimSpace(goal)
			if goal == "" || len([]rune(goal)) > 20_000 {
				return nil, fmt.Errorf("batchGoals 第 %d 项为空或超过 20000 字符", index+1)
			}
			plan.Tasks = append(plan.Tasks, subagentTaskSpec{Index: index + 1, Label: fmt.Sprintf("任务 %d", index+1), Goal: goal})
		}
		return plan, nil
	}

	var wireTasks []struct {
		Label     string `json:"label"`
		Goal      string `json:"goal"`
		DependsOn []int  `json:"dependsOn"`
	}
	if err := decodeStrictJSON(parallelData, &wireTasks); err != nil {
		return nil, errors.New("parallelTasks 必须是结构化任务数组")
	}
	if len(wireTasks) < 2 || len(wireTasks) > maxSubagentBatchTasks {
		return nil, fmt.Errorf("parallelTasks 必须包含 2 到 %d 个子任务", maxSubagentBatchTasks)
	}
	plan.Label = label
	if plan.Label == "" {
		plan.Label = fmt.Sprintf("依赖并行编排 %d 项", len(wireTasks))
	}
	for index, wire := range wireTasks {
		goal := strings.TrimSpace(wire.Goal)
		if goal == "" || len([]rune(goal)) > 20_000 {
			return nil, fmt.Errorf("parallelTasks 第 %d 项目标为空或超过 20000 字符", index+1)
		}
		label := strings.TrimSpace(wire.Label)
		if len([]rune(label)) > 100 {
			return nil, fmt.Errorf("parallelTasks 第 %d 项标签超过 100 字符", index+1)
		}
		if label == "" {
			label = fmt.Sprintf("任务 %d", index+1)
		}
		seenDependencies := map[int]bool{}
		dependencies := make([]int, 0, len(wire.DependsOn))
		for _, dependency := range wire.DependsOn {
			if dependency < 1 || dependency > len(wireTasks) {
				return nil, fmt.Errorf("parallelTasks 第 %d 项包含越界依赖 %d", index+1, dependency)
			}
			if dependency == index+1 {
				return nil, fmt.Errorf("parallelTasks 第 %d 项不能依赖自身", index+1)
			}
			if !seenDependencies[dependency] {
				seenDependencies[dependency] = true
				dependencies = append(dependencies, dependency)
			}
		}
		sort.Ints(dependencies)
		plan.Tasks = append(plan.Tasks, subagentTaskSpec{Index: index + 1, Label: label, Goal: goal, DependsOn: dependencies})
	}
	if err := validateSubagentTaskGraph(plan.Tasks); err != nil {
		return nil, err
	}
	return plan, nil
}

func validateSubagentTaskGraph(tasks []subagentTaskSpec) error {
	visiting := make([]bool, len(tasks))
	visited := make([]bool, len(tasks))
	var visit func(int) bool
	visit = func(index int) bool {
		if visiting[index] {
			return true
		}
		if visited[index] {
			return false
		}
		visiting[index] = true
		for _, dependency := range tasks[index].DependsOn {
			if visit(dependency - 1) {
				return true
			}
		}
		visiting[index] = false
		visited[index] = true
		return false
	}
	for index := range tasks {
		if visit(index) {
			return errors.New("parallelTasks 包含循环依赖")
		}
	}
	return nil
}

func subagentRawForTask(raw map[string]json.RawMessage, goal string) map[string]json.RawMessage {
	result := make(map[string]json.RawMessage, len(raw))
	for key, value := range raw {
		result[key] = append(json.RawMessage(nil), value...)
	}
	encodedGoal, _ := json.Marshal(goal)
	result["goal"] = encodedGoal
	delete(result, "batchGoals")
	delete(result, "parallelTasks")
	delete(result, "batchLabel")
	delete(result, "background")
	return result
}

func buildSubagentPlanApprovalDetail(plan subagentExecutionPlan, policies []subagentPolicy, risk string) string {
	lines := []string{plan.ParentGoal, "", "满足依赖的子任务会立即并行启动；批准后各子代理只在声明的工具预算内运行，不再逐次弹出竞争审批。"}
	if risk == "high" {
		lines = append(lines, "本批次申请了写入、代码编辑或 Shell 权限。")
	}
	for _, task := range plan.Tasks {
		line := fmt.Sprintf("%d. [%s] %s", task.Index, task.Label, task.Goal)
		if task.Index <= len(policies) && policies[task.Index-1].TemplateID != "" {
			policy := policies[task.Index-1]
			line += fmt.Sprintf("（模板：%s · %s）", policy.TemplateTitle, policy.TemplateID)
		}
		if len(task.DependsOn) > 0 {
			line += fmt.Sprintf("（依赖 %v）", task.DependsOn)
		}
		lines = append(lines, line)
	}
	return truncateRunes(strings.Join(lines, "\n"), 4000)
}

func buildSingleSubagentApprovalDetail(policy subagentPolicy, risk string, background bool) string {
	lines := []string{policy.Goal}
	if policy.TemplateID != "" {
		lines = append(lines, fmt.Sprintf("项目模板：%s（%s）", policy.TemplateTitle, policy.TemplateID))
	}
	if background {
		lines = append(lines, "", "任务会在后台继续运行；本次批准覆盖下列已声明且受项目边界约束的工具预算。")
	}
	if risk == "high" {
		lines = append(lines, "本任务申请了写入、代码编辑或 Shell 权限。")
	}
	if len(policy.AllowedToolNames) > 0 {
		names := make([]string, 0, len(policy.AllowedToolNames))
		for name := range policy.AllowedToolNames {
			names = append(names, name)
		}
		sort.Strings(names)
		lines = append(lines, "允许工具："+strings.Join(names, "、"))
	} else {
		lines = append(lines, "允许工具：父级当前启用工具与本次声明能力的交集（不含子代理递归派发）。")
	}
	return truncateRunes(strings.Join(lines, "\n"), 4_000)
}

func annotateSubagentTaskResults(results []subagentTaskResult, policies []subagentPolicy) {
	for index := range results {
		if index >= len(policies) {
			break
		}
		results[index].TemplateID = policies[index].TemplateID
		results[index].TemplateTitle = policies[index].TemplateTitle
	}
}

func subagentPlanConcurrency(plan subagentExecutionPlan) int {
	if len(plan.Tasks) < 1 {
		return 1
	}
	return len(plan.Tasks)
}

func executeSubagentDependencyPlan(
	ctx context.Context,
	plan subagentExecutionPlan,
	maxConcurrent int,
	runner func(context.Context, subagentTaskSpec) (string, error),
) []subagentTaskResult {
	if maxConcurrent < 1 {
		maxConcurrent = 1
	}
	results := make([]subagentTaskResult, len(plan.Tasks))
	for index, task := range plan.Tasks {
		results[index] = subagentTaskResult{
			Index: task.Index, Label: task.Label, Goal: task.Goal,
			DependsOn: append([]int(nil), task.DependsOn...), Status: "pending",
		}
	}
	outcomes := make(chan subagentTaskOutcome, len(plan.Tasks))
	running, finished := 0, 0
	ctxDone := ctx.Done()
	cancellationHandled := false

	for finished < len(results) {
		if ctx.Err() != nil && !cancellationHandled {
			for index := range results {
				if results[index].Status == "pending" {
					results[index].Status = "cancelled"
					results[index].Error = "父任务已取消，子代理未启动"
					finished++
				}
			}
			cancellationHandled = true
			ctxDone = nil
		}

		if !cancellationHandled {
			changed := true
			for changed {
				changed = false
				for index, task := range plan.Tasks {
					if results[index].Status != "pending" {
						continue
					}
					failed := []string{}
					for _, dependency := range task.DependsOn {
						dependencyResult := results[dependency-1]
						if dependencyResult.Status == "failed" || dependencyResult.Status == "skipped" || dependencyResult.Status == "cancelled" {
							failed = append(failed, dependencyResult.Label)
						}
					}
					if len(failed) > 0 {
						results[index].Status = "skipped"
						results[index].Error = "依赖任务未成功完成：" + strings.Join(failed, "、")
						finished++
						changed = true
					}
				}
			}

			for index, task := range plan.Tasks {
				if running >= maxConcurrent || results[index].Status != "pending" {
					continue
				}
				ready := true
				for _, dependency := range task.DependsOn {
					if results[dependency-1].Status != "completed" {
						ready = false
						break
					}
				}
				if !ready {
					continue
				}
				results[index].Status = "running"
				running++
				go func(taskIndex int, taskValue subagentTaskSpec) {
					output, err := runner(ctx, taskValue)
					outcomes <- subagentTaskOutcome{Index: taskIndex, Output: output, Err: err}
				}(index, task)
			}
		}

		if finished == len(results) {
			break
		}
		if running == 0 {
			for index := range results {
				if results[index].Status == "pending" {
					results[index].Status = "failed"
					results[index].Error = "依赖调度无法继续"
					finished++
				}
			}
			continue
		}

		var outcome subagentTaskOutcome
		if ctxDone == nil {
			outcome = <-outcomes
		} else {
			select {
			case outcome = <-outcomes:
			case <-ctxDone:
				continue
			}
		}
		running--
		result := &results[outcome.Index]
		result.Output = truncateRunes(outcome.Output, maxSubagentTaskResultRunes)
		if outcome.Err != nil {
			if errors.Is(outcome.Err, context.Canceled) || errors.Is(outcome.Err, context.DeadlineExceeded) {
				result.Status = "cancelled"
			} else {
				result.Status = "failed"
			}
			result.Error = truncateRunes(outcome.Err.Error(), 2000)
		} else if success, detail := subagentOutputSuccess(outcome.Output); !success {
			result.Status = "failed"
			result.Error = truncateRunes(detail, 2000)
		} else {
			result.Status = "completed"
		}
		finished++
	}
	return results
}

func subagentOutputSuccess(output string) (bool, string) {
	var envelope struct {
		Success *bool  `json:"success"`
		Error   string `json:"error"`
	}
	if json.Unmarshal([]byte(output), &envelope) == nil && envelope.Success != nil {
		if *envelope.Success {
			return true, ""
		}
		if strings.TrimSpace(envelope.Error) != "" {
			return false, strings.TrimSpace(envelope.Error)
		}
		return false, "子代理返回失败结果"
	}
	return true, ""
}

func marshalSubagentPlanResult(plan subagentExecutionPlan, results []subagentTaskResult) string {
	completed, failed, skipped, cancelled := 0, 0, 0, 0
	for _, result := range results {
		switch result.Status {
		case "completed":
			completed++
		case "failed":
			failed++
		case "skipped":
			skipped++
		case "cancelled":
			cancelled++
		}
	}
	return marshalToolResult(map[string]any{
		"success": failed == 0 && skipped == 0 && cancelled == 0,
		"batch":   plan.Label, "parentGoal": plan.ParentGoal,
		"total": len(results), "completed": completed, "failed": failed, "skipped": skipped, "cancelled": cancelled,
		"concurrencyLimit": subagentPlanConcurrency(plan), "results": results,
	})
}

func resolveSubagentPolicy(config desktopConfig, toolName string, raw map[string]json.RawMessage) (subagentPolicy, error) {
	goal := rawString(raw, "goal", "")
	if goal == "" || len([]rune(goal)) > 20_000 {
		return subagentPolicy{}, errors.New("子代理 goal 为空或超过 20000 字符")
	}
	policy := subagentPolicy{
		Label: toolName, Goal: goal, Model: rawString(raw, "model", ""), ReasoningEffort: rawString(raw, "reasoningEffort", ""),
		EnabledBuiltins: []string{"file"}, EnabledFileOps: []string{"read", "list", "exists"},
	}
	switch toolName {
	case "explore":
	case "research", "security_review":
		policy.EnabledBuiltins = append(policy.EnabledBuiltins, "web_search", "web_fetch")
	case "review":
	case "subagent_launch":
		policy.Label = "general"
		template := resolveProjectSubagentTemplate(config, goal, rawString(raw, "templateId", ""))
		if template != nil {
			policy.Label = template.Title
			policy.TemplateID = template.ID
			policy.TemplateTitle = template.Title
			if policy.Model == "" {
				policy.Model = template.PreferredModel
			}
			if policy.ReasoningEffort == "" {
				policy.ReasoningEffort = template.PreferredReasoningEffort
			}
		}
		policy.EnabledBuiltins = append(policy.EnabledBuiltins, "code_search")
		enableWebSearch := true
		allowWriteAccess, allowCodeEdits, allowShell := false, false, false
		if template != nil {
			enableWebSearch = template.EnableWebSearch
			allowWriteAccess = template.AllowWriteAccess
			allowCodeEdits = template.AllowCodeEdits
			allowShell = template.AllowShell
		}
		if value, exists := rawOptionalBool(raw, "enableWebSearch"); exists {
			enableWebSearch = value
		}
		if value, exists := rawOptionalBool(raw, "allowWriteAccess"); exists {
			allowWriteAccess = value
		}
		if value, exists := rawOptionalBool(raw, "allowCodeEdits"); exists {
			allowCodeEdits = value
		}
		if value, exists := rawOptionalBool(raw, "allowShell"); exists {
			allowShell = value
		}
		if enableWebSearch {
			policy.EnabledBuiltins = append(policy.EnabledBuiltins, "web_search", "web_fetch")
		}
		if allowWriteAccess {
			policy.EnabledFileOps = append(policy.EnabledFileOps, "write", "delete", "chmod")
		}
		if allowCodeEdits {
			policy.EnabledBuiltins = append(policy.EnabledBuiltins, "code_edit")
		}
		if allowShell {
			policy.EnabledBuiltins = append(policy.EnabledBuiltins, "shell")
		}
		policy.AllowedToolNames = parseAllowedSubagentTools(raw["allowedTools"])
		for name := range policy.AllowedToolNames {
			if strings.HasPrefix(name, "github_") {
				policy.EnabledBuiltins = append(policy.EnabledBuiltins, "github")
				break
			}
		}
	default:
		return subagentPolicy{}, fmt.Errorf("未知子代理预设：%s", toolName)
	}
	if config.SubagentProfileEnabled {
		if policy.Model == "" {
			policy.Model = config.SubagentModel
		}
		if policy.ReasoningEffort == "" {
			policy.ReasoningEffort = config.SubagentReasoningEffort
		}
	}
	if policy.ReasoningEffort == "" {
		switch toolName {
		case "explore":
			policy.ReasoningEffort = "medium"
		case "research", "review", "security_review":
			policy.ReasoningEffort = "high"
		}
	}
	policy.Model = normalizeExecutionProfileModel(policy.Model)
	policy.ReasoningEffort = normalizeExecutionProfileReasoning(policy.ReasoningEffort)
	policy.EnabledBuiltins = intersectStrings(policy.EnabledBuiltins, config.EnabledBuiltinTools)
	policy.EnabledFileOps = intersectStrings(policy.EnabledFileOps, config.EnabledFileOperations)
	return policy, nil
}

func rawOptionalBool(raw map[string]json.RawMessage, key string) (bool, bool) {
	data, exists := raw[key]
	if !exists || len(data) == 0 || string(data) == "null" {
		return false, false
	}
	var value bool
	if json.Unmarshal(data, &value) != nil {
		return false, false
	}
	return value, true
}

func resolveProjectSubagentTemplate(config desktopConfig, goal, explicitTemplateID string) *ProjectSubagentTemplate {
	templates := projectSubagentTemplates(config)
	explicitTemplateID = strings.TrimSpace(explicitTemplateID)
	if explicitTemplateID != "" {
		for index := range templates {
			if templates[index].Enabled && templates[index].ID == explicitTemplateID {
				return &templates[index]
			}
		}
	}
	type templateMatch struct {
		Template ProjectSubagentTemplate
		Count    int
	}
	matches := []templateMatch{}
	lowerGoal := strings.ToLower(goal)
	for _, template := range templates {
		if !template.Enabled {
			continue
		}
		count := 0
		for _, matcher := range template.GoalMatchers {
			if matcher = strings.TrimSpace(matcher); matcher != "" && strings.Contains(lowerGoal, strings.ToLower(matcher)) {
				count++
			}
		}
		if count > 0 {
			matches = append(matches, templateMatch{Template: template, Count: count})
		}
	}
	if len(matches) == 0 {
		return nil
	}
	sort.SliceStable(matches, func(i, j int) bool {
		if matches[i].Count != matches[j].Count {
			return matches[i].Count > matches[j].Count
		}
		if len(matches[i].Template.GoalMatchers) != len(matches[j].Template.GoalMatchers) {
			return len(matches[i].Template.GoalMatchers) > len(matches[j].Template.GoalMatchers)
		}
		return len([]rune(matches[i].Template.Title)) < len([]rune(matches[j].Template.Title))
	})
	matched := matches[0].Template
	return &matched
}

func parseAllowedSubagentTools(data json.RawMessage) map[string]bool {
	if len(data) == 0 {
		return nil
	}
	var values []string
	if json.Unmarshal(data, &values) != nil || len(values) == 0 {
		return nil
	}
	result := map[string]bool{}
	for _, value := range values {
		normalized := strings.ToLower(strings.TrimSpace(value))
		switch {
		case normalized == "file" || strings.HasPrefix(normalized, "file("):
			if strings.Contains(normalized, "read") {
				result["read_file"] = true
			}
			if strings.Contains(normalized, "list") {
				result["list_files"] = true
			}
			if strings.Contains(normalized, "exists") {
				result["file_exists"] = true
			}
			if strings.Contains(normalized, "write") {
				result["write_file"], result["create_directory"] = true, true
			}
			if strings.Contains(normalized, "delete") {
				result["delete_path"] = true
			}
		case normalized == "shell":
			result["run_terminal"] = true
		case normalized == "code_search", normalized == "code_edit", normalized == "web_search", normalized == "web_fetch":
			result[normalized] = true
		default:
			result[normalized] = true
		}
	}
	return result
}

func intersectStrings(requested, enabled []string) []string {
	allowed := map[string]bool{}
	for _, value := range enabled {
		allowed[value] = true
	}
	result := []string{}
	seen := map[string]bool{}
	for _, value := range requested {
		if allowed[value] && !seen[value] {
			result = append(result, value)
			seen[value] = true
		}
	}
	return result
}

func functionToolName(raw any) string {
	wrapper, _ := raw.(map[string]any)
	function, _ := wrapper["function"].(map[string]any)
	name, _ := function["name"].(string)
	return name
}

func isSubagentToolName(name string) bool {
	switch name {
	case "subagent_launch", "subagent_jobs", "explore", "research", "review", "security_review":
		return true
	default:
		return false
	}
}

func isKnowledgeToolName(name string) bool {
	return subagentReadOnlyKnowledgeTools[name] || strings.HasPrefix(name, "create_global_") ||
		strings.HasPrefix(name, "create_project_") || name == "remember_memory" || name == "forget_memory" || name == "run_skill"
}
