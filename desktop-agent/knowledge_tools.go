package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
)

func (app *DesktopAgentApp) knowledgeToolDefinitions() []any {
	return []any{
		functionTool("memory_list", "列出当前可用的全局或项目记忆；只返回标题和 ID，不展开完整内容", map[string]any{
			"scope": map[string]any{"type": "string", "enum": []string{"any", "global", "project"}},
			"limit": map[string]any{"type": "integer", "minimum": 1, "maximum": 30},
		}, []string{}),
		functionTool("memory_search", "按关键词搜索已启用的全局或项目记忆", map[string]any{
			"query": map[string]any{"type": "string", "description": "要搜索的关键词"},
			"scope": map[string]any{"type": "string", "enum": []string{"any", "global", "project"}},
			"limit": map[string]any{"type": "integer", "minimum": 1, "maximum": 20},
		}, []string{"query"}),
		functionTool("memory_read", "按 ID 读取一条已启用的全局或项目记忆", map[string]any{
			"memory_id": map[string]any{"type": "string", "description": "memory_list 或 memory_search 返回的记忆 ID"},
			"scope":     map[string]any{"type": "string", "enum": []string{"any", "global", "project"}},
		}, []string{"memory_id"}),
		functionTool("remember_memory", "保存一条全局或项目记忆；仅当用户明确要求长期记住时使用", map[string]any{
			"title":   map[string]any{"type": "string"},
			"content": map[string]any{"type": "string"},
			"scope":   map[string]any{"type": "string", "enum": []string{"global", "project"}, "description": "未指定时有项目则保存到项目，否则保存到全局"},
			"enabled": map[string]any{"type": "boolean"},
		}, []string{"title", "content"}),
		functionTool("forget_memory", "删除一条记忆；仅当用户明确要求忘记时使用", map[string]any{
			"memory_id": map[string]any{"type": "string"},
			"scope":     map[string]any{"type": "string", "enum": []string{"any", "global", "project"}},
		}, []string{"memory_id"}),
		functionTool("read_skill", "列出或读取当前已启用的全局/项目 Skill", map[string]any{
			"skill":          map[string]any{"type": "string", "description": "Skill ID 或标题；留空时列出目录"},
			"source":         map[string]any{"type": "string", "enum": []string{"any", "project", "global"}},
			"includeContent": map[string]any{"type": "boolean"},
		}, []string{}),
		functionTool("run_skill", "执行一个已启用的全局或项目 Skill；内联 Skill 会返回完整指令供当前轮继续执行，子代理 Skill 会按其模型和工具预算派发隔离任务", map[string]any{
			"skill":       map[string]any{"type": "string", "description": "Skill ID、标题或可唯一匹配的关键词"},
			"task":        map[string]any{"type": "string", "description": "本次具体任务；子代理 Skill 必填"},
			"source":      map[string]any{"type": "string", "enum": []string{"any", "project", "global"}},
			"preferRunAs": map[string]any{"type": "string", "enum": []string{"skill-default", "inline", "subagent"}},
			"background":  map[string]any{"type": "boolean", "description": "仅对子代理 Skill 生效"},
		}, []string{"skill"}),
		functionTool("create_global_rule", "创建一条全局规则；仅当用户明确要求永久添加规则时使用", map[string]any{
			"title":   map[string]any{"type": "string"},
			"content": map[string]any{"type": "string"},
			"enabled": map[string]any{"type": "boolean"},
		}, []string{"title", "content"}),
		functionTool("create_global_memory", "创建一条全局记忆；仅当用户明确要求保存为全局记忆时使用", map[string]any{
			"title": map[string]any{"type": "string"}, "content": map[string]any{"type": "string"}, "enabled": map[string]any{"type": "boolean"},
		}, []string{"title", "content"}),
		functionTool("create_global_skill", "创建一个可复用的全局 Skill；仅当用户明确要求保存 Skill 时使用", map[string]any{
			"title": map[string]any{"type": "string"}, "description": map[string]any{"type": "string"}, "content": map[string]any{"type": "string"},
			"runAs": map[string]any{"type": "string", "enum": []string{"inline", "subagent"}}, "allowedTools": map[string]any{"type": "array", "items": map[string]any{"type": "string"}},
			"preferredModel": map[string]any{"type": "string"}, "enabled": map[string]any{"type": "boolean"},
		}, []string{"title", "content"}),
		functionTool("create_project_rule", "在当前项目创建规则；仅当用户明确要求保存为项目规则时使用", map[string]any{
			"title": map[string]any{"type": "string"}, "content": map[string]any{"type": "string"}, "enabled": map[string]any{"type": "boolean"},
		}, []string{"title", "content"}),
		functionTool("create_project_memory", "在当前项目创建记忆；仅当用户明确要求保存为项目记忆时使用", map[string]any{
			"title": map[string]any{"type": "string"}, "content": map[string]any{"type": "string"}, "enabled": map[string]any{"type": "boolean"},
		}, []string{"title", "content"}),
		functionTool("create_project_skill", "在当前项目创建 Skill；仅当用户明确要求保存为项目 Skill 时使用", map[string]any{
			"title": map[string]any{"type": "string"}, "description": map[string]any{"type": "string"}, "content": map[string]any{"type": "string"},
			"runAs": map[string]any{"type": "string", "enum": []string{"inline", "subagent"}}, "allowedTools": map[string]any{"type": "array", "items": map[string]any{"type": "string"}},
			"preferredModel": map[string]any{"type": "string"}, "enabled": map[string]any{"type": "boolean"},
		}, []string{"title", "content"}),
	}
}

