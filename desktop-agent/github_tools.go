package main

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"unicode/utf8"
)

const (
	maximumGitHubTextFileBytes = 1 << 20
	maximumGitHubBodyRunes     = 65_536
)

var (
	gitHubObjectSHAPattern = regexp.MustCompile(`^[0-9a-fA-F]{40,64}$`)
	gitHubOwnerPattern     = regexp.MustCompile(`^[A-Za-z0-9_.-]+$`)
)

type gitHubRepository struct {
	FullName      string `json:"full_name"`
	Description   string `json:"description"`
	Private       bool   `json:"private"`
	Archived      bool   `json:"archived"`
	DefaultBranch string `json:"default_branch"`
	HTMLURL       string `json:"html_url"`
	OpenIssues    int    `json:"open_issues_count"`
}

type gitHubContent struct {
	Type     string `json:"type"`
	Path     string `json:"path"`
	SHA      string `json:"sha"`
	Size     int    `json:"size"`
	Encoding string `json:"encoding"`
	Content  string `json:"content"`
	HTMLURL  string `json:"html_url"`
}

type gitHubBranch struct {
	Name      string `json:"name"`
	Protected bool   `json:"protected"`
	Commit    struct {
		SHA string `json:"sha"`
	} `json:"commit"`
}

type gitHubIssue struct {
	Number      int            `json:"number"`
	Title       string         `json:"title"`
	State       string         `json:"state"`
	HTMLURL     string         `json:"html_url"`
	PullRequest map[string]any `json:"pull_request"`
	User        struct {
		Login string `json:"login"`
	} `json:"user"`
}

type gitHubReference struct {
	Ref    string `json:"ref"`
	Object struct {
		SHA string `json:"sha"`
	} `json:"object"`
}

type gitHubWriteResponse struct {
	Content *gitHubContent `json:"content"`
	Commit  *struct {
		SHA     string `json:"sha"`
		HTMLURL string `json:"html_url"`
	} `json:"commit"`
	Number  int    `json:"number"`
	HTMLURL string `json:"html_url"`
}

func (manager *savedWorkflowManager) gitHubRuntime() (runtimeGitHubConfig, *http.Client, error) {
	if manager == nil || manager.store == nil {
		return runtimeGitHubConfig{}, nil, errors.New("GitHub 连接尚未初始化")
	}
	config, err := manager.store.runtimeGitHub()
	if err != nil {
		return runtimeGitHubConfig{}, nil, err
	}
	manager.mu.Lock()
	client := manager.githubHTTP
	manager.mu.Unlock()
	if client == nil {
		client = newGitHubHTTPClient()
	}
	return config, client, nil
}

func gitHubToolDefinitions() []any {
	repository := map[string]any{"type": "string", "description": "owner/repository"}
	return []any{
		functionTool("github_repository", "读取 GitHub 仓库基本信息", map[string]any{"repository": repository}, []string{"repository"}),
		functionTool("github_read_file", "读取 GitHub 仓库中的 UTF-8 文本文件并返回远端 SHA", map[string]any{
			"repository": repository, "path": map[string]any{"type": "string"}, "ref": map[string]any{"type": "string", "description": "分支、标签或提交 SHA；省略使用默认分支"},
		}, []string{"repository", "path"}),
		functionTool("github_list_branches", "列出 GitHub 仓库最近的分支", map[string]any{"repository": repository}, []string{"repository"}),
		functionTool("github_list_issues", "列出 GitHub 仓库 Issue（不混入 Pull Request）", map[string]any{
			"repository": repository, "state": map[string]any{"type": "string", "enum": []string{"open", "closed", "all"}}, "max_results": map[string]any{"type": "integer", "minimum": 1, "maximum": 30},
		}, []string{"repository"}),
		functionTool("github_create_branch", "从指定远端分支创建一个 GitHub 分支", map[string]any{
			"repository": repository, "branch": map[string]any{"type": "string"}, "from_ref": map[string]any{"type": "string", "description": "省略使用仓库默认分支"},
		}, []string{"repository", "branch"}),
		functionTool("github_put_file", "在 GitHub 分支创建或提交一个 UTF-8 文本文件；更新已有文件必须提供 github_read_file 返回的 SHA", map[string]any{
			"repository": repository, "path": map[string]any{"type": "string"}, "branch": map[string]any{"type": "string"},
			"message": map[string]any{"type": "string"}, "content": map[string]any{"type": "string"}, "expected_sha": map[string]any{"type": "string"},
		}, []string{"repository", "path", "branch", "message", "content"}),
		functionTool("github_create_issue", "在 GitHub 仓库创建 Issue", map[string]any{
			"repository": repository, "title": map[string]any{"type": "string"}, "body": map[string]any{"type": "string"},
		}, []string{"repository", "title"}),
		functionTool("github_create_pull_request", "从远端分支创建 GitHub Pull Request", map[string]any{
			"repository": repository, "title": map[string]any{"type": "string"}, "head": map[string]any{"type": "string"}, "base": map[string]any{"type": "string"},
			"body": map[string]any{"type": "string"}, "draft": map[string]any{"type": "boolean"},
		}, []string{"repository", "title", "head", "base"}),
	}
}

