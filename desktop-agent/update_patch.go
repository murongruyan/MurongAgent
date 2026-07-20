package main

import (
	"errors"
	"fmt"
	"strconv"
	"strings"
)

type updatePatchResult struct {
	Content   string
	HunkCount int
}

type updatePatchHunk struct {
	OldLines    []string
	NewLines    []string
	OldLineHint int
}

func applySingleFileUpdatePatch(expectedPath, currentContent, patchText string) (updatePatchResult, error) {
	if strings.TrimSpace(patchText) == "" {
		return updatePatchResult{}, errors.New("patch_text 不能为空")
	}
	normalized := strings.ReplaceAll(strings.ReplaceAll(patchText, "\r\n", "\n"), "\r", "\n")
	lines := strings.Split(normalized, "\n")
	meaningful := make([]string, 0, len(lines))
	for _, line := range lines {
		if strings.TrimSpace(line) != "" {
			meaningful = append(meaningful, line)
		}
	}
	if len(meaningful) < 2 || meaningful[0] != "*** Begin Patch" || meaningful[len(meaningful)-1] != "*** End Patch" {
		return updatePatchResult{}, errors.New("补丁必须以 *** Begin Patch 开始并以 *** End Patch 结束")
	}
	type section struct {
		path string
		body []string
	}
	sections := []section{}
	for index := 0; index < len(lines); {
		line := lines[index]
		switch {
		case line == "*** Begin Patch" || strings.TrimSpace(line) == "":
			index++
		case line == "*** End Patch":
			index = len(lines)
		case strings.HasPrefix(line, "*** Add File:") || strings.HasPrefix(line, "*** Delete File:"):
			return updatePatchResult{}, errors.New("code_edit apply_patch 只支持 *** Update File")
		case strings.HasPrefix(line, "*** Update File:"):
			path := strings.TrimSpace(strings.TrimPrefix(line, "*** Update File:"))
			if path == "" {
				return updatePatchResult{}, errors.New("Update File 路径不能为空")
			}
			index++
			body := []string{}
			for index < len(lines) && !strings.HasPrefix(lines[index], "*** ") {
				body = append(body, lines[index])
				index++
			}
			sections = append(sections, section{path: path, body: body})
		default:
			return updatePatchResult{}, fmt.Errorf("无法识别的补丁行：%s", line)
		}
	}
	if len(sections) != 1 {
		return updatePatchResult{}, errors.New("code_edit apply_patch 必须且只能包含一个 Update File 区块")
	}
	if !patchPathsMatch(expectedPath, sections[0].path) {
		return updatePatchResult{}, fmt.Errorf("补丁路径 %q 与请求路径 %q 不一致", sections[0].path, expectedPath)
	}
	hunks, err := parseUpdatePatchHunks(sections[0].body)
	if err != nil {
		return updatePatchResult{}, err
	}
	if len(hunks) == 0 {
		return updatePatchResult{}, errors.New("补丁没有 @@ hunk")
	}
	lineEnding := "\n"
	if strings.Contains(currentContent, "\r\n") {
		lineEnding = "\r\n"
	}
	hadTrailing := strings.HasSuffix(currentContent, "\n") || strings.HasSuffix(currentContent, "\r")
	currentNormalized := strings.ReplaceAll(strings.ReplaceAll(currentContent, "\r\n", "\n"), "\r", "\n")
	currentNormalized = strings.TrimSuffix(currentNormalized, "\n")
	currentLines := []string{}
	if currentNormalized != "" {
		currentLines = strings.Split(currentNormalized, "\n")
	}
	for index, hunk := range hunks {
		start, err := findUpdateHunkStart(currentLines, hunk)
		if err != nil {
			return updatePatchResult{}, fmt.Errorf("hunk %d：%w", index+1, err)
		}
		next := make([]string, 0, len(currentLines)-len(hunk.OldLines)+len(hunk.NewLines))
		next = append(next, currentLines[:start]...)
		next = append(next, hunk.NewLines...)
		next = append(next, currentLines[start+len(hunk.OldLines):]...)
		currentLines = next
	}
	result := strings.Join(currentLines, "\n")
	if hadTrailing {
		result += "\n"
	}
	if lineEnding == "\r\n" {
		result = strings.ReplaceAll(result, "\n", "\r\n")
	}
	if result == currentContent {
		return updatePatchResult{}, errors.New("补丁没有改变文件")
	}
	return updatePatchResult{Content: result, HunkCount: len(hunks)}, nil
}

