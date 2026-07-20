package main

import (
	"errors"
	"fmt"
	"os"
	"sort"
	"strings"
)

const (
	maxComposerContextItems = 12
	maxComposerContextRunes = 40_000
)

var composerSubagentChoices = []ComposerChoice{
	{Kind: "subagent", ID: "subagent_launch", Label: "通用子代理", Detail: "派发一条隔离的子任务"},
	{Kind: "subagent", ID: "explore", Label: "Explore", Detail: "只读探索项目结构与调用链"},
	{Kind: "subagent", ID: "research", Label: "Research", Detail: "结合项目与网页资料研究方案"},
	{Kind: "subagent", ID: "review", Label: "Review", Detail: "审查代码、风险与回归问题"},
	{Kind: "subagent", ID: "security_review", Label: "Security Review", Detail: "检查安全边界与敏感操作"},
}

func normalizeComposerMode(value string) string {
	switch strings.ToLower(strings.TrimSpace(value)) {
	case "goal", "plan", "goal_plan":
		return strings.ToLower(strings.TrimSpace(value))
	default:
		return ""
	}
}

func (app *DesktopAgentApp) GetComposerCatalog() ComposerCatalog {
	config := app.store.rawConfig()
	choices := make([]ComposerChoice, 0, len(config.GlobalSkills))
	for _, skill := range config.GlobalSkills {
		if skill.Enabled {
			choices = append(choices, ComposerChoice{
				Kind: "skill", ID: skill.ID, Label: skill.Title, Detail: composerSkillDetail(skill), Scope: "global",
			})
		}
	}
	project := projectKnowledgeFromConfig(config)
	for _, skill := range project.Library.Skills {
		if skill.Enabled {
			choices = append(choices, ComposerChoice{
				Kind: "skill", ID: skill.ID, Label: skill.Title, Detail: composerSkillDetail(skill), Scope: "project",
			})
		}
	}
	sort.SliceStable(choices, func(i, j int) bool {
		if choices[i].Scope != choices[j].Scope {
			return choices[i].Scope == "project"
		}
		return strings.ToLower(choices[i].Label) < strings.ToLower(choices[j].Label)
	})
	mcpChoices := []ComposerChoice{}
	if app.mcp != nil && isBuiltinToolEnabled(resolvedToolConfig(config), "mcp") {
		for _, tool := range app.mcp.ToolDefinitions() {
			detail := tool.ServerName
			if tool.Description != "" {
				detail += " · " + tool.Description
			}
			if tool.TrustedReadOnly {
				detail += " · 可信只读"
			}
			mcpChoices = append(mcpChoices, ComposerChoice{
				Kind: "mcp", ID: tool.CanonicalName, Label: tool.Name, Detail: detail, Scope: tool.ServerID,
			})
		}
	}
	subagents := []ComposerChoice{}
	for _, template := range projectSubagentTemplates(config) {
		if !template.Enabled {
			continue
		}
		detail := strings.TrimSpace(template.Description)
		if detail == "" {
			detail = "当前项目自定义模板"
		}
		if len(template.GoalMatchers) > 0 {
			detail += " · 匹配：" + strings.Join(template.GoalMatchers, "、")
		}
		subagents = append(subagents, ComposerChoice{
			Kind: "subagent", ID: template.ID, Label: template.Title, Detail: detail, Scope: "project",
		})
	}
	subagents = append(subagents, composerSubagentChoices...)
	return ComposerCatalog{Skills: choices, Subagents: subagents, MCPTools: mcpChoices}
}

func composerSkillDetail(skill GlobalSkill) string {
	parts := []string{"内联执行"}
	if skill.RunAs == "SUBAGENT" {
		parts[0] = "子代理执行"
	}
	if description := strings.TrimSpace(skill.Description); description != "" {
		parts = append(parts, description)
	}
	if model := strings.TrimSpace(skill.PreferredModel); model != "" {
		parts = append(parts, "模型 "+model)
	}
	if len(skill.AllowedTools) > 0 {
		parts = append(parts, fmt.Sprintf("%d 个允许工具", len(skill.AllowedTools)))
	}
	return strings.Join(parts, " · ")
}