func (app *DesktopAgentApp) executeKnowledgeTool(ctx context.Context, sessionID string, config desktopConfig, call modelToolCall) (string, error) {
	var raw map[string]json.RawMessage
	if err := json.Unmarshal([]byte(call.Function.Arguments), &raw); err != nil {
		return "", fmt.Errorf("工具参数不是有效 JSON：%w", err)
	}
	stringArg := func(name string) string {
		var value string
		if data := raw[name]; len(data) > 0 {
			_ = json.Unmarshal(data, &value)
		}
		return strings.TrimSpace(value)
	}
	boolArg := func(name string, fallback bool) bool {
		value := fallback
		if data := raw[name]; len(data) > 0 {
			_ = json.Unmarshal(data, &value)
		}
		return value
	}
	intArg := func(name string, fallback, maximum int) int {
		value := fallback
		if data := raw[name]; len(data) > 0 {
			_ = json.Unmarshal(data, &value)
		}
		if value < 1 {
			return fallback
		}
		if value > maximum {
			return maximum
		}
		return value
	}
	arrayArg := func(name string) []string {
		var value []string
		if data := raw[name]; len(data) > 0 {
			_ = json.Unmarshal(data, &value)
		}
		return value
	}
	authorize := func(summary, detail, risk string) error {
		return app.authorizeTool(ctx, config, ApprovalRequest{
			ID: newID("approval"), SessionID: sessionID, ToolName: call.Function.Name,
			Summary: summary, Detail: detail, Arguments: call.Function.Arguments, Risk: risk,
		}, "knowledge:"+call.Function.Name)
	}

	switch call.Function.Name {
	case "memory_list":
		scope := normalizeKnowledgeScope(stringArg("scope"))
		if err := authorize("列出可用记忆", scope, "low"); err != nil {
			return "", err
		}
		memories := app.scopedMemories(scope)
		limit := intArg("limit", 10, 30)
		if len(memories) > limit {
			memories = memories[:limit]
		}
		items := make([]map[string]string, 0, len(memories))
		for _, entry := range memories {
			items = append(items, map[string]string{"id": entry.Memory.ID, "title": entry.Memory.Title, "scope": entry.Scope})
		}
		return marshalToolResult(map[string]any{"success": true, "memories": items}), nil

	case "memory_search":
		query, scope := stringArg("query"), normalizeKnowledgeScope(stringArg("scope"))
		if query == "" {
			return "", errors.New("搜索关键词不能为空")
		}
		if err := authorize("搜索可用记忆", query+"\n范围: "+scope, "low"); err != nil {
			return "", err
		}
		query = strings.ToLower(query)
		matches := []map[string]string{}
		for _, entry := range app.scopedMemories(scope) {
			if strings.Contains(strings.ToLower(entry.Memory.Title+"\n"+entry.Memory.Content), query) {
				matches = append(matches, map[string]string{
					"id": entry.Memory.ID, "title": entry.Memory.Title, "scope": entry.Scope, "excerpt": truncateRunes(entry.Memory.Content, 240),
				})
			}
		}
		limit := intArg("limit", 5, 20)
		if len(matches) > limit {
			matches = matches[:limit]
		}
		return marshalToolResult(map[string]any{"success": true, "matches": matches}), nil

	case "memory_read":
		id, scope := stringArg("memory_id"), normalizeKnowledgeScope(stringArg("scope"))
		if id == "" {
			return "", errors.New("memory_id 不能为空")
		}
		if err := authorize("读取记忆", id+"\n范围: "+scope, "low"); err != nil {
			return "", err
		}
		for _, entry := range app.scopedMemories(scope) {
			if equalKnowledgeID(entry.Memory.ID, id) {
				return marshalToolResult(map[string]any{"success": true, "scope": entry.Scope, "memory": entry.Memory}), nil
			}
		}
		return "", errors.New("记忆不存在或未启用")

	case "remember_memory", "create_global_memory", "create_project_memory":
		title, content := stringArg("title"), stringArg("content")
		if title == "" || content == "" {
			return "", errors.New("记忆标题和内容不能为空")
		}
		scope := strings.ToLower(stringArg("scope"))
		if call.Function.Name == "create_global_memory" {
			scope = "global"
		} else if call.Function.Name == "create_project_memory" {
			scope = "project"
		} else if scope == "" {
			if app.store.projectKnowledge().HasProject {
				scope = "project"
			} else {
				scope = "global"
			}
		}
		if scope != "global" && scope != "project" {
			return "", errors.New("记忆 scope 必须是 global 或 project")
		}
		if err := authorize("保存"+knowledgeScopeLabel(scope)+"记忆", title+"\n\n"+truncateRunes(content, 1200), "high"); err != nil {
			return "", err
		}
		saved := GlobalMemory{ID: newID("memory"), Title: title, Content: content, Enabled: boolArg("enabled", true)}
		if scope == "project" {
			snapshot, err := app.store.mutateProjectKnowledge(func(library *KnowledgeLibrary) error {
				library.Memories = append(library.Memories, saved)
				return nil
			})
			if err != nil {
				return "", err
			}
			app.emit("project-knowledge:changed", snapshot)
		} else {
			library, err := app.store.mutateKnowledge(func(config *desktopConfig) error {
				config.GlobalMemories = append(config.GlobalMemories, saved)
				return nil
			})
			if err != nil {
				return "", err
			}
			app.emit("knowledge:changed", library)
		}
		return marshalToolResult(map[string]any{"success": true, "scope": scope, "memory": saved}), nil

	case "forget_memory":
		id, scope := stringArg("memory_id"), normalizeKnowledgeScope(stringArg("scope"))
		if id == "" {
			return "", errors.New("memory_id 不能为空")
		}
		if err := authorize("删除记忆", id+"\n范围: "+scope, "high"); err != nil {
			return "", err
		}
		if scope != "global" {
			project := app.store.projectKnowledge()
			if _, ok := findMemory(project.Library.Memories, id, false); ok {
				snapshot, err := app.store.mutateProjectKnowledge(func(library *KnowledgeLibrary) error {
					library.Memories = removeMemory(library.Memories, id)
					return nil
				})
				if err != nil {
					return "", err
				}
				app.emit("project-knowledge:changed", snapshot)
				return marshalToolResult(map[string]any{"success": true, "scope": "project", "forgottenId": id}), nil
			}
		}
		if scope != "project" {
			global := app.store.knowledgeLibrary()
			if _, ok := findMemory(global.Memories, id, false); ok {
				library, err := app.store.mutateKnowledge(func(config *desktopConfig) error {
					config.GlobalMemories = removeMemory(config.GlobalMemories, id)
					return nil
				})
				if err != nil {
					return "", err
				}
				app.emit("knowledge:changed", library)
				return marshalToolResult(map[string]any{"success": true, "scope": "global", "forgottenId": id}), nil
			}
		}
		return "", errors.New("记忆不存在")

	case "read_skill":
		query, source := stringArg("skill"), normalizeKnowledgeScope(stringArg("source"))
		if err := authorize("读取 Skill", query+"\n来源: "+source, "low"); err != nil {
			return "", err
		}
		entries := app.scopedSkills(source)
		if query == "" {
			catalog := make([]map[string]string, 0, len(entries))
			for _, entry := range entries {
				catalog = append(catalog, map[string]string{"id": entry.Skill.ID, "title": entry.Skill.Title, "description": entry.Skill.Description, "source": entry.Scope})
			}
			return marshalToolResult(map[string]any{"success": true, "skills": catalog}), nil
		}
		for _, entry := range entries {
			if equalKnowledgeID(entry.Skill.ID, query) || strings.EqualFold(strings.TrimSpace(entry.Skill.Title), query) {
				skill := entry.Skill
				if !boolArg("includeContent", true) {
					skill.Content = ""
				}
				return marshalToolResult(map[string]any{"success": true, "source": entry.Scope, "skill": skill}), nil
			}
		}
		return "", errors.New("Skill 不存在或未启用")

	case "create_global_rule", "create_project_rule":
		title, content := stringArg("title"), stringArg("content")
		if title == "" || content == "" {
			return "", errors.New("规则标题和内容不能为空")
		}
		scope := "global"
		if call.Function.Name == "create_project_rule" {
			scope = "project"
		}
		if err := authorize("创建"+knowledgeScopeLabel(scope)+"规则", title+"\n\n"+truncateRunes(content, 1200), "high"); err != nil {
			return "", err
		}
		rule := GlobalRule{ID: newID("rule"), Title: title, Content: content, Enabled: boolArg("enabled", true)}
		if scope == "project" {
			snapshot, err := app.store.mutateProjectKnowledge(func(library *KnowledgeLibrary) error {
				library.Rules = append(library.Rules, rule)
				return nil
			})
			if err != nil {
				return "", err
			}
			app.emit("project-knowledge:changed", snapshot)
		} else {
			library, err := app.store.mutateKnowledge(func(config *desktopConfig) error {
				config.GlobalRules = append(config.GlobalRules, rule)
				return nil
			})
			if err != nil {
				return "", err
			}
			app.emit("knowledge:changed", library)
		}
		return marshalToolResult(map[string]any{"success": true, "scope": scope, "rule": rule}), nil

	case "create_global_skill", "create_project_skill":
		title, content := stringArg("title"), stringArg("content")
		if title == "" || content == "" {
			return "", errors.New("Skill 标题和内容不能为空")
		}
		scope := "global"
		if call.Function.Name == "create_project_skill" {
			scope = "project"
		}
		if err := authorize("创建"+knowledgeScopeLabel(scope)+" Skill", title+"\n\n"+truncateRunes(content, 1200), "high"); err != nil {
			return "", err
		}
		runAs := strings.ToUpper(stringArg("runAs"))
		skill := GlobalSkill{
			ID: newID("skill"), Title: title, Description: stringArg("description"), Content: content,
			RunAs: runAs, AllowedTools: arrayArg("allowedTools"), PreferredModel: stringArg("preferredModel"), Enabled: boolArg("enabled", true),
		}
		if scope == "project" {
			snapshot, err := app.store.mutateProjectKnowledge(func(library *KnowledgeLibrary) error {
				library.Skills = append(library.Skills, skill)
				return nil
			})
			if err != nil {
				return "", err
			}
			if normalized, ok := findSkill(snapshot.Library.Skills, skill.ID, false); ok {
				skill = normalized
			}
			app.emit("project-knowledge:changed", snapshot)
		} else {
			library, err := app.store.mutateKnowledge(func(config *desktopConfig) error {
				config.GlobalSkills = append(config.GlobalSkills, skill)
				return nil
			})
			if err != nil {
				return "", err
			}
			if normalized, ok := findSkill(library.Skills, skill.ID, false); ok {
				skill = normalized
			}
			app.emit("knowledge:changed", library)
		}
		return marshalToolResult(map[string]any{"success": true, "scope": scope, "skill": skill}), nil
	default:
		return "", fmt.Errorf("未知工具：%s", call.Function.Name)
	}
}

