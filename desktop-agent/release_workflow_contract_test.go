package main

import (
	"os"
	"strings"
	"testing"
)

func TestUnifiedReleasePublishesLatestReleaseNotes(t *testing.T) {
	workflow, err := os.ReadFile("../.github/workflows/build-all.yml")
	if err != nil {
		t.Fatal(err)
	}
	text := string(workflow)
	for _, marker := range []string{
		"- name: Checkout Release Notes",
		"ref: ${{ github.sha }}",
		"cp release-notes/latest.md dist/release/RELEASE_NOTES.md",
		"dist/release/RELEASE_NOTES.md",
		"gh release edit \"$tag\" --title \"Murong Complete Suite ${tag}\" --notes-file release/RELEASE_NOTES.md",
		"gh release create \"$tag\" \"${assets[@]}\" --notes-file release/RELEASE_NOTES.md",
	} {
		if !strings.Contains(text, marker) {
			t.Fatalf("unified release workflow is missing %q", marker)
		}
	}
	if strings.Contains(text, "gh release create \"$tag\" \"${assets[@]}\" --generate-notes") {
		t.Fatal("GitHub Release still replaces curated latest.md with generated notes")
	}
}

func TestWindowsVLMBuildSelectsInstalledVisualStudioAndARM64ClangCL(t *testing.T) {
	script, err := os.ReadFile("scripts/build-vlm-runtimes.ps1")
	if err != nil {
		t.Fatal(err)
	}
	text := string(script)
	for _, marker := range []string{
		"Get-CMakeVisualStudioArguments $Architecture",
		"18 { \"Visual Studio 18 2026\" }",
		"$arguments += @(\"-T\", \"ClangCL\")",
		"\"-DMNN_KLEIDIAI=OFF\", \"-DMNN_SME2=OFF\"",
		"Assert-ChildPath $buildRoot $mnnBuild",
		"& cmake -S $sourceRoot -B $mnnBuild @cmakeVisualStudioArguments",
	} {
		if !strings.Contains(text, marker) {
			t.Fatalf("Windows VLM build regression guard is missing %q", marker)
		}
	}
	if strings.Contains(text, "-G \"Visual Studio 17 2022\"") {
		t.Fatal("Windows VLM CMake invocation still hardcodes Visual Studio 2022")
	}
}