func validateComposerContext(config desktopConfig, values []ComposerContextItem) ([]ComposerContextItem, error) {
	return validateComposerContextWithMCP(config, values, nil)
}

func (app *DesktopAgentApp) validateComposerContext(config desktopConfig, values []ComposerContextItem) ([]ComposerContextItem, error) {
	return validateComposerContextWithMCP(config, values, func(id string) (mcpToolDefinition, bool) {
		if app.mcp == nil {
			return mcpToolDefinition{}, false
		}
		return app.mcp.Tool(id)
	})
}

func validateComposerContextWithMCP(
	config desktopConfig,
	values []ComposerContextItem,
	lookupMCP func(string) (mcpToolDefinition, bool),
) ([]ComposerContextItem, error) {
	if len(values) > maxComposerContextItems {
		return nil, fmt.Errorf("每轮最多选择 %d 个上下文", maxComposerContextItems)
	}
	var workspace *localWorkspace
	result := make([]ComposerContextItem, 0, len(values))
	seen := map[string]bool{}
	for _, value := range values {
		value.Kind = strings.ToLower(strings.TrimSpace(value.Kind))
		value.ID = strings.TrimSpace(value.ID)
		value.Scope = strings.ToLower(strings.TrimSpace(value.Scope))
		switch value.Kind {
		case "file", "folder":
			if workspace == nil {
				var err error
				workspace, err = newLocalWorkspace(config.ProjectPath)
				if err != nil {
					return nil, err
				}
			}
			relative, err := normalizeRelativePath(value.Path, value.Kind == "folder")
			if err != nil {
				return nil, err
			}
			resolved, err := workspace.resolveExisting(relative, value.Kind == "folder")
			if err != nil {
				return nil, fmt.Errorf("上下文路径 %q 不存在", relative)
			}
			info, err := os.Stat(resolved)
			if err != nil || (value.Kind == "folder") != info.IsDir() {
				return nil, fmt.Errorf("上下文路径 %q 类型不匹配", relative)
			}
			value.Path = relative
			value.Label = relative
			value.ID = ""
			value.Scope = "project"
		case "skill":
			skill, scope := composerSkill(config, value.ID, value.Scope)
			if skill == nil || !skill.Enabled {
				return nil, fmt.Errorf("所选 Skill %q 不存在或已关闭", value.ID)
			}
			value.Label = skill.Title
			value.Scope = scope
			value.Path = ""
		case "subagent":
			if value.Scope == "project" {
				template := composerProjectSubagentTemplate(config, value.ID)
				if template == nil || !template.Enabled {
					return nil, fmt.Errorf("项目子代理模板 %q 不存在或已关闭", value.ID)
				}
				value.Label = template.Title
				value.Scope = "project"
			} else {
				choice := composerSubagent(value.ID)
				if choice == nil {
					return nil, fmt.Errorf("未知子代理类型 %q", value.ID)
				}
				value.Label = choice.Label
				value.Scope = ""
			}
			value.Path = ""
		case "mcp":
			if lookupMCP == nil || !isBuiltinToolEnabled(resolvedToolConfig(config), "mcp") {
				return nil, errors.New("所选 MCP 工具尚未连接或已关闭")
			}
			tool, exists := lookupMCP(value.ID)
			if !exists {
				return nil, fmt.Errorf("所选 MCP 工具 %q 不存在或已断开", value.ID)
			}
			value.Label = tool.Name
			value.Scope = tool.ServerID
			value.Path = ""
		default:
			return nil, fmt.Errorf("未知上下文类型 %q", value.Kind)
		}
		key := value.Kind + "\x00" + value.Scope + "\x00" + value.ID + "\x00" + strings.ToLower(value.Path)
		if seen[key] {
			continue
		}
		seen[key] = true
		result = append(result, value)
	}
	return result, nil
}

func composerSkill(config desktopConfig, id, scope string) (*GlobalSkill, string) {
	id = strings.TrimSpace(id)
	project := projectKnowledgeFromConfig(config)
	if scope == "project" || scope == "" {
		for index := range project.Library.Skills {
			if project.Library.Skills[index].ID == id {
				return &project.Library.Skills[index], "project"
			}
		}
	}
	if scope == "global" || scope == "" {
		for index := range config.GlobalSkills {
			if config.GlobalSkills[index].ID == id {
				return &config.GlobalSkills[index], "global"
			}
		}
	}
	return nil, ""
}

