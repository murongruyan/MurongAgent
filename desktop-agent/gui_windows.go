//go:build windows

package main

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"os/exec"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"unicode/utf16"
)

type windowsGUINodeRef struct {
	Path       string
	ResourceID string
	ClassName  string
	Bounds     guiRect
}

type windowsGUIRawNode struct {
	Path       string  `json:"path"`
	ParentPath string  `json:"parentPath"`
	Role       string  `json:"role"`
	Text       string  `json:"text"`
	ResourceID string  `json:"resourceId"`
	ClassName  string  `json:"className"`
	Bounds     guiRect `json:"bounds"`
	Clickable  bool    `json:"clickable"`
	Editable   bool    `json:"editable"`
	Scrollable bool    `json:"scrollable"`
	Enabled    bool    `json:"enabled"`
	Selected   bool    `json:"selected"`
	Password   bool    `json:"password"`
	Visible    bool    `json:"visible"`
}

type windowsGUIRawObservation struct {
	Success     bool                `json:"success"`
	Application string              `json:"application"`
	WindowTitle string              `json:"windowTitle"`
	Width       int                 `json:"width"`
	Height      int                 `json:"height"`
	Nodes       []windowsGUIRawNode `json:"nodes"`
	Truncated   bool                `json:"truncated"`
	Error       string              `json:"error"`
}

var windowsGUIState = struct {
	sync.RWMutex
	nodes map[string]windowsGUINodeRef
}{nodes: map[string]windowsGUINodeRef{}}

var windowsGUIObservationSequence atomic.Uint64

func guiPlatformSupported() bool {
	return true
}