type scopedMemory struct {
	Memory GlobalMemory
	Scope  string
}

type scopedSkill struct {
	Skill GlobalSkill
	Scope string
}

func (app *DesktopAgentApp) scopedMemories(scope string) []scopedMemory {
	result := []scopedMemory{}
	if scope != "global" {
		for _, memory := range app.store.projectKnowledge().Library.Memories {
			if memory.Enabled {
				result = append(result, scopedMemory{Memory: memory, Scope: "project"})
			}
		}
	}
	if scope != "project" {
		for _, memory := range app.store.knowledgeLibrary().Memories {
			if memory.Enabled {
				result = append(result, scopedMemory{Memory: memory, Scope: "global"})
			}
		}
	}
	return result
}

func (app *DesktopAgentApp) scopedSkills(scope string) []scopedSkill {
	result := []scopedSkill{}
	if scope != "global" {
		for _, skill := range app.store.projectKnowledge().Library.Skills {
			if skill.Enabled {
				result = append(result, scopedSkill{Skill: skill, Scope: "project"})
			}
		}
	}
	if scope != "project" {
		for _, skill := range app.store.knowledgeLibrary().Skills {
			if skill.Enabled {
				result = append(result, scopedSkill{Skill: skill, Scope: "global"})
			}
		}
	}
	return result
}

