package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"sort"
	"strings"
)

const (
	maxRunSkillQueryRunes       = 500
	maxRunSkillTaskRunes        = 20_000
	maxRunSkillInstructionRunes = 18_000
)

type runSkillArguments struct {
	Skill       string `json:"skill"`
	Task        string `json:"task"`
	Source      string `json:"source"`
	PreferRunAs string `json:"preferRunAs"`
	Background  bool   `json:"background"`
}

func parseRunSkillArguments(arguments string) (runSkillArguments, error) {
	decoder := json.NewDecoder(bytes.NewBufferString(arguments))
	decoder.DisallowUnknownFields()
	request := runSkillArguments{}
	if err := decoder.Decode(&request); err != nil {
		return runSkillArguments{}, fmt.Errorf("run_skill 参数无效：%w", err)
	}
	var extra any
	if err := decoder.Decode(&extra); err != io.EOF {
		if err == nil {
			return runSkillArguments{}, errors.New("run_skill 参数只能包含一个 JSON 对象")
		}
		return runSkillArguments{}, fmt.Errorf("run_skill 参数尾部无效：%w", err)
	}
	request.Skill = strings.TrimSpace(request.Skill)
	request.Task = strings.TrimSpace(request.Task)
	request.Source = strings.ToLower(strings.TrimSpace(request.Source))
	request.PreferRunAs = strings.ToLower(strings.TrimSpace(request.PreferRunAs))
	if request.Skill == "" || len([]rune(request.Skill)) > maxRunSkillQueryRunes {
		return runSkillArguments{}, fmt.Errorf("run_skill 的 skill 为空或超过 %d 字符", maxRunSkillQueryRunes)
	}
	if len([]rune(request.Task)) > maxRunSkillTaskRunes {
		return runSkillArguments{}, fmt.Errorf("run_skill 的 task 超过 %d 字符", maxRunSkillTaskRunes)
	}
	if request.Source == "" {
		request.Source = "any"
	}
	if request.Source != "any" && request.Source != "project" && request.Source != "global" {
		return runSkillArguments{}, errors.New("run_skill 的 source 必须是 any、project 或 global")
	}
	if request.PreferRunAs == "" {
		request.PreferRunAs = "skill-default"
	}
	if request.PreferRunAs != "skill-default" && request.PreferRunAs != "inline" && request.PreferRunAs != "subagent" {
		return runSkillArguments{}, errors.New("run_skill 的 preferRunAs 必须是 skill-default、inline 或 subagent")
	}
	return request, nil
}

func matchRunSkill(entries []scopedSkill, query string) (scopedSkill, []scopedSkill) {
	query = strings.TrimSpace(query)
	filter := func(predicate func(scopedSkill) bool) []scopedSkill {
		matches := []scopedSkill{}
		for _, entry := range entries {
			if predicate(entry) {
				matches = append(matches, entry)
			}
		}
		return matches
	}
	exactIDs := filter(func(entry scopedSkill) bool { return equalKnowledgeID(entry.Skill.ID, query) })
	if len(exactIDs) == 1 {
		return exactIDs[0], nil
	}
	if len(exactIDs) > 1 {
		return scopedSkill{}, exactIDs
	}
	exactTitles := filter(func(entry scopedSkill) bool {
		return strings.EqualFold(strings.TrimSpace(entry.Skill.Title), query)
	})
	if len(exactTitles) == 1 {
		return exactTitles[0], nil
	}
	if len(exactTitles) > 1 {
		return scopedSkill{}, exactTitles
	}
	lowerQuery := strings.ToLower(query)
	fuzzy := filter(func(entry scopedSkill) bool {
		haystack := strings.ToLower(entry.Skill.ID + "\n" + entry.Skill.Title + "\n" + entry.Skill.Description)
		return strings.Contains(haystack, lowerQuery)
	})
	if len(fuzzy) == 1 {
		return fuzzy[0], nil
	}
	return scopedSkill{}, fuzzy
}

