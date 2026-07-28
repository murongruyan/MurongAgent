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
		"release/release-manifest.json release/RELEASE_NOTES.md",
		"gh release edit \"$tag\" --title \"$title\" --notes-file release/RELEASE_NOTES.md",
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
		"suite_version_name:",
		"suite_build_number:",
		"extension_version_name:",
		"extension_version_code:",
		"extension_toolchain_version:",
		"INPUT_VERSION_NAME: ${{ inputs.suite_version_name }}",
		"INPUT_VERSION_CODE: ${{ inputs.suite_build_number }}",
		"INPUT_VERSION_NAME: ${{ inputs.extension_version_name }}",
		"INPUT_VERSION_CODE: ${{ inputs.extension_version_code }}",
		"INPUT_TOOLCHAIN_VERSION: ${{ inputs.extension_toolchain_version }}",
		"- name: Prepare Shared Desktop Version",
		"DESKTOP_VERSION_NAME=$version",
		`version_name="${INPUT_VERSION_NAME:-$(read_gradle_default versionName)}"`,
		`version_code="${INPUT_VERSION_CODE:-$(read_gradle_default versionCode)}"`,
		`toolchain_version="${INPUT_TOOLCHAIN_VERSION:-$(read_gradle_default toolchainVersion)}"`,
		"version_code <= 2100000000",
	} {
		if !strings.Contains(text, marker) {
			t.Fatalf("unified release version override is missing %q", marker)
		}
	}
}

func TestUnifiedReleaseManifestDeclaresDesktopVersion(t *testing.T) {
	workflow, err := os.ReadFile("../.github/workflows/build-all.yml")
	if err != nil {
		t.Fatal(err)
	}
	text := string(workflow)
	for _, marker := range []string{
		`DESKTOP_VERSION_NAME="${MAIN_VERSION_NAME}"`,
		`export DESKTOP_VERSION_NAME`,
		`"desktopAgent": {"name": os.environ["DESKTOP_VERSION_NAME"]}`,
		`"toolchainVersion": os.environ["EXTENSION_TOOLCHAIN_VERSION"]`,
	} {
		if !strings.Contains(text, marker) {
			t.Fatalf("unified release manifest is missing desktop version marker %q", marker)
		}
	}
}

func TestDesktopReleasePublishesThinAppAndIndependentRuntimePackages(t *testing.T) {
	workflow, err := os.ReadFile("../.github/workflows/build-all.yml")
	if err != nil {
		t.Fatal(err)
	}
	text := string(workflow)
	for _, marker := range []string{
		"- name: Package Independent Desktop Runtimes",
		`$codexName = "murong-runtime-codex-${{ matrix.goos }}-${{ matrix.goarch }}-${env:CODEX_VERSION}.tgz"`,
		"go run ./cmd/runtime-packager",
		"murong-runtime-mnn-windows-arm64-builtin-vlm-2026-07-v1.tgz",
		"murong-runtime-llama-windows-amd64-builtin-vlm-2026-07-v1.tgz",
		"murong-runtime-litert-windows-amd64-builtin-vlm-2026-07-v1.tgz",
		`"kind": "runtime" if path.name.startswith("murong-runtime-") else "application"`,
		"Expected exactly 18 application/runtime packages",
	} {
		if !strings.Contains(text, marker) {
			t.Fatalf("independent runtime release contract is missing %q", marker)
		}
	}
	if strings.Contains(text, "build_tags: embedded_codex") ||
		strings.Contains(text, `-tags "embedded_codex"`) {
		t.Fatal("desktop release still embeds Codex in the main application")
	}

	script, err := os.ReadFile("build-release.ps1")
	if err != nil {
		t.Fatal(err)
	}
	scriptText := string(script)
	for _, marker := range []string{
		"murong-runtime-codex-windows-$Architecture-$codexVersion.tgz",
		"go run ./cmd/runtime-packager",
		`-platform "windows/$Architecture" -trimpath -o $outputName`,
	} {
		if !strings.Contains(scriptText, marker) {
			t.Fatalf("local thin desktop release script is missing %q", marker)
		}
	}
}

func TestMainPushPublishesAndSyncsCuratedReleaseNotes(t *testing.T) {
	workflow, err := os.ReadFile("../.github/workflows/build-all.yml")
	if err != nil {
		t.Fatal(err)
	}
	text := string(workflow)
	for _, marker := range []string{
		"if: github.event_name == 'push' || (github.event_name == 'workflow_dispatch' && inputs.publish_release)",
		"if: github.event_name == 'push' || (github.event_name == 'workflow_dispatch' && inputs.sync_mobile_to_server)",
		"strategy:",
		"fail-fast: false",
		"artifact_key: murongagent-app",
		"artifact_key: murong-terminal-extension",
		"--retry-all-errors",
		"needs: [android_app, terminal_extension, verify, publish]",
		`"downloadUrl": asset["browser_download_url"]`,
		`digest.removeprefix("sha256:")`,
		"Publish GitHub Release Metadata To Backend",
		`notes_path = Path("../release-source/release-notes/latest.md")`,
		`"changelog": changelog`,
		`tag="${INPUT_RELEASE_TAG:-murong-suite-v${suite_version}}"`,
		`assets=("${packages[@]}" release/SHA256SUMS.txt release/release-manifest.json release/RELEASE_NOTES.md)`,
	} {
		if !strings.Contains(text, marker) {
			t.Fatalf("main push release synchronization is missing %q", marker)
		}
	}
	if strings.Contains(text, `"changelog": "内置完整 Termux 工具链`) {
		t.Fatal("terminal extension metadata still uses a hard-coded changelog")
	}
}