func normalizeKnowledgeScope(value string) string {
	value = strings.ToLower(strings.TrimSpace(value))
	if value == "global" || value == "project" {
		return value
	}
	return "any"
}

func knowledgeScopeLabel(scope string) string {
	if scope == "project" {
		return "项目"
	}
	return "全局"
}

func removeMemory(values []GlobalMemory, id string) []GlobalMemory {
	result := make([]GlobalMemory, 0, len(values))
	for _, memory := range values {
		if !equalKnowledgeID(memory.ID, id) {
			result = append(result, memory)
		}
	}
	return result
}

func findMemory(values []GlobalMemory, id string, requireEnabled bool) (GlobalMemory, bool) {
	for _, value := range values {
		if equalKnowledgeID(value.ID, id) && (!requireEnabled || value.Enabled) {
			return value, true
		}
	}
	return GlobalMemory{}, false
}

func findSkill(values []GlobalSkill, id string, requireEnabled bool) (GlobalSkill, bool) {
	for _, value := range values {
		if (equalKnowledgeID(value.ID, id) || strings.EqualFold(strings.TrimSpace(value.Title), strings.TrimSpace(id))) && (!requireEnabled || value.Enabled) {
			return value, true
		}
	}
	return GlobalSkill{}, false
}

func equalKnowledgeID(left, right string) bool {
	return strings.EqualFold(strings.TrimSpace(left), strings.TrimSpace(right))
}