func runSkillCandidate(entry scopedSkill) map[string]any {
	return map[string]any{
		"id": entry.Skill.ID, "title": entry.Skill.Title, "description": entry.Skill.Description,
		"source": entry.Scope, "runAs": strings.ToLower(entry.Skill.RunAs),
	}
}

func runSkillMetadata(entry scopedSkill) map[string]any {
	return map[string]any{
		"id": entry.Skill.ID, "title": entry.Skill.Title, "description": entry.Skill.Description,
		"source": entry.Scope, "runAs": strings.ToLower(entry.Skill.RunAs),
		"allowedTools": append([]string(nil), entry.Skill.AllowedTools...), "preferredModel": entry.Skill.PreferredModel,
	}
}

func (app *DesktopAgentApp) executeRunSkill(
	ctx context.Context, sessionID string, config desktopConfig, workspace *localWorkspace, call modelToolCall,
) (string, error) {
	request, err := parseRunSkillArguments(call.Function.Arguments)
	if err != nil {
		return "", err
	}
	entry, matches := matchRunSkill(app.scopedSkills(request.Source), request.Skill)
	if entry.Skill.ID == "" {
		candidates := make([]map[string]any, 0, len(matches))
		for _, match := range matches {
			candidates = append(candidates, runSkillCandidate(match))
		}
		if len(candidates) > 0 {
			return marshalToolResult(map[string]any{
				"success": false, "kind": "ambiguous", "error": "Skill 匹配不唯一，请改用 Skill ID 或限制 source", "candidates": candidates,
			}), nil
		}
		return marshalToolResult(map[string]any{
			"success": false, "kind": "not_found", "error": "没有找到已启用且匹配的 Skill", "query": request.Skill, "source": request.Source,
		}), nil
	}
	if len([]rune(entry.Skill.Content)) > maxRunSkillInstructionRunes {
		return marshalToolResult(map[string]any{
			"success": false, "kind": "too_large", "error": fmt.Sprintf("Skill 指令超过可执行上限 %d 字符，请拆分后再运行", maxRunSkillInstructionRunes),
			"skill": runSkillMetadata(entry),
		}), nil
	}
	runAs := strings.ToLower(entry.Skill.RunAs)
	if request.PreferRunAs != "skill-default" {
		runAs = request.PreferRunAs
	}
	if runAs != "subagent" {
		if err := app.authorizeTool(ctx, config, ApprovalRequest{
			ID: newID("approval"), SessionID: sessionID, ToolName: "run_skill", Risk: "low",
			Summary: "执行内联 Skill · " + entry.Skill.Title,
			Detail:  truncateRunes(request.Task+"\n\n"+entry.Skill.Content, 2_000), Arguments: call.Function.Arguments,
		}, "knowledge:run_skill", "skill:"+entry.Skill.ID); err != nil {
			return "", err
		}
		return marshalToolResult(map[string]any{
			"success": true, "kind": "execution", "runAs": "inline", "skill": runSkillMetadata(entry),
			"task": request.Task, "instructions": entry.Skill.Content,
		}), nil
	}
	if request.Task == "" {
		return marshalToolResult(map[string]any{
			"success": false, "kind": "missing_task", "error": "子代理 Skill 必须提供本次具体 task", "skill": runSkillMetadata(entry),
		}), nil
	}
	if !isBuiltinToolEnabled(config, "subagent_launch") {
		return marshalToolResult(map[string]any{
			"success": false, "kind": "unavailable", "error": "当前项目已关闭子代理工具", "skill": runSkillMetadata(entry),
		}), nil
	}
	if workspace == nil {
		workspace, err = newLocalWorkspace(config.ProjectPath)
		if err != nil {
			return marshalToolResult(map[string]any{
				"success": false, "kind": "unavailable", "error": err.Error(), "skill": runSkillMetadata(entry),
			}), nil
		}
	}
	raw, err := runSkillSubagentArguments(entry, request)
	if err != nil {
		return marshalToolResult(map[string]any{
			"success": false, "kind": "invalid_budget", "error": err.Error(), "skill": runSkillMetadata(entry),
		}), nil
	}
	arguments, err := json.Marshal(raw)
	if err != nil {
		return "", err
	}
	childResult, err := app.executeSubagentTool(ctx, sessionID, config, workspace, modelToolCall{
		ID: call.ID, Type: "function", Function: modelToolFunction{Name: "subagent_launch", Arguments: string(arguments)},
	}, raw)
	if err != nil {
		return "", err
	}
	return marshalToolResult(map[string]any{
		"success": true, "kind": "execution", "runAs": "subagent", "skill": runSkillMetadata(entry),
		"task": request.Task, "background": request.Background, "result": childResult,
	}), nil
}