func composerSubagent(id string) *ComposerChoice {
	for index := range composerSubagentChoices {
		if composerSubagentChoices[index].ID == strings.TrimSpace(id) {
			return &composerSubagentChoices[index]
		}
	}
	return nil
}

func composerProjectSubagentTemplate(config desktopConfig, id string) *ProjectSubagentTemplate {
	id = strings.TrimSpace(id)
	for _, template := range projectSubagentTemplates(config) {
		if template.ID == id {
			matched := template
			return &matched
		}
	}
	return nil
}

func materializeUserMessage(config desktopConfig, message ChatMessage) string {
	content := strings.TrimSpace(message.Content)
	mode := normalizeComposerMode(message.Mode)
	changeSummary := workspaceChangeSummary(message.WorkspaceChanges, message.WorkspaceChangesOmitted)
	if message.Role != "user" || (len(message.Context) == 0 && mode == "" && changeSummary == "") {
		return content
	}
	lines := []string{"[Murong：本轮补充上下文]"}
	if changeSummary != "" {
		lines = append(lines, changeSummary)
	}
	switch mode {
	case "goal":
		lines = append(lines, "- 用户正在设置或更新当前会话的长期目标；围绕该目标持续推进，不要偏离。")
	case "plan":
		lines = append(lines, "- 用户选择计划模式；本轮只调查并生成可执行计划，不执行写入或终端操作。")
	case "goal_plan":
		lines = append(lines, "- 用户正在设置或更新长期目标，并要求本轮先生成可执行计划；不要立即执行计划。")
	}
	usedRunes := 0
	for _, item := range message.Context {
		switch item.Kind {
		case "file":
			lines = append(lines, fmt.Sprintf("- 项目文件 @%s：先用文件工具读取真实内容，再据此处理。", item.Path))
		case "folder":
			lines = append(lines, fmt.Sprintf("- 项目文件夹 @%s：把该目录作为本轮重点范围，按需列出和读取。", item.Path))
		case "skill":
			skill, scope := composerSkill(config, item.ID, item.Scope)
			if skill == nil || !skill.Enabled {
				continue
			}
			instruction := truncateRunes(skill.Content, maxComposerContextRunes-usedRunes)
			usedRunes += len([]rune(instruction))
			runInstruction := "直接在当前 Agent 中遵循"
			if skill.RunAs == "SUBAGENT" {
				runInstruction = "必须通过 subagent_launch 派发，并把这份说明交给子代理"
			}
			lines = append(lines, fmt.Sprintf("- 已选择 %s Skill %q（ID: %s，%s）。\n  Skill 完整说明：\n%s", scope, skill.Title, skill.ID, runInstruction, instruction))
		case "subagent":
			if item.Scope == "project" {
				template := composerProjectSubagentTemplate(config, item.ID)
				if template != nil && template.Enabled {
					lines = append(lines, fmt.Sprintf("- 本轮执行方式：必须调用 subagent_launch，并传 templateId=%q 使用项目模板 %q；处理适合委派的主体工作后再整合结果回复用户。", template.ID, template.Title))
				}
			} else if choice := composerSubagent(item.ID); choice != nil {
				lines = append(lines, fmt.Sprintf("- 本轮执行方式：必须调用 %s 子代理工具处理适合委派的主体工作，再整合结果回复用户。", choice.ID))
			}
		case "mcp":
			lines = append(lines, fmt.Sprintf("- 本轮优先使用 MCP 工具 %s（%s）。", item.Label, item.ID))
		}
		if usedRunes >= maxComposerContextRunes {
			lines = append(lines, "- 其余 Skill 内容因上下文上限未展开。")
			break
		}
	}
	if len(lines) == 1 {
		return content
	}
	return content + "\n\n" + strings.Join(lines, "\n")
}

func cloneComposerContext(values []ComposerContextItem) []ComposerContextItem {
	return append([]ComposerContextItem{}, values...)
}
