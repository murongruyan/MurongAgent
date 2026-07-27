package main

import (
	"net"
	"net/url"
	"strings"
)

const (
	guiInferenceLocalOnly  = "local_only"
	guiInferenceLocalFirst = "local_first"
	guiInferenceUserAPI    = "user_api"
)

type guiRect struct {
	Left   int `json:"left"`
	Top    int `json:"top"`
	Right  int `json:"right"`
	Bottom int `json:"bottom"`
}

type guiNodeSnapshot struct {
	ID            string  `json:"id"`
	ParentID      string  `json:"parentId,omitempty"`
	Role          string  `json:"role"`
	Text          string  `json:"text,omitempty"`
	Description   string  `json:"contentDescription,omitempty"`
	ResourceID    string  `json:"resourceId,omitempty"`
	ClassName     string  `json:"className,omitempty"`
	Bounds        guiRect `json:"bounds"`
	Clickable     bool    `json:"clickable,omitempty"`
	LongClickable bool    `json:"longClickable,omitempty"`
	Editable      bool    `json:"editable,omitempty"`
	Scrollable    bool    `json:"scrollable,omitempty"`
	Enabled       bool    `json:"enabled"`
	Selected      bool    `json:"selected,omitempty"`
	Password      bool    `json:"password,omitempty"`
	Visible       bool    `json:"visible"`
}

type guiObservation struct {
	Success              bool              `json:"success"`
	Target               string            `json:"target"`
	ObservationID        string            `json:"observationId"`
	Application          string            `json:"application,omitempty"`
	WindowTitle          string            `json:"windowTitle,omitempty"`
	Width                int               `json:"width,omitempty"`
	Height               int               `json:"height,omitempty"`
	Nodes                []guiNodeSnapshot `json:"nodes"`
	Truncated            bool              `json:"truncated,omitempty"`
	SemanticTextRedacted bool              `json:"semanticTextRedacted"`
	Source               string            `json:"source"`
	Error                string            `json:"error,omitempty"`
}

type guiScreenshot struct {
	MimeType string `json:"mimeType"`
	Base64   string `json:"-"`
	Width    int    `json:"width"`
	Height   int    `json:"height"`
	OriginX  int    `json:"-"`
	OriginY  int    `json:"-"`
}

type guiToolResponse struct {
	Success     bool            `json:"success"`
	Target      string          `json:"target"`
	Action      string          `json:"action"`
	Source      string          `json:"source,omitempty"`
	Message     string          `json:"message,omitempty"`
	Observation *guiObservation `json:"observation,omitempty"`
	ModelResult string          `json:"modelResult,omitempty"`
	ImageWidth  int             `json:"imageWidth,omitempty"`
	ImageHeight int             `json:"imageHeight,omitempty"`
	ImageSHA256 string          `json:"imageSha256,omitempty"`
	Error       string          `json:"error,omitempty"`
}

func normalizeGUIInferenceMode(value string) string {
	switch strings.ToLower(strings.TrimSpace(value)) {
	case guiInferenceLocalOnly:
		return guiInferenceLocalOnly
	case guiInferenceUserAPI:
		return guiInferenceUserAPI
	default:
		return guiInferenceLocalFirst
	}
}

func isLocalModelBaseURL(value string) bool {
	raw := strings.TrimSpace(value)
	if raw == "" {
		return false
	}
	if !strings.Contains(raw, "://") {
		raw = "http://" + raw
	}
	parsed, err := url.Parse(raw)
	if err != nil {
		return false
	}
	if parsed.Scheme != "http" && parsed.Scheme != "https" {
		return false
	}
	host := strings.TrimSuffix(strings.ToLower(parsed.Hostname()), ".")
	if host == "localhost" || strings.HasSuffix(host, ".localhost") || strings.HasSuffix(host, ".local") {
		return true
	}
	ip := net.ParseIP(host)
	return ip != nil && (ip.IsLoopback() || ip.IsPrivate() || ip.IsLinkLocalUnicast())
}

func clampInt(value, minimum, maximum int) int {
	if value < minimum {
		return minimum
	}
	if value > maximum {
		return maximum
	}
	return value
}