func runSkillSubagentArguments(entry scopedSkill, request runSkillArguments) (map[string]json.RawMessage, error) {
	goal := fmt.Sprintf("Apply the Murong Skill below to the delegated task. Follow the Skill instructions exactly within the declared tool budget.\n\nSkill: %s\nSource: %s\nDescription: %s\n\nSkill instructions:\n%s\n\nDelegated task:\n%s",
		entry.Skill.Title, entry.Scope, entry.Skill.Description, entry.Skill.Content, request.Task)
	if len([]rune(goal)) > maxRunSkillTaskRunes {
		return nil, fmt.Errorf("Skill 指令与 task 合计超过子代理 goal 上限 %d 字符", maxRunSkillTaskRunes)
	}
	values := map[string]any{"goal": goal, "background": request.Background}
	if model := strings.TrimSpace(entry.Skill.PreferredModel); model != "" {
		values["model"] = model
	}
	if len(entry.Skill.AllowedTools) > 0 {
		allowed := normalizeRunSkillAllowedTools(entry.Skill.AllowedTools)
		if len(allowed) == 0 {
			return nil, errors.New("Skill 的 allowedTools 只包含不可派发的递归或交互工具")
		}
		values["allowedTools"] = allowed
		allowedSet := map[string]bool{}
		for _, name := range allowed {
			allowedSet[name] = true
		}
		values["enableWebSearch"] = allowedSet["web_search"] || allowedSet["web_fetch"]
		values["allowCodeEdits"] = allowedSet["code_edit"]
		values["allowShell"] = allowedSet["run_terminal"]
		values["allowWriteAccess"] = allowedSet["write_file"] || allowedSet["create_directory"] || allowedSet["delete_path"] || allowedSet["chmod_path"]
	}
	encoded, err := json.Marshal(values)
	if err != nil {
		return nil, err
	}
	raw := map[string]json.RawMessage{}
	if err := json.Unmarshal(encoded, &raw); err != nil {
		return nil, err
	}
	return raw, nil
}

func normalizeRunSkillAllowedTools(values []string) []string {
	result := map[string]bool{}
	add := func(names ...string) {
		for _, name := range names {
			result[name] = true
		}
	}
	for _, value := range values {
		name := strings.ToLower(strings.TrimSpace(value))
		switch name {
		case "", "ask_user", "run_skill", "subagent", "subagent_launch", "subagent_jobs", "explore", "research", "review", "security_review":
			continue
		case "file", "computer_workspace":
			add("list_files", "read_file", "file_exists", "workspace_diff", "write_file", "create_directory", "delete_path", "chmod_path")
		case "shell", "computer_terminal":
			add("run_terminal")
		case "web":
			add("web_search", "web_fetch")
		case "github":
			add("github_repository", "github_read_file", "github_list_branches", "github_list_issues", "github_create_branch", "github_put_file", "github_create_issue", "github_create_pull_request")
		case "memory":
			add("memory_list", "memory_search", "memory_read")
		case "skill":
			add("read_skill")
		default:
			add(name)
		}
	}
	names := make([]string, 0, len(result))
	for name := range result {
		names = append(names, name)
	}
	sort.Strings(names)
	return names
}
