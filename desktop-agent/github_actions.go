package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

const (
	githubAPIVersion  = "2026-03-10"
	maximumGitHubBody = 2 << 20
	githubUserAgent   = "MurongDesktopAgent/1.0"
)

type gitHubActionsResponse struct {
	WorkflowRuns []gitHubWorkflowRun `json:"workflow_runs"`
}

type gitHubWorkflowRun struct {
	Name         string `json:"name"`
	DisplayTitle string `json:"display_title"`
	Status       string `json:"status"`
	Conclusion   string `json:"conclusion"`
	HeadBranch   string `json:"head_branch"`
	HTMLURL      string `json:"html_url"`
	UpdatedAt    string `json:"updated_at"`
}

type gitHubViewerResponse struct {
	Login string `json:"login"`
}

type gitHubAPIError struct {
	StatusCode int
	Message    string
}

func (failure *gitHubAPIError) Error() string {
	return fmt.Sprintf("GitHub API 返回 HTTP %d：%s", failure.StatusCode, failure.Message)
}

func newGitHubHTTPClient() *http.Client {
	return &http.Client{
		Timeout: 45 * time.Second,
		CheckRedirect: func(*http.Request, []*http.Request) error {
			return errors.New("GitHub API 返回了重定向，已阻止携带凭据跳转")
		},
	}
}

func testGitHubConnection(ctx context.Context, client *http.Client, config runtimeGitHubConfig) (string, error) {
	if strings.TrimSpace(config.Token) == "" {
		return "", errors.New("请先填写并保存 GitHub Token")
	}
	body, err := performGitHubGET(ctx, client, config, "/user")
	if err != nil {
		return "", err
	}
	var viewer gitHubViewerResponse
	if err := json.Unmarshal(body, &viewer); err != nil {
		return "", fmt.Errorf("GitHub 用户信息格式无效：%w", err)
	}
	viewer.Login = strings.TrimSpace(viewer.Login)
	if viewer.Login == "" {
		return "", errors.New("GitHub 未返回登录用户名")
	}
	return viewer.Login, nil
}

func readGitHubActionsStatus(ctx context.Context, client *http.Client, config runtimeGitHubConfig, repository string) (string, error) {
	repository = strings.TrimSpace(repository)
	if !githubRepositoryPattern.MatchString(repository) {
		return "", errors.New("GitHub 仓库格式应为 owner/repository")
	}
	parts := strings.SplitN(repository, "/", 2)
	path := "/repos/" + url.PathEscape(parts[0]) + "/" + url.PathEscape(parts[1]) + "/actions/runs?per_page=5"
	body, err := performGitHubGET(ctx, client, config, path)
	if err != nil {
		return "", err
	}
	var response gitHubActionsResponse
	if err := json.Unmarshal(body, &response); err != nil {
		return "", fmt.Errorf("GitHub Actions 响应格式无效：%w", err)
	}
	entries := make([]string, 0, 5)
	for _, run := range response.WorkflowRuns {
		if len(entries) >= 5 {
			break
		}
		name := strings.TrimSpace(run.Name)
		if name == "" {
			name = strings.TrimSpace(run.DisplayTitle)
		}
		if name == "" {
			name = "未命名工作流"
		}
		status := strings.TrimSpace(run.Conclusion)
		if status == "" {
			status = strings.TrimSpace(run.Status)
		}
		if status == "" {
			status = "unknown"
		}
		branch := strings.TrimSpace(run.HeadBranch)
		if branch != "" {
			entries = append(entries, fmt.Sprintf("%s（%s）：%s", truncateRunes(name, 80), truncateRunes(branch, 80), truncateRunes(status, 40)))
		} else {
			entries = append(entries, fmt.Sprintf("%s：%s", truncateRunes(name, 80), truncateRunes(status, 40)))
		}
	}
	if len(entries) == 0 {
		return "GitHub Actions 已查询 " + repository + "：暂未返回工作流运行记录", nil
	}
	return "GitHub Actions 已查询 " + repository + "：" + strings.Join(entries, "；"), nil
}

func performGitHubGET(ctx context.Context, client *http.Client, config runtimeGitHubConfig, path string) ([]byte, error) {
	return performGitHubRequest(ctx, client, config, http.MethodGet, path, nil, false)
}

func performGitHubRequest(ctx context.Context, client *http.Client, config runtimeGitHubConfig, method, path string, payload any, requireToken bool) ([]byte, error) {
	if client == nil {
		client = newGitHubHTTPClient()
	}
	if !strings.HasPrefix(path, "/") || strings.HasPrefix(path, "//") || strings.ContainsAny(path, "\r\n") {
		return nil, errors.New("GitHub API 请求路径无效")
	}
	baseURL := normalizeGitHubAPIBaseURL(config.APIBaseURL)
	if err := validateGitHubAPIBaseURL(baseURL); err != nil {
		return nil, err
	}
	if requireToken && strings.TrimSpace(config.Token) == "" {
		return nil, errors.New("该 GitHub 写入操作需要先配置 Token")
	}
	var requestBody io.Reader
	if payload != nil {
		encoded, err := json.Marshal(payload)
		if err != nil {
			return nil, fmt.Errorf("编码 GitHub API 请求失败：%w", err)
		}
		if len(encoded) > maximumGitHubBody {
			return nil, errors.New("GitHub API 请求超过大小限制")
		}
		requestBody = bytes.NewReader(encoded)
	}
	request, err := http.NewRequestWithContext(ctx, method, baseURL+path, requestBody)
	if err != nil {
		return nil, err
	}
	request.Header.Set("Accept", "application/vnd.github+json")
	request.Header.Set("X-GitHub-Api-Version", githubAPIVersion)
	request.Header.Set("User-Agent", githubUserAgent)
	if payload != nil {
		request.Header.Set("Content-Type", "application/json")
	}
	if token := strings.TrimSpace(config.Token); token != "" {
		request.Header.Set("Authorization", "Bearer "+token)
	}
	response, err := client.Do(request)
	if err != nil {
		return nil, fmt.Errorf("GitHub API 请求失败：%w", err)
	}
	defer response.Body.Close()
	body, err := io.ReadAll(io.LimitReader(response.Body, maximumGitHubBody+1))
	if err != nil {
		return nil, fmt.Errorf("读取 GitHub API 响应失败：%w", err)
	}
	if len(body) > maximumGitHubBody {
		return nil, errors.New("GitHub API 响应超过大小限制")
	}
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		var failure struct {
			Message string `json:"message"`
		}
		_ = json.Unmarshal(body, &failure)
		message := truncateRunes(failure.Message, 300)
		if message == "" {
			message = http.StatusText(response.StatusCode)
		}
		return nil, &gitHubAPIError{StatusCode: response.StatusCode, Message: message}
	}
	return body, nil
}