func guiPlatformObserve(ctx context.Context, maxNodes int, includeText bool) (guiObservation, error) {
	maxNodes = clampInt(maxNodes, 1, 500)
	script := fmt.Sprintf(windowsUIAutomationPrelude+`
$IncludeText = %s
$MaxNodes = %d
$handle = [MurongWindow]::GetForegroundWindow()
if ($handle -eq [IntPtr]::Zero) { throw "没有前台窗口" }
$root = [System.Windows.Automation.AutomationElement]::FromHandle($handle)
if ($null -eq $root) { throw "无法读取前台窗口 UI Automation 树" }
$walker = [System.Windows.Automation.TreeWalker]::RawViewWalker
$queue = [System.Collections.Generic.Queue[object]]::new()
$queue.Enqueue([pscustomobject]@{ Element = $root; Path = "root"; ParentPath = "" })
$nodes = [System.Collections.Generic.List[object]]::new()
$visited = 0
$truncated = $false
while ($queue.Count -gt 0) {
    if ($nodes.Count -ge $MaxNodes -or $visited -ge 5000) { $truncated = $true; break }
    $item = $queue.Dequeue()
    $element = $item.Element
    $visited++
    try {
        $current = $element.Current
        $bounds = $current.BoundingRectangle
        $password = [bool]$current.IsPassword
        $name = if ($IncludeText -and -not $password) { [string]$current.Name } elseif ($IncludeText -and $password) { "[REDACTED]" } else { "" }
        $control = [string]$current.ControlType.ProgrammaticName
        $role = $control.Replace("ControlType.", "").ToLowerInvariant()
        $clickable = $role -in @("button","checkbox","combobox","hyperlink","listitem","menuitem","radio","tabitem","treeitem")
        $editable = $role -eq "edit"
        $scrollable = $role -in @("list","datagrid","document","pane","tree")
        $nodes.Add([pscustomobject]@{
            path = [string]$item.Path
            parentPath = [string]$item.ParentPath
            role = $role
            text = $name
            resourceId = [string]$current.AutomationId
            className = [string]$current.ClassName
            bounds = @{ left = [int]$bounds.Left; top = [int]$bounds.Top; right = [int]$bounds.Right; bottom = [int]$bounds.Bottom }
            clickable = $clickable
            editable = $editable
            scrollable = $scrollable
            enabled = [bool]$current.IsEnabled
            selected = $false
            password = $password
            visible = -not [bool]$current.IsOffscreen
        })
    } catch {}
    $child = $walker.GetFirstChild($element)
    $index = 0
    while ($null -ne $child) {
        $path = if ($item.Path -eq "root") { "$index" } else { "$($item.Path).$index" }
        $queue.Enqueue([pscustomobject]@{ Element = $child; Path = $path; ParentPath = [string]$item.Path })
        $child = $walker.GetNextSibling($child)
        $index++
    }
}
$processName = ""
try { $processName = (Get-Process -Id $root.Current.ProcessId -ErrorAction Stop).ProcessName } catch {}
$rect = New-Object MurongWindow+RECT
[void][MurongWindow]::GetWindowRect($handle, [ref]$rect)
[pscustomobject]@{
    success = $true
    application = $processName
    windowTitle = [string]$root.Current.Name
    width = [Math]::Max(0, $rect.Right - $rect.Left)
    height = [Math]::Max(0, $rect.Bottom - $rect.Top)
    nodes = $nodes
    truncated = $truncated
    error = ""
} | ConvertTo-Json -Depth 8 -Compress
`, powershellBool(includeText), maxNodes)
	output, err := runWindowsPowerShell(ctx, script)
	if err != nil {
		return guiObservation{}, err
	}
	var raw windowsGUIRawObservation
	if err := json.Unmarshal(output, &raw); err != nil {
		return guiObservation{}, fmt.Errorf("无法解析 Windows UI Automation 结果：%w", err)
	}
	if !includeText {
		raw.WindowTitle = ""
	}
	generation := windowsGUIObservationSequence.Add(1)
	observationID := fmt.Sprintf("windows:%d", generation)
	pathIDs := make(map[string]string, len(raw.Nodes))
	for index, node := range raw.Nodes {
		pathIDs[node.Path] = fmt.Sprintf("%s:%d", observationID, index)
	}
	nodes := make([]guiNodeSnapshot, 0, len(raw.Nodes))
	cache := make(map[string]windowsGUINodeRef, len(raw.Nodes))
	for _, node := range raw.Nodes {
		id := pathIDs[node.Path]
		nodes = append(nodes, guiNodeSnapshot{
			ID: id, ParentID: pathIDs[node.ParentPath], Role: node.Role, Text: node.Text,
			ResourceID: node.ResourceID, ClassName: node.ClassName, Bounds: node.Bounds,
			Clickable: node.Clickable, LongClickable: node.Clickable, Editable: node.Editable, Scrollable: node.Scrollable,
			Enabled: node.Enabled, Selected: node.Selected, Password: node.Password, Visible: node.Visible,
		})
		cache[id] = windowsGUINodeRef{
			Path: node.Path, ResourceID: node.ResourceID, ClassName: node.ClassName, Bounds: node.Bounds,
		}
	}
	windowsGUIState.Lock()
	windowsGUIState.nodes = cache
	windowsGUIState.Unlock()
	return guiObservation{
		Success: raw.Success, Target: "windows", ObservationID: observationID,
		Application: raw.Application, WindowTitle: raw.WindowTitle, Width: raw.Width, Height: raw.Height,
		Nodes: nodes, Truncated: raw.Truncated, SemanticTextRedacted: !includeText,
		Source: "windows_uiautomation", Error: raw.Error,
	}, nil
}