func (app *DesktopAgentApp) executeGitHubTool(ctx context.Context, sessionID string, config desktopConfig, call modelToolCall, raw map[string]json.RawMessage) (string, error) {
	github, client, err := app.workflows.gitHubRuntime()
	if err != nil {
		return "", err
	}
	stringArg := func(name string) string {
		var value string
		_ = json.Unmarshal(raw[name], &value)
		return strings.TrimSpace(value)
	}
	repository := stringArg("repository")
	if _, _, err := gitHubRepositoryPath(repository); err != nil {
		return "", err
	}
	risk := "low"
	summary := "读取 GitHub 仓库"
	detail := repository
	switch call.Function.Name {
	case "github_create_branch":
		risk, summary, detail = "high", "创建 GitHub 分支", repository+" · "+stringArg("branch")+" ← "+stringArg("from_ref")
	case "github_put_file":
		risk, summary, detail = "high", "提交 GitHub 文件", repository+" · "+stringArg("branch")+" · "+stringArg("path")+"\n\n"+stringArg("message")
	case "github_create_issue":
		risk, summary, detail = "high", "创建 GitHub Issue", repository+"\n\n"+stringArg("title")+"\n\n"+truncateRunes(stringArg("body"), 1_500)
	case "github_create_pull_request":
		risk, summary, detail = "high", "创建 GitHub Pull Request", repository+" · "+stringArg("head")+" → "+stringArg("base")+"\n\n"+stringArg("title")
	case "github_read_file":
		summary, detail = "读取 GitHub 文件", repository+" · "+stringArg("path")+" @ "+stringArg("ref")
	case "github_list_branches":
		summary = "列出 GitHub 分支"
	case "github_list_issues":
		summary = "列出 GitHub Issue"
	}
	if err := app.authorizeTool(ctx, config, ApprovalRequest{
		ID: newID("approval"), SessionID: sessionID, ToolName: call.Function.Name, Summary: summary,
		Detail: truncateRunes(detail, 2_000), Arguments: call.Function.Arguments, Risk: risk,
	}, "github:"+repository, "github-path:"+repository+":"+stringArg("path"), "github-branch:"+repository+":"+stringArg("branch")); err != nil {
		return "", err
	}

	var result any
	switch call.Function.Name {
	case "github_repository":
		result, err = readGitHubRepository(ctx, client, github, repository)
	case "github_read_file":
		result, err = readGitHubFile(ctx, client, github, repository, stringArg("path"), stringArg("ref"))
	case "github_list_branches":
		result, err = listGitHubBranches(ctx, client, github, repository)
	case "github_list_issues":
		maxResults := 10
		_ = json.Unmarshal(raw["max_results"], &maxResults)
		result, err = listGitHubIssues(ctx, client, github, repository, stringArg("state"), maxResults)
	case "github_create_branch":
		result, err = createGitHubBranch(ctx, client, github, repository, stringArg("branch"), stringArg("from_ref"))
	case "github_put_file":
		var content string
		_ = json.Unmarshal(raw["content"], &content)
		result, err = putGitHubFile(ctx, client, github, repository, stringArg("path"), stringArg("branch"), stringArg("message"), content, stringArg("expected_sha"))
	case "github_create_issue":
		result, err = createGitHubIssue(ctx, client, github, repository, stringArg("title"), stringArg("body"))
	case "github_create_pull_request":
		result, err = createGitHubPullRequest(ctx, client, github, repository, stringArg("title"), stringArg("head"), stringArg("base"), stringArg("body"), rawBool(raw, "draft", false))
	default:
		return "", errors.New("未知 GitHub 工具")
	}
	return marshalToolResult(map[string]any{"success": err == nil, "result": result}), err
}

