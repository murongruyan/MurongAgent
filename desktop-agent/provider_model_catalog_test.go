package main

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"reflect"
	"testing"
)

func TestFetchProviderModelEndpointUsesProviderAuthenticationAndParsesModels(t *testing.T) {
	tests := []struct {
		name       string
		providerID string
		body       string
		wantHeader string
		wantValue  string
		wantModels []string
	}{
		{name: "openai", providerID: "openai-compatible", body: "{\"data\":[{\"id\":\"gpt-one\"},{\"model\":\"gpt-two\"}]}", wantHeader: "Authorization", wantValue: "Bearer secret", wantModels: []string{"gpt-one", "gpt-two"}},
		{name: "claude", providerID: "claude", body: "{\"models\":[{\"id\":\"claude-one\"}]}", wantHeader: "x-api-key", wantValue: "secret", wantModels: []string{"claude-one"}},
		{name: "gemini", providerID: "gemini", body: "{\"models\":[{\"name\":\"models/gemini-one\"}]}", wantHeader: "x-goog-api-key", wantValue: "secret", wantModels: []string{"gemini-one"}},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
				if got := request.Header.Get(test.wantHeader); got != test.wantValue {
					t.Errorf("unexpected %s header: %q", test.wantHeader, got)
				}
				if test.providerID == "claude" && request.Header.Get("anthropic-version") != "2023-06-01" {
					t.Error("Claude model request omitted anthropic-version")
				}
				fmt.Fprint(response, test.body)
			}))
			defer server.Close()
			models, err := fetchProviderModelEndpoint(context.Background(), server.Client(), server.URL, test.providerID, "secret")
			if err != nil {
				t.Fatal(err)
			}
			if !reflect.DeepEqual(models, test.wantModels) {
				t.Fatalf("unexpected models: %#v", models)
			}
		})
	}
}

func TestProviderModelCatalogCandidatesFollowProviderProtocols(t *testing.T) {
	tests := []struct {
		providerID string
		baseURL    string
		want       []string
	}{
		{"openai-compatible", "https://api.example/v1/", []string{"https://api.example/v1/models", "https://api.example/models"}},
		{"claude", "https://api.example", []string{"https://api.example/v1/models", "https://api.example/models"}},
		{"gemini", "https://api.example/v1beta", []string{"https://api.example/v1beta/models"}},
	}
	for _, test := range tests {
		if got := providerModelEndpointCandidates(test.providerID, test.baseURL); !reflect.DeepEqual(got, test.want) {
			t.Errorf("%s candidates = %#v, want %#v", test.providerID, got, test.want)
		}
	}
}

func TestParseProviderModelIDsRejectsInvalidOrEmptyCatalogs(t *testing.T) {
	if _, err := parseProviderModelIDs([]byte("not-json")); err == nil {
		t.Fatal("invalid model catalog JSON passed validation")
	}
	models, err := parseProviderModelIDs([]byte("{\"items\":[\"one\",{\"name\":\"two\"},\"one\"]}"))
	if err != nil || !reflect.DeepEqual(models, []string{"one", "two"}) {
		t.Fatalf("unexpected parsed catalog: %#v, %v", models, err)
	}
}