func guiPlatformAction(ctx context.Context, action string, raw map[string]json.RawMessage) (string, error) {
	nodeID := rawJSONString(raw, "nodeId")
	var ref windowsGUINodeRef
	if nodeID != "" {
		windowsGUIState.RLock()
		var ok bool
		ref, ok = windowsGUIState.nodes[nodeID]
		windowsGUIState.RUnlock()
		if !ok {
			return "", errors.New("nodeId 已过期；请重新 observe")
		}
	}
	x, y := rawJSONInt(raw, "x", 0), rawJSONInt(raw, "y", 0)
	if nodeID != "" && x == 0 && y == 0 {
		x = (ref.Bounds.Left + ref.Bounds.Right) / 2
		y = (ref.Bounds.Top + ref.Bounds.Bottom) / 2
	}
	textValue := rawJSONStringUntrimmed(raw, "text")
	duration := clampInt(rawJSONInt(raw, "durationMs", 700), 50, 5000)
	pathLiteral := powershellSingleQuoted(ref.Path)
	textBase64 := base64.StdEncoding.EncodeToString([]byte(textValue))
	keyExpression, keyErr := windowsSendKeysExpression(rawJSONString(raw, "key"))
	if action == "key" && keyErr != nil {
		return "", keyErr
	}
	script := windowsUIAutomationPrelude + windowsNativeInputPrelude + fmt.Sprintf(`
$Action = %s
$Path = %s
$ExpectedAutomationId = %s
$ExpectedClassName = %s
$X = %d
$Y = %d
$Duration = %d
$Text = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String(%s))
$KeyExpression = %s
function Resolve-Element([string]$Path) {
    $handle = [MurongWindow]::GetForegroundWindow()
    $element = [System.Windows.Automation.AutomationElement]::FromHandle($handle)
    if ($null -eq $element -or $Path -eq "" -or $Path -eq "root") { return $element }
    $walker = [System.Windows.Automation.TreeWalker]::RawViewWalker
    foreach ($segment in $Path.Split(".")) {
        $targetIndex = [int]$segment
        $child = $walker.GetFirstChild($element)
        for ($i = 0; $i -lt $targetIndex -and $null -ne $child; $i++) { $child = $walker.GetNextSibling($child) }
        if ($null -eq $child) { return $null }
        $element = $child
    }
    return $element
}
$element = Resolve-Element $Path
if ($Path -ne "" -and $null -eq $element) { throw "nodeId 已过期；请重新 observe" }
if ($null -ne $element -and $ExpectedAutomationId -ne "" -and [string]$element.Current.AutomationId -ne $ExpectedAutomationId) {
    throw "nodeId 指向的 AutomationId 已变化；请重新 observe"
}
if ($null -ne $element -and $ExpectedClassName -ne "" -and [string]$element.Current.ClassName -ne $ExpectedClassName) {
    throw "nodeId 指向的 ClassName 已变化；请重新 observe"
}
$success = $false
$source = "windows_input"
switch ($Action) {
    "click" {
        if ($null -ne $element) {
            $pattern = $null
            if ($element.TryGetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern, [ref]$pattern)) {
                ([System.Windows.Automation.InvokePattern]$pattern).Invoke(); $success = $true; $source = "windows_uiautomation"
            } elseif ($element.TryGetCurrentPattern([System.Windows.Automation.TogglePattern]::Pattern, [ref]$pattern)) {
                ([System.Windows.Automation.TogglePattern]$pattern).Toggle(); $success = $true; $source = "windows_uiautomation"
            } elseif ($element.TryGetCurrentPattern([System.Windows.Automation.SelectionItemPattern]::Pattern, [ref]$pattern)) {
                ([System.Windows.Automation.SelectionItemPattern]$pattern).Select(); $success = $true; $source = "windows_uiautomation"
            } elseif ($element.TryGetCurrentPattern([System.Windows.Automation.ExpandCollapsePattern]::Pattern, [ref]$pattern)) {
                ([System.Windows.Automation.ExpandCollapsePattern]$pattern).Expand(); $success = $true; $source = "windows_uiautomation"
            }
        }
        if (-not $success) { [MurongNativeInput]::Click($X, $Y); $success = $true }
    }
    "long_click" { [MurongNativeInput]::LongClick($X, $Y, $Duration); $success = $true }
    "input" {
        if ($null -ne $element) {
            $pattern = $null
            if ($element.TryGetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern, [ref]$pattern)) {
                ([System.Windows.Automation.ValuePattern]$pattern).SetValue($Text); $success = $true; $source = "windows_uiautomation"
            } else { try { $element.SetFocus() } catch {} }
        }
        if (-not $success) {
            if ($X -ne 0 -or $Y -ne 0) { [MurongNativeInput]::Click($X, $Y) }
            [MurongNativeInput]::SendUnicode($Text); $success = $true
        }
    }
    "tap" { [MurongNativeInput]::Click($X, $Y); $success = $true }
    "swipe" {
        [MurongNativeInput]::Drag(%d, %d, %d, %d, $Duration); $success = $true
    }
    "scroll" {
        $direction = %s
        if ($null -ne $element) {
            $pattern = $null
            if ($element.TryGetCurrentPattern([System.Windows.Automation.ScrollPattern]::Pattern, [ref]$pattern)) {
                $amount = if ($direction -in @("up","backward","left")) {
                    [System.Windows.Automation.ScrollAmount]::SmallDecrement
                } else {
                    [System.Windows.Automation.ScrollAmount]::SmallIncrement
                }
                if ($direction -in @("left","right")) {
                    ([System.Windows.Automation.ScrollPattern]$pattern).Scroll(
                        $amount,
                        [System.Windows.Automation.ScrollAmount]::NoAmount
                    )
                } else {
                    ([System.Windows.Automation.ScrollPattern]$pattern).Scroll(
                        [System.Windows.Automation.ScrollAmount]::NoAmount,
                        $amount
                    )
                }
                $success = $true; $source = "windows_uiautomation"
            }
        }
        if (-not $success) {
            Add-Type -AssemblyName System.Windows.Forms
            if ($X -eq 0 -and $Y -eq 0) { $X = [System.Windows.Forms.Cursor]::Position.X; $Y = [System.Windows.Forms.Cursor]::Position.Y }
            $delta = if ($direction -in @("up","backward","left")) { 360 } else { -360 }
            [MurongNativeInput]::Wheel($X, $Y, $delta); $success = $true
        }
    }
    "key" {
        Add-Type -AssemblyName System.Windows.Forms
        [System.Windows.Forms.SendKeys]::SendWait($KeyExpression); $success = $true
    }
    "launch" {
        $application = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String(%s))
        if ([string]::IsNullOrWhiteSpace($application)) { throw "launch 缺少 application" }
        Start-Process -FilePath $application
        $success = $true; $source = "windows_shell"
    }
    default { throw "不支持的 Windows GUI action: $Action" }
}
[pscustomobject]@{ success = $success; action = $Action; source = $source } | ConvertTo-Json -Compress
`,
		powershellSingleQuoted(action), pathLiteral,
		powershellSingleQuoted(ref.ResourceID), powershellSingleQuoted(ref.ClassName),
		x, y, duration,
		powershellSingleQuoted(textBase64), powershellSingleQuoted(keyExpression),
		rawJSONInt(raw, "startX", 0), rawJSONInt(raw, "startY", 0),
		rawJSONInt(raw, "endX", 0), rawJSONInt(raw, "endY", 0),
		powershellSingleQuoted(strings.ToLower(rawJSONString(raw, "direction"))),
		powershellSingleQuoted(base64.StdEncoding.EncodeToString([]byte(rawJSONString(raw, "application")))),
	)
	output, err := runWindowsPowerShell(ctx, script)
	return strings.TrimSpace(string(output)), err
}