func readGitHubRepository(ctx context.Context, client *http.Client, config runtimeGitHubConfig, repository string) (gitHubRepository, error) {
	base, _, err := gitHubRepositoryPath(repository)
	if err != nil {
		return gitHubRepository{}, err
	}
	body, err := performGitHubGET(ctx, client, config, base)
	var result gitHubRepository
	if err == nil {
		err = json.Unmarshal(body, &result)
	}
	if err == nil && strings.TrimSpace(result.FullName) == "" {
		err = errors.New("GitHub 仓库响应缺少 full_name")
	}
	return result, err
}

func readGitHubFile(ctx context.Context, client *http.Client, config runtimeGitHubConfig, repository, filePath, ref string) (map[string]any, error) {
	base, _, err := gitHubRepositoryPath(repository)
	if err != nil {
		return nil, err
	}
	encodedPath, err := gitHubFilePath(filePath)
	if err != nil {
		return nil, err
	}
	if ref != "" {
		if err := validateGitHubRef(ref); err != nil {
			return nil, err
		}
	}
	endpoint := base + "/contents/" + encodedPath
	if ref != "" {
		endpoint += "?ref=" + url.QueryEscape(ref)
	}
	body, err := performGitHubGET(ctx, client, config, endpoint)
	if err != nil {
		return nil, err
	}
	var remote gitHubContent
	if err := json.Unmarshal(body, &remote); err != nil {
		return nil, errors.New("GitHub 文件响应不是单个文件")
	}
	if remote.Type != "file" || remote.Encoding != "base64" || remote.Size < 0 || remote.Size > maximumGitHubTextFileBytes {
		return nil, errors.New("GitHub 路径不是可读取的文本文件或超过 1 MiB")
	}
	decoded, err := base64.StdEncoding.DecodeString(strings.ReplaceAll(remote.Content, "\n", ""))
	if err != nil || len(decoded) > maximumGitHubTextFileBytes || !utf8.Valid(decoded) || strings.IndexByte(string(decoded), 0) >= 0 {
		return nil, errors.New("GitHub 文件不是有效的 UTF-8 文本")
	}
	return map[string]any{"path": remote.Path, "sha": remote.SHA, "size": remote.Size, "content": string(decoded), "htmlUrl": remote.HTMLURL, "ref": ref}, nil
}

func listGitHubBranches(ctx context.Context, client *http.Client, config runtimeGitHubConfig, repository string) ([]gitHubBranch, error) {
	base, _, err := gitHubRepositoryPath(repository)
	if err != nil {
		return nil, err
	}
	body, err := performGitHubGET(ctx, client, config, base+"/branches?per_page=30")
	var branches []gitHubBranch
	if err == nil {
		err = json.Unmarshal(body, &branches)
	}
	if len(branches) > 30 {
		branches = branches[:30]
	}
	return branches, err
}

func listGitHubIssues(ctx context.Context, client *http.Client, config runtimeGitHubConfig, repository, state string, maximum int) ([]gitHubIssue, error) {
	base, _, err := gitHubRepositoryPath(repository)
	if err != nil {
		return nil, err
	}
	if state == "" {
		state = "open"
	}
	if state != "open" && state != "closed" && state != "all" {
		return nil, errors.New("Issue state 必须是 open、closed 或 all")
	}
	if maximum < 1 {
		maximum = 10
	}
	if maximum > 30 {
		maximum = 30
	}
	body, err := performGitHubGET(ctx, client, config, base+"/issues?state="+state+"&per_page="+strconv.Itoa(maximum))
	var issues []gitHubIssue
	if err == nil {
		err = json.Unmarshal(body, &issues)
	}
	filtered := make([]gitHubIssue, 0, len(issues))
	for _, issue := range issues {
		if len(issue.PullRequest) == 0 {
			filtered = append(filtered, issue)
		}
	}
	return filtered, err
}