func parseUpdatePatchHunks(body []string) ([]updatePatchHunk, error) {
	result := []updatePatchHunk{}
	for index := 0; index < len(body); {
		for index < len(body) && strings.TrimSpace(body[index]) == "" {
			index++
		}
		if index >= len(body) {
			break
		}
		header := body[index]
		if !strings.HasPrefix(header, "@@") {
			return nil, fmt.Errorf("应为 @@ hunk 头，实际为：%s", header)
		}
		hint := 0
		fields := strings.Fields(header)
		for _, field := range fields {
			if strings.HasPrefix(field, "-") {
				value := strings.TrimPrefix(strings.SplitN(field, ",", 2)[0], "-")
				hint, _ = strconv.Atoi(value)
				break
			}
		}
		index++
		hunk := updatePatchHunk{OldLineHint: hint}
		for index < len(body) && !strings.HasPrefix(body[index], "@@") {
			line := body[index]
			switch {
			case line == "\\ No newline at end of file":
			case strings.HasPrefix(line, " "):
				hunk.OldLines = append(hunk.OldLines, strings.TrimPrefix(line, " "))
				hunk.NewLines = append(hunk.NewLines, strings.TrimPrefix(line, " "))
			case strings.HasPrefix(line, "-"):
				hunk.OldLines = append(hunk.OldLines, strings.TrimPrefix(line, "-"))
			case strings.HasPrefix(line, "+"):
				hunk.NewLines = append(hunk.NewLines, strings.TrimPrefix(line, "+"))
			case line == "":
				hunk.OldLines = append(hunk.OldLines, "")
				hunk.NewLines = append(hunk.NewLines, "")
			default:
				return nil, fmt.Errorf("无效的 hunk 行：%s", line)
			}
			index++
		}
		if len(hunk.OldLines) == 0 {
			return nil, errors.New("纯插入 hunk 位置不明确，请至少包含一行上下文")
		}
		result = append(result, hunk)
	}
	return result, nil
}

func findUpdateHunkStart(lines []string, hunk updatePatchHunk) (int, error) {
	if len(hunk.OldLines) > len(lines) {
		return 0, errors.New("上下文未匹配当前文件")
	}
	matches := []int{}
	for start := 0; start <= len(lines)-len(hunk.OldLines); start++ {
		matched := true
		for offset := range hunk.OldLines {
			if lines[start+offset] != hunk.OldLines[offset] {
				matched = false
				break
			}
		}
		if matched {
			matches = append(matches, start)
		}
	}
	if len(matches) == 0 {
		return 0, errors.New("上下文未匹配当前文件")
	}
	if len(matches) == 1 {
		return matches[0], nil
	}
	if hunk.OldLineHint <= 0 {
		return 0, fmt.Errorf("上下文匹配 %d 处，请增加上下文或行号", len(matches))
	}
	target := hunk.OldLineHint - 1
	best, bestDistance, tied := matches[0], absoluteInt(matches[0]-target), false
	for _, match := range matches[1:] {
		distance := absoluteInt(match - target)
		if distance < bestDistance {
			best, bestDistance, tied = match, distance, false
		} else if distance == bestDistance {
			tied = true
		}
	}
	if tied {
		return 0, fmt.Errorf("行 %d 附近的上下文仍不唯一", hunk.OldLineHint)
	}
	return best, nil
}

func patchPathsMatch(expected, patched string) bool {
	normalize := func(value string) string {
		value = strings.ToLower(strings.TrimSpace(strings.ReplaceAll(value, "\\", "/")))
		value = strings.TrimPrefix(value, "./")
		return strings.TrimSuffix(value, "/")
	}
	expected, patched = normalize(expected), normalize(patched)
	return expected == patched || strings.HasSuffix(expected, "/"+patched)
}

func absoluteInt(value int) int {
	if value < 0 {
		return -value
	}
	return value
}