func guiPlatformScreenshot(ctx context.Context, raw map[string]json.RawMessage) (guiScreenshot, error) {
	left := rawJSONInt(raw, "cropLeft", -1)
	top := rawJSONInt(raw, "cropTop", -1)
	right := rawJSONInt(raw, "cropRight", -1)
	bottom := rawJSONInt(raw, "cropBottom", -1)
	script := fmt.Sprintf(`
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Windows.Forms
$screen = [System.Windows.Forms.SystemInformation]::VirtualScreen
$left = %d; $top = %d; $right = %d; $bottom = %d
if ($left -lt 0) { $left = 0 }
if ($top -lt 0) { $top = 0 }
if ($right -lt 1) { $right = $screen.Width }
if ($bottom -lt 1) { $bottom = $screen.Height }
$left = [Math]::Min($left, $screen.Width - 1)
$top = [Math]::Min($top, $screen.Height - 1)
$right = [Math]::Max($left + 1, [Math]::Min($right, $screen.Width))
$bottom = [Math]::Max($top + 1, [Math]::Min($bottom, $screen.Height))
$width = $right - $left
$height = $bottom - $top
$bitmap = New-Object System.Drawing.Bitmap($width, $height, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$graphics = [System.Drawing.Graphics]::FromImage($bitmap)
$stream = New-Object System.IO.MemoryStream
try {
    $graphics.CopyFromScreen($screen.Left + $left, $screen.Top + $top, 0, 0, (New-Object System.Drawing.Size($width, $height)))
    $bitmap.Save($stream, [System.Drawing.Imaging.ImageFormat]::Png)
    [pscustomobject]@{
        mimeType = "image/png"
        base64 = [Convert]::ToBase64String($stream.ToArray())
        width = $width
        height = $height
        originX = $screen.Left + $left
        originY = $screen.Top + $top
    } | ConvertTo-Json -Compress
} finally {
    $stream.Dispose(); $graphics.Dispose(); $bitmap.Dispose()
}
`, left, top, right, bottom)
	output, err := runWindowsPowerShell(ctx, script)
	if err != nil {
		return guiScreenshot{}, err
	}
	var result struct {
		MimeType string `json:"mimeType"`
		Base64   string `json:"base64"`
		Width    int    `json:"width"`
		Height   int    `json:"height"`
		OriginX  int    `json:"originX"`
		OriginY  int    `json:"originY"`
	}
	if err := json.Unmarshal(output, &result); err != nil {
		return guiScreenshot{}, fmt.Errorf("无法解析 Windows 截图结果：%w", err)
	}
	if result.Base64 == "" || len(result.Base64) > 48*1024*1024 {
		return guiScreenshot{}, errors.New("Windows 截图为空或超过内存安全限制")
	}
	return guiScreenshot{
		MimeType: result.MimeType, Base64: result.Base64,
		Width: result.Width, Height: result.Height, OriginX: result.OriginX, OriginY: result.OriginY,
	}, nil
}