func createGitHubBranch(ctx context.Context, client *http.Client, config runtimeGitHubConfig, repository, branch, from string) (gitHubReference, error) {
	base, _, err := gitHubRepositoryPath(repository)
	if err != nil {
		return gitHubReference{}, err
	}
	if err := validateGitHubBranch(branch); err != nil {
		return gitHubReference{}, err
	}
	if from == "" {
		repositoryInfo, readErr := readGitHubRepository(ctx, client, config, repository)
		if readErr != nil {
			return gitHubReference{}, readErr
		}
		from = repositoryInfo.DefaultBranch
	}
	if err := validateGitHubRef(from); err != nil {
		return gitHubReference{}, err
	}
	body, err := performGitHubGET(ctx, client, config, base+"/git/ref/heads/"+url.PathEscape(from))
	if err != nil {
		return gitHubReference{}, err
	}
	var source gitHubReference
	if err := json.Unmarshal(body, &source); err != nil || !gitHubObjectSHAPattern.MatchString(source.Object.SHA) {
		return gitHubReference{}, errors.New("GitHub 来源分支没有返回有效提交 SHA")
	}
	body, err = performGitHubRequest(ctx, client, config, http.MethodPost, base+"/git/refs", map[string]any{
		"ref": "refs/heads/" + branch, "sha": source.Object.SHA,
	}, true)
	var created gitHubReference
	if err == nil {
		err = json.Unmarshal(body, &created)
	}
	return created, err
}

func putGitHubFile(ctx context.Context, client *http.Client, config runtimeGitHubConfig, repository, filePath, branch, message, content, expectedSHA string) (gitHubWriteResponse, error) {
	base, _, err := gitHubRepositoryPath(repository)
	if err != nil {
		return gitHubWriteResponse{}, err
	}
	encodedPath, err := gitHubFilePath(filePath)
	if err != nil {
		return gitHubWriteResponse{}, err
	}
	if err := validateGitHubBranch(branch); err != nil {
		return gitHubWriteResponse{}, err
	}
	message = strings.TrimSpace(message)
	if message == "" || len([]rune(message)) > 300 {
		return gitHubWriteResponse{}, errors.New("GitHub 提交说明不能为空且不能超过 300 字符")
	}
	if len([]byte(content)) > maximumGitHubTextFileBytes || !utf8.ValidString(content) || strings.IndexByte(content, 0) >= 0 {
		return gitHubWriteResponse{}, errors.New("GitHub 文件必须是有效 UTF-8 文本且不超过 1 MiB")
	}
	if expectedSHA != "" && !gitHubObjectSHAPattern.MatchString(expectedSHA) {
		return gitHubWriteResponse{}, errors.New("expected_sha 不是有效 Git 对象 SHA")
	}
	current, readErr := readGitHubFile(ctx, client, config, repository, filePath, branch)
	if readErr == nil {
		actual := fmt.Sprint(current["sha"])
		if expectedSHA == "" {
			return gitHubWriteResponse{}, errors.New("远端文件已存在；更新前必须读取并提供 expected_sha")
		}
		if !strings.EqualFold(expectedSHA, actual) {
			return gitHubWriteResponse{}, errors.New("远端文件已变化，expected_sha 不匹配；请重新读取后再提交")
		}
	} else {
		var failure *gitHubAPIError
		if !errors.As(readErr, &failure) || failure.StatusCode != http.StatusNotFound {
			return gitHubWriteResponse{}, readErr
		}
		if expectedSHA != "" {
			return gitHubWriteResponse{}, errors.New("远端文件不存在，但提供了 expected_sha")
		}
	}
	payload := map[string]any{"message": message, "content": base64.StdEncoding.EncodeToString([]byte(content)), "branch": branch}
	if expectedSHA != "" {
		payload["sha"] = expectedSHA
	}
	body, err := performGitHubRequest(ctx, client, config, http.MethodPut, base+"/contents/"+encodedPath, payload, true)
	var result gitHubWriteResponse
	if err == nil {
		err = json.Unmarshal(body, &result)
	}
	return result, err
}

func createGitHubIssue(ctx context.Context, client *http.Client, config runtimeGitHubConfig, repository, title, bodyText string) (gitHubWriteResponse, error) {
	base, _, err := gitHubRepositoryPath(repository)
	if err != nil {
		return gitHubWriteResponse{}, err
	}
	if err := validateGitHubText(title, 256, "Issue 标题"); err != nil {
		return gitHubWriteResponse{}, err
	}
	if len([]rune(bodyText)) > maximumGitHubBodyRunes {
		return gitHubWriteResponse{}, errors.New("Issue 正文不能超过 65536 字符")
	}
	body, err := performGitHubRequest(ctx, client, config, http.MethodPost, base+"/issues", map[string]any{"title": strings.TrimSpace(title), "body": bodyText}, true)
	var result gitHubWriteResponse
	if err == nil {
		err = json.Unmarshal(body, &result)
	}
	return result, err
}

