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
		"\"-DARCHS=ARM64\"",
		"\"-DMNN_ARM82=OFF\"",
		"\"-DMNN_KLEIDIAI=OFF\"",
		"\"-DMNN_SME2=OFF\"",
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

func TestWindowsVLMBuildCompilesARM64AssemblyWithoutVisualStudioTPAndUsesCompatibleJDK(t *testing.T) {
	cmakeConfig, err := os.ReadFile("vlm-runtime/CMakeLists.txt")
	if err != nil {
		t.Fatal(err)
	}
	for _, marker := range []string{
		"TARGET MNNARM64",
		`REGEX "\\.[sS]$"`,
		"--target=arm64-pc-windows-msvc",
		"murong-mnn-arm64-assembly",
		"target_sources(MNN PRIVATE",
		"EXTERNAL_OBJECT TRUE",
	} {
		if !strings.Contains(string(cmakeConfig), marker) {
			t.Fatalf("Windows ARM64 MNN assembly regression guard is missing %q", marker)
		}
	}
	if strings.Contains(string(cmakeConfig), `VS_TOOL_OVERRIDE "ClCompile"`) {
		t.Fatal("Windows ARM64 MNN assembly still uses ClCompile, which adds /TP")
	}

	script, err := os.ReadFile("scripts/build-vlm-runtimes.ps1")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(script), "& javac --release 21") {
		t.Fatal("LiteRT-LM Java helper is not compiled for its Java 21 dependency baseline")
	}

	workflow, err := os.ReadFile("../.github/workflows/build-all.yml")
	if err != nil {
		t.Fatal(err)
	}
	workflowText := string(workflow)
	if !strings.Contains(workflowText, `LITERT_JAVA_VERSION: "21"`) {
		t.Fatal("LiteRT-LM desktop JDK baseline is not pinned to Java 21")
	}
	desktopJobIndex := strings.Index(workflowText, "\n  desktop:\n")
	if desktopJobIndex < 0 {
		t.Fatal("desktop workflow job is missing")
	}
	desktopJob := workflowText[desktopJobIndex:]
	for _, marker := range []string{
		"- name: Set Up JDK ${{ env.LITERT_JAVA_VERSION }}",
		"if: matrix.goos == 'windows' && matrix.goarch == 'amd64'",
		"uses: actions/setup-java@v5",
		"java-version: ${{ env.LITERT_JAVA_VERSION }}",
	} {
		if !strings.Contains(desktopJob, marker) {
			t.Fatalf("Windows desktop Java toolchain setup is missing %q", marker)
		}
	}
}

func TestUnifiedReleaseWorkflowAcceptsVersionOverrides(t *testing.T) {
	workflow, err := os.ReadFile("../.github/workflows/build-all.yml")
	if err != nil {
		t.Fatal(err)
	}
	text := string(workflow)
	for _, marker := range []string{
		"main_version_name:",
		"main_version_code:",
		"extension_version_name:",
		"extension_version_code:",
		"INPUT_VERSION_NAME: ${{ inputs.main_version_name }}",
		"INPUT_VERSION_CODE: ${{ inputs.main_version_code }}",
		"INPUT_VERSION_NAME: ${{ inputs.extension_version_name }}",
		"INPUT_VERSION_CODE: ${{ inputs.extension_version_code }}",
		`version_name="${INPUT_VERSION_NAME:-$(read_gradle_default versionName)}"`,
		`version_code="${INPUT_VERSION_CODE:-$(read_gradle_default versionCode)}"`,
		"version_code <= 2100000000",
	} {
		if !strings.Contains(text, marker) {
			t.Fatalf("unified release version override is missing %q", marker)
		}
	}
}