func runWindowsPowerShell(ctx context.Context, script string) ([]byte, error) {
	encoded := base64.StdEncoding.EncodeToString(utf16LE(script))
	command := exec.CommandContext(ctx, "powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded)
	output, err := command.CombinedOutput()
	if err != nil {
		return nil, fmt.Errorf("Windows GUI 自动化失败：%s", strings.TrimSpace(string(output)))
	}
	return output, nil
}

func utf16LE(value string) []byte {
	words := utf16.Encode([]rune(value))
	result := make([]byte, len(words)*2)
	for index, word := range words {
		result[index*2] = byte(word)
		result[index*2+1] = byte(word >> 8)
	}
	return result
}

func powershellBool(value bool) string {
	if value {
		return "$true"
	}
	return "$false"
}

func powershellSingleQuoted(value string) string {
	return "'" + strings.ReplaceAll(value, "'", "''") + "'"
}

func windowsSendKeysExpression(value string) (string, error) {
	parts := strings.Split(strings.ToLower(strings.TrimSpace(value)), "+")
	if len(parts) == 0 || parts[0] == "" {
		return "", errors.New("key 缺少按键")
	}
	prefix := ""
	key := parts[len(parts)-1]
	for _, modifier := range parts[:len(parts)-1] {
		switch strings.TrimSpace(modifier) {
		case "ctrl", "control":
			prefix += "^"
		case "alt":
			prefix += "%"
		case "shift":
			prefix += "+"
		default:
			return "", fmt.Errorf("不支持的组合键修饰符 %q", modifier)
		}
	}
	names := map[string]string{
		"enter": "{ENTER}", "return": "{ENTER}", "escape": "{ESC}", "esc": "{ESC}",
		"tab": "{TAB}", "backspace": "{BACKSPACE}", "delete": "{DELETE}", "home": "{HOME}",
		"end": "{END}", "pageup": "{PGUP}", "pagedown": "{PGDN}", "up": "{UP}",
		"down": "{DOWN}", "left": "{LEFT}", "right": "{RIGHT}", "space": " ",
	}
	if mapped := names[key]; mapped != "" {
		return prefix + mapped, nil
	}
	if len(key) == 1 && strings.Contains("abcdefghijklmnopqrstuvwxyz0123456789", key) {
		return prefix + key, nil
	}
	if strings.HasPrefix(key, "f") {
		number, err := strconv.Atoi(strings.TrimPrefix(key, "f"))
		if err == nil && number >= 1 && number <= 12 {
			return prefix + "{F" + strconv.Itoa(number) + "}", nil
		}
	}
	return "", fmt.Errorf("不支持的按键 %q", key)
}

const windowsUIAutomationPrelude = `
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes
if (-not ("MurongWindow" -as [type])) {
Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class MurongWindow {
    [StructLayout(LayoutKind.Sequential)]
    public struct RECT { public int Left; public int Top; public int Right; public int Bottom; }
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, ref RECT rect);
}
"@
}
`

const windowsNativeInputPrelude = `
if (-not ("MurongNativeInput" -as [type])) {
Add-Type @"
using System;
using System.Runtime.InteropServices;
using System.Threading;
public static class MurongNativeInput {
    const uint MOUSEEVENTF_LEFTDOWN = 0x0002, MOUSEEVENTF_LEFTUP = 0x0004, MOUSEEVENTF_WHEEL = 0x0800;
    const uint INPUT_KEYBOARD = 1, KEYEVENTF_UNICODE = 0x0004, KEYEVENTF_KEYUP = 0x0002;
    [StructLayout(LayoutKind.Sequential)] struct INPUT { public uint type; public InputUnion U; }
    [StructLayout(LayoutKind.Explicit)] struct InputUnion { [FieldOffset(0)] public KEYBDINPUT ki; }
    [StructLayout(LayoutKind.Sequential)] struct KEYBDINPUT { public ushort wVk; public ushort wScan; public uint dwFlags; public uint time; public UIntPtr dwExtraInfo; }
    [DllImport("user32.dll")] static extern bool SetCursorPos(int x, int y);
    [DllImport("user32.dll")] static extern void mouse_event(uint flags, uint dx, uint dy, int data, UIntPtr extra);
    [DllImport("user32.dll")] static extern uint SendInput(uint count, INPUT[] inputs, int size);
    public static void Click(int x, int y) { SetCursorPos(x,y); mouse_event(MOUSEEVENTF_LEFTDOWN,0,0,0,UIntPtr.Zero); mouse_event(MOUSEEVENTF_LEFTUP,0,0,0,UIntPtr.Zero); }
    public static void LongClick(int x, int y, int duration) { SetCursorPos(x,y); mouse_event(MOUSEEVENTF_LEFTDOWN,0,0,0,UIntPtr.Zero); Thread.Sleep(duration); mouse_event(MOUSEEVENTF_LEFTUP,0,0,0,UIntPtr.Zero); }
    public static void Wheel(int x, int y, int delta) { SetCursorPos(x,y); mouse_event(MOUSEEVENTF_WHEEL,0,0,delta,UIntPtr.Zero); }
    public static void Drag(int x1, int y1, int x2, int y2, int duration) {
        SetCursorPos(x1,y1); mouse_event(MOUSEEVENTF_LEFTDOWN,0,0,0,UIntPtr.Zero);
        int steps = Math.Max(2, Math.Min(60, duration / 16));
        for(int i=1;i<=steps;i++){ SetCursorPos(x1+(x2-x1)*i/steps,y1+(y2-y1)*i/steps); Thread.Sleep(Math.Max(1,duration/steps)); }
        mouse_event(MOUSEEVENTF_LEFTUP,0,0,0,UIntPtr.Zero);
    }
    public static void SendUnicode(string text) {
        foreach(char ch in text) {
            var inputs = new INPUT[2];
            inputs[0].type=INPUT_KEYBOARD; inputs[0].U.ki.wScan=ch; inputs[0].U.ki.dwFlags=KEYEVENTF_UNICODE;
            inputs[1].type=INPUT_KEYBOARD; inputs[1].U.ki.wScan=ch; inputs[1].U.ki.dwFlags=KEYEVENTF_UNICODE|KEYEVENTF_KEYUP;
            SendInput(2, inputs, Marshal.SizeOf(typeof(INPUT)));
        }
    }
}
"@
}
`
