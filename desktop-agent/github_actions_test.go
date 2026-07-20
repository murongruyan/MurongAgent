package main

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestGitHubActionsReaderUsesFixedGETAndReturnsFiveRuns(t *testing.T) {
	server := httptest.NewTLSServer(http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		if request.Method != http.MethodGet || request.URL.Path != "/repos/murong/example/actions/runs" || request.URL.Query().Get("per_page") != "5" {
			t.Errorf("unexpected request: %s %s", request.Method, request.URL.String())
		}
		if request.Header.Get("Authorization") != "Bearer test-token" {
			t.Errorf("missing bearer token: %#v", request.Header)
		}
		if request.Header.Get("X-GitHub-Api-Version") != githubAPIVersion || request.Header.Get("User-Agent") != githubUserAgent {
			t.Errorf("missing GitHub headers: %#v", request.Header)
		}
		response.Header().Set("Content-Type", "application/json")
		fmt.Fprint(response, `{"workflow_runs":[
          {"name":"Build","status":"completed","conclusion":"success","head_branch":"main"},
          {"name":"Test","status":"in_progress","conclusion":null,"head_branch":"feature"},
          {"name":"Lint","status":"completed","conclusion":"failure"},
          {"name":"Deploy","status":"queued","conclusion":null},
          {"display_title":"Docs","status":"completed","conclusion":"success"},
          {"name":"Ignored sixth","status":"completed","conclusion":"success"}
        ]}`)
	}))
	defer server.Close()

	summary, err := readGitHubActionsStatus(context.Background(), server.Client(), runtimeGitHubConfig{
		APIBaseURL: server.URL,
		Token:      "test-token",
	}, "murong/example")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(summary, "Build（main）：success") || !strings.Contains(summary, "Test（feature）：in_progress") || strings.Contains(summary, "Ignored sixth") {
		t.Fatalf("unexpected summary: %s", summary)
	}
}

func TestGitHubConnectionReturnsViewerAndDoesNotRedirectToken(t *testing.T) {
	server := httptest.NewTLSServer(http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		if request.URL.Path != "/user" || request.Header.Get("Authorization") != "Bearer viewer-token" {
			t.Errorf("unexpected viewer request: %s %#v", request.URL.Path, request.Header)
		}
		fmt.Fprint(response, `{"login":"murong-user"}`)
	}))
	defer server.Close()
	viewer, err := testGitHubConnection(context.Background(), server.Client(), runtimeGitHubConfig{APIBaseURL: server.URL, Token: "viewer-token"})
	if err != nil || viewer != "murong-user" {
		t.Fatalf("unexpected viewer %q: %v", viewer, err)
	}

	redirectTarget := httptest.NewTLSServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {
		t.Fatal("redirect target must not receive GitHub credentials")
	}))
	defer redirectTarget.Close()
	redirectSource := httptest.NewTLSServer(http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		http.Redirect(response, request, redirectTarget.URL, http.StatusFound)
	}))
	defer redirectSource.Close()
	client := redirectSource.Client()
	client.CheckRedirect = newGitHubHTTPClient().CheckRedirect
	if _, err := testGitHubConnection(context.Background(), client, runtimeGitHubConfig{APIBaseURL: redirectSource.URL, Token: "secret"}); err == nil {
		t.Fatal("GitHub redirect was not blocked")
	}
}

func TestGitHubActionsReaderAllowsAnonymousPublicQueryAndBoundsErrors(t *testing.T) {
	server := httptest.NewTLSServer(http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		if request.Header.Get("Authorization") != "" {
			t.Errorf("anonymous query unexpectedly had authorization: %#v", request.Header)
		}
		response.WriteHeader(http.StatusForbidden)
		fmt.Fprint(response, `{"message":"rate limit"}`)
	}))
	defer server.Close()
	_, err := readGitHubActionsStatus(context.Background(), server.Client(), runtimeGitHubConfig{APIBaseURL: server.URL}, "public/repository")
	if err == nil || !strings.Contains(err.Error(), "HTTP 403") || !strings.Contains(err.Error(), "rate limit") {
		t.Fatalf("unexpected GitHub error: %v", err)
	}
}
