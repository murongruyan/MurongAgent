package main

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
)

const testGitHubSHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

func TestGitHubRepositoryReadToolsUseBoundedRESTEndpoints(t *testing.T) {
	server := httptest.NewTLSServer(http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		assertGitHubToolRequest(t, request, http.MethodGet, "read-token")
		response.Header().Set("Content-Type", "application/json")
		switch request.URL.Path {
		case "/repos/murong/example":
			fmt.Fprint(response, `{"full_name":"murong/example","description":"demo","default_branch":"main","html_url":"https://github.example/murong/example","open_issues_count":2}`)
		case "/repos/murong/example/contents/docs/readme.md":
			if request.URL.Query().Get("ref") != "main" {
				t.Errorf("unexpected file ref: %s", request.URL.RawQuery)
			}
			fmt.Fprintf(response, `{"type":"file","path":"docs/readme.md","sha":"%s","size":5,"encoding":"base64","content":"%s"}`, testGitHubSHA, base64.StdEncoding.EncodeToString([]byte("hello")))
		case "/repos/murong/example/branches":
			if request.URL.Query().Get("per_page") != "30" {
				t.Errorf("unexpected branches query: %s", request.URL.RawQuery)
			}
			fmt.Fprintf(response, `[{"name":"main","protected":true,"commit":{"sha":"%s"}}]`, testGitHubSHA)
		case "/repos/murong/example/issues":
			if request.URL.Query().Get("state") != "all" || request.URL.Query().Get("per_page") != "5" {
				t.Errorf("unexpected issues query: %s", request.URL.RawQuery)
			}
			fmt.Fprint(response, `[{"number":7,"title":"Bug","state":"open","user":{"login":"alice"}},{"number":8,"title":"PR","pull_request":{"url":"x"}}]`)
		default:
			http.NotFound(response, request)
		}
	}))
	defer server.Close()
	config := runtimeGitHubConfig{APIBaseURL: server.URL, Token: "read-token"}

	repository, err := readGitHubRepository(context.Background(), server.Client(), config, "murong/example")
	if err != nil || repository.FullName != "murong/example" || repository.DefaultBranch != "main" {
		t.Fatalf("unexpected repository: %#v, %v", repository, err)
	}
	file, err := readGitHubFile(context.Background(), server.Client(), config, "murong/example", "docs/readme.md", "main")
	if err != nil || file["content"] != "hello" || file["sha"] != testGitHubSHA {
		t.Fatalf("unexpected file: %#v, %v", file, err)
	}
	branches, err := listGitHubBranches(context.Background(), server.Client(), config, "murong/example")
	if err != nil || len(branches) != 1 || branches[0].Name != "main" || !branches[0].Protected {
		t.Fatalf("unexpected branches: %#v, %v", branches, err)
	}
	issues, err := listGitHubIssues(context.Background(), server.Client(), config, "murong/example", "all", 5)
	if err != nil || len(issues) != 1 || issues[0].Number != 7 {
		t.Fatalf("unexpected issues: %#v, %v", issues, err)
	}
}