func createGitHubPullRequest(ctx context.Context, client *http.Client, config runtimeGitHubConfig, repository, title, head, baseRef, bodyText string, draft bool) (gitHubWriteResponse, error) {
	base, _, err := gitHubRepositoryPath(repository)
	if err != nil {
		return gitHubWriteResponse{}, err
	}
	if err := validateGitHubText(title, 256, "Pull Request 标题"); err != nil {
		return gitHubWriteResponse{}, err
	}
	if err := validateGitHubPullHead(head); err != nil {
		return gitHubWriteResponse{}, err
	}
	if err := validateGitHubBranch(baseRef); err != nil {
		return gitHubWriteResponse{}, err
	}
	if len([]rune(bodyText)) > maximumGitHubBodyRunes {
		return gitHubWriteResponse{}, errors.New("Pull Request 正文不能超过 65536 字符")
	}
	body, err := performGitHubRequest(ctx, client, config, http.MethodPost, base+"/pulls", map[string]any{
		"title": strings.TrimSpace(title), "head": head, "base": baseRef, "body": bodyText, "draft": draft,
	}, true)
	var result gitHubWriteResponse
	if err == nil {
		err = json.Unmarshal(body, &result)
	}
	return result, err
}

func gitHubRepositoryPath(repository string) (string, string, error) {
	repository = strings.TrimSpace(repository)
	if !githubRepositoryPattern.MatchString(repository) || len(repository) > 200 {
		return "", "", errors.New("GitHub 仓库格式应为 owner/repository")
	}
	parts := strings.SplitN(repository, "/", 2)
	return "/repos/" + url.PathEscape(parts[0]) + "/" + url.PathEscape(parts[1]), repository, nil
}

func gitHubFilePath(value string) (string, error) {
	value = strings.TrimSpace(value)
	if strings.Contains(value, "\\") {
		return "", errors.New("GitHub 文件路径必须使用正斜杠")
	}
	if value == "" || strings.HasPrefix(value, "/") || len(value) > 1_024 || strings.ContainsAny(value, "\r\n") {
		return "", errors.New("GitHub 文件路径无效")
	}
	parts := strings.Split(value, "/")
	for index, part := range parts {
		if part == "" || part == "." || part == ".." {
			return "", errors.New("GitHub 文件路径不能包含空段、. 或 ..")
		}
		parts[index] = url.PathEscape(part)
	}
	return strings.Join(parts, "/"), nil
}

func validateGitHubBranch(value string) error {
	if err := validateGitHubRef(value); err != nil {
		return err
	}
	if strings.Contains(value, ":") {
		return errors.New("GitHub 分支名称不能包含冒号")
	}
	return nil
}

func validateGitHubPullHead(value string) error {
	value = strings.TrimSpace(value)
	parts := strings.Split(value, ":")
	if len(parts) == 1 {
		return validateGitHubBranch(value)
	}
	if len(parts) != 2 || !gitHubOwnerPattern.MatchString(parts[0]) {
		return errors.New("GitHub Pull Request head 必须是 branch 或 owner:branch")
	}
	return validateGitHubBranch(parts[1])
}

func validateGitHubRef(value string) error {
	value = strings.TrimSpace(value)
	if value == "" || len(value) > 200 || value == "@" || strings.HasPrefix(value, "/") || strings.HasSuffix(value, "/") || strings.HasSuffix(value, ".") ||
		strings.Contains(value, "..") || strings.Contains(value, "//") || strings.Contains(value, "@{") || strings.ContainsAny(value, "\\ ~^:?*[") {
		return errors.New("GitHub 分支或引用名称无效")
	}
	for _, part := range strings.Split(value, "/") {
		if part == "" || part == "." || part == ".." || strings.HasSuffix(strings.ToLower(part), ".lock") {
			return errors.New("GitHub 分支或引用名称无效")
		}
	}
	for _, character := range value {
		if character < 0x20 || character == 0x7f {
			return errors.New("GitHub 分支或引用名称包含控制字符")
		}
	}
	return nil
}

func validateGitHubText(value string, maximum int, label string) error {
	value = strings.TrimSpace(value)
	if value == "" || len([]rune(value)) > maximum {
		return fmt.Errorf("%s不能为空且不能超过 %d 字符", label, maximum)
	}
	return nil
}