func TestGitHubWriteToolsRequireTokenAndPreserveRemoteSHA(t *testing.T) {
	var writes atomic.Int32
	server := httptest.NewTLSServer(http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		response.Header().Set("Content-Type", "application/json")
		if request.Method != http.MethodGet {
			writes.Add(1)
			assertGitHubToolRequest(t, request, request.Method, "write-token")
		}
		switch request.Method + " " + request.URL.Path {
		case "GET /repos/murong/example/git/ref/heads/main":
			fmt.Fprintf(response, `{"ref":"refs/heads/main","object":{"sha":"%s"}}`, testGitHubSHA)
		case "POST /repos/murong/example/git/refs":
			var payload struct{ Ref, SHA string }
			if err := json.NewDecoder(request.Body).Decode(&payload); err != nil || payload.Ref != "refs/heads/feature/test" || payload.SHA != testGitHubSHA {
				t.Errorf("unexpected branch payload: %#v, %v", payload, err)
			}
			fmt.Fprintf(response, `{"ref":"%s","object":{"sha":"%s"}}`, payload.Ref, payload.SHA)
		case "GET /repos/murong/example/contents/existing.txt":
			fmt.Fprintf(response, `{"type":"file","path":"existing.txt","sha":"%s","size":3,"encoding":"base64","content":"b2xk"}`, testGitHubSHA)
		case "PUT /repos/murong/example/contents/existing.txt":
			var payload map[string]any
			_ = json.NewDecoder(request.Body).Decode(&payload)
			if payload["sha"] != testGitHubSHA || payload["branch"] != "feature/test" || payload["content"] != base64.StdEncoding.EncodeToString([]byte("new")) {
				t.Errorf("unexpected file payload: %#v", payload)
			}
			fmt.Fprintf(response, `{"content":{"path":"existing.txt","sha":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"},"commit":{"sha":"cccccccccccccccccccccccccccccccccccccccc","html_url":"https://example/commit"}}`)
		case "GET /repos/murong/example/contents/new.txt":
			response.WriteHeader(http.StatusNotFound)
			fmt.Fprint(response, `{"message":"Not Found"}`)
		case "PUT /repos/murong/example/contents/new.txt":
			var payload map[string]any
			_ = json.NewDecoder(request.Body).Decode(&payload)
			if _, hasSHA := payload["sha"]; hasSHA {
				t.Errorf("new file unexpectedly included SHA: %#v", payload)
			}
			fmt.Fprint(response, `{"content":{"path":"new.txt","sha":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"},"commit":{"sha":"cccccccccccccccccccccccccccccccccccccccc"}}`)
		case "POST /repos/murong/example/issues":
			fmt.Fprint(response, `{"number":9,"html_url":"https://example/issues/9"}`)
		case "POST /repos/murong/example/pulls":
			fmt.Fprint(response, `{"number":10,"html_url":"https://example/pulls/10"}`)
		default:
			http.NotFound(response, request)
		}
	}))
	defer server.Close()
	config := runtimeGitHubConfig{APIBaseURL: server.URL, Token: "write-token"}

	branch, err := createGitHubBranch(context.Background(), server.Client(), config, "murong/example", "feature/test", "main")
	if err != nil || branch.Ref != "refs/heads/feature/test" {
		t.Fatalf("create branch failed: %#v, %v", branch, err)
	}
	updated, err := putGitHubFile(context.Background(), server.Client(), config, "murong/example", "existing.txt", "feature/test", "Update file", "new", testGitHubSHA)
	if err != nil || updated.Commit == nil || updated.Commit.SHA == "" {
		t.Fatalf("update file failed: %#v, %v", updated, err)
	}
	created, err := putGitHubFile(context.Background(), server.Client(), config, "murong/example", "new.txt", "feature/test", "Create file", "created", "")
	if err != nil || created.Content == nil || created.Content.Path != "new.txt" {
		t.Fatalf("create file failed: %#v, %v", created, err)
	}
	issue, err := createGitHubIssue(context.Background(), server.Client(), config, "murong/example", "New issue", "Details")
	if err != nil || issue.Number != 9 {
		t.Fatalf("create issue failed: %#v, %v", issue, err)
	}
	pull, err := createGitHubPullRequest(context.Background(), server.Client(), config, "murong/example", "New PR", "feature/test", "main", "Details", true)
	if err != nil || pull.Number != 10 {
		t.Fatalf("create pull request failed: %#v, %v", pull, err)
	}

	writesBeforeConflict := writes.Load()
	if _, err := putGitHubFile(context.Background(), server.Client(), config, "murong/example", "existing.txt", "feature/test", "Overwrite", "bad", strings.Repeat("b", 40)); err == nil || !strings.Contains(err.Error(), "expected_sha 不匹配") {
		t.Fatalf("remote SHA conflict was not blocked: %v", err)
	}
	if writes.Load() != writesBeforeConflict {
		t.Fatal("remote SHA conflict reached a write endpoint")
	}

	requestCount := writes.Load()
	if _, err := createGitHubIssue(context.Background(), server.Client(), runtimeGitHubConfig{APIBaseURL: server.URL}, "murong/example", "No token", ""); err == nil || !strings.Contains(err.Error(), "需要先配置 Token") {
		t.Fatalf("anonymous write was not blocked: %v", err)
	}
	if writes.Load() != requestCount {
		t.Fatal("anonymous write reached the server")
	}
}

func TestGitHubToolValidationAndPlanModeBoundary(t *testing.T) {
	for _, path := range []string{"", "../secret", "/root", "dir//file", "dir/./file", `dir\\file`} {
		if _, err := gitHubFilePath(path); err == nil {
			t.Fatalf("unsafe GitHub path passed: %q", path)
		}
	}
	for _, ref := range []string{"", "../main", "feature..bad", "feature.lock", "bad ref", "bad@{ref"} {
		if err := validateGitHubRef(ref); err == nil {
			t.Fatalf("unsafe GitHub ref passed: %q", ref)
		}
	}
	if err := validateGitHubPullHead("fork-owner:feature/test"); err != nil {
		t.Fatalf("valid fork pull request head was rejected: %v", err)
	}
	app := &DesktopAgentApp{terminals: []TerminalBackend{{ID: terminalCMD, Label: "CMD"}}}
	tools := app.planModeToolDefinitions(app.toolDefinitions(defaultDesktopConfig()))
	seen := map[string]bool{}
	for _, raw := range tools {
		seen[functionToolName(raw)] = true
	}
	for _, name := range []string{"github_repository", "github_read_file", "github_list_branches", "github_list_issues"} {
		if !seen[name] {
			t.Fatalf("plan mode is missing read-only GitHub tool %s", name)
		}
	}
	for _, name := range []string{"github_create_branch", "github_put_file", "github_create_issue", "github_create_pull_request"} {
		if seen[name] {
			t.Fatalf("plan mode exposed GitHub write tool %s", name)
		}
	}
}

func assertGitHubToolRequest(t *testing.T, request *http.Request, method, token string) {
	t.Helper()
	if request.Method != method {
		t.Errorf("unexpected method: %s, want %s", request.Method, method)
	}
	if request.Header.Get("Authorization") != "Bearer "+token || request.Header.Get("X-GitHub-Api-Version") != githubAPIVersion || request.Header.Get("User-Agent") != githubUserAgent {
		t.Errorf("missing GitHub request headers: %#v", request.Header)
	}
	if method != http.MethodGet && request.Header.Get("Content-Type") != "application/json" {
		t.Errorf("missing JSON content type: %#v", request.Header)
	}
}
