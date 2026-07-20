package main

import (
	"context"
	"encoding/json"
	"encoding/xml"
	"errors"
	"fmt"
	"html"
	"io"
	"net"
	"net/http"
	"net/url"
	"regexp"
	"runtime"
	"strings"
	"time"
)

var webUserAgent = "Murong-Desktop-Agent/0.2 (" + runtime.GOOS + "; " + runtime.GOARCH + ")"

type webFetchResult struct {
	Type       string `json:"type"`
	URL        string `json:"url"`
	Title      string `json:"title"`
	Excerpt    string `json:"excerpt"`
	Content    string `json:"content"`
	Truncated  bool   `json:"truncated"`
	TotalChars int    `json:"totalChars"`
}

type webSearchEntry struct {
	Title   string `json:"title"`
	URL     string `json:"url"`
	Snippet string `json:"snippet,omitempty"`
}

func newWebHTTPClient(allowPrivate bool) *http.Client {
	dialer := &net.Dialer{Timeout: 10 * time.Second, KeepAlive: 30 * time.Second}
	transport := http.DefaultTransport.(*http.Transport).Clone()
	transport.DialContext = func(ctx context.Context, network, address string) (net.Conn, error) {
		host, port, err := net.SplitHostPort(address)
		if err != nil {
			return nil, err
		}
		if !allowPrivate {
			addresses, err := net.DefaultResolver.LookupIPAddr(ctx, host)
			if err != nil {
				return nil, err
			}
			for _, address := range addresses {
				if !isPublicWebIP(address.IP) {
					return nil, errors.New("网页工具拒绝访问本机、局域网或链路本地地址")
				}
			}
		}
		return dialer.DialContext(ctx, network, net.JoinHostPort(host, port))
	}
	client := &http.Client{Transport: transport, Timeout: 25 * time.Second}
	client.CheckRedirect = func(request *http.Request, via []*http.Request) error {
		if len(via) >= 8 {
			return errors.New("网页重定向次数过多")
		}
		return validateWebURL(request.URL, allowPrivate)
	}
	return client
}

func isPublicWebIP(ip net.IP) bool {
	if ip == nil || ip.IsLoopback() || ip.IsPrivate() || ip.IsUnspecified() || ip.IsMulticast() || ip.IsLinkLocalUnicast() || ip.IsLinkLocalMulticast() {
		return false
	}
	if ipv4 := ip.To4(); ipv4 != nil {
		return ipv4[0] != 0 && !(ipv4[0] == 169 && ipv4[1] == 254)
	}
	return true
}

func validateWebURL(parsed *url.URL, allowPrivate bool) error {
	if parsed == nil || (parsed.Scheme != "http" && parsed.Scheme != "https") || parsed.Hostname() == "" || parsed.User != nil {
		return errors.New("网页 URL 必须是无账户信息的 HTTP 或 HTTPS 地址")
	}
	host := strings.ToLower(parsed.Hostname())
	if !allowPrivate && (host == "localhost" || strings.HasSuffix(host, ".localhost") || strings.HasSuffix(host, ".local")) {
		return errors.New("网页工具拒绝访问本机或局域网主机名")
	}
	if ip := net.ParseIP(host); ip != nil && !allowPrivate && !isPublicWebIP(ip) {
		return errors.New("网页工具拒绝访问本机、局域网或链路本地地址")
	}
	return nil
}

func normalizeWebURL(raw string) (*url.URL, error) {
	raw = strings.TrimSpace(raw)
	if !strings.HasPrefix(strings.ToLower(raw), "http://") && !strings.HasPrefix(strings.ToLower(raw), "https://") {
		raw = "https://" + raw
	}
	parsed, err := url.Parse(raw)
	if err != nil {
		return nil, err
	}
	if err := validateWebURL(parsed, false); err != nil {
		return nil, err
	}
	return parsed, nil
}

func fetchWebPage(ctx context.Context, client *http.Client, parsed *url.URL, maxChars int) (webFetchResult, error) {
	if maxChars < 500 {
		maxChars = 4000
	}
	if maxChars > 12000 {
		maxChars = 12000
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, parsed.String(), nil)
	if err != nil {
		return webFetchResult{}, err
	}
	request.Header.Set("User-Agent", webUserAgent)
	request.Header.Set("Accept", "text/html, text/plain, application/xhtml+xml")
	response, err := client.Do(request)
	if err != nil {
		return webFetchResult{}, err
	}
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return webFetchResult{}, fmt.Errorf("网页抓取失败 HTTP %d", response.StatusCode)
	}
	contentType := strings.ToLower(response.Header.Get("Content-Type"))
	if contentType != "" && !strings.Contains(contentType, "text/") && !strings.Contains(contentType, "html") && !strings.Contains(contentType, "xml") {
		return webFetchResult{}, fmt.Errorf("网页抓取不支持内容类型 %s", contentType)
	}
	data, err := io.ReadAll(io.LimitReader(response.Body, 2*1024*1024+1))
	if err != nil {
		return webFetchResult{}, err
	}
	if len(data) > 2*1024*1024 {
		return webFetchResult{}, errors.New("网页响应超过 2 MiB 上限")
	}
	body := string(data)
	content := extractWebText(body)
	if strings.TrimSpace(content) == "" {
		return webFetchResult{}, errors.New("网页正文为空")
	}
	totalRunes := []rune(content)
	returned := totalRunes
	truncated := len(totalRunes) > maxChars
	if truncated {
		returned = totalRunes[:maxChars]
	}
	text := strings.TrimSpace(string(returned))
	title := extractWebTitle(body)
	if title == "" {
		title = parsed.String()
	}
	return webFetchResult{
		Type: "web_fetch_result", URL: parsed.String(), Title: title,
		Excerpt: truncateRunes(text, 280), Content: text, Truncated: truncated, TotalChars: len(totalRunes),
	}, nil
}

var (
	webTitlePattern    = regexp.MustCompile(`(?is)<title[^>]*>(.*?)</title>`)
	webScriptPattern   = regexp.MustCompile(`(?is)<script[^>]*>.*?</script>`)
	webStylePattern    = regexp.MustCompile(`(?is)<style[^>]*>.*?</style>`)
	webNoScriptPattern = regexp.MustCompile(`(?is)<noscript[^>]*>.*?</noscript>`)
	webBreakPattern    = regexp.MustCompile(`(?i)<br\s*/?>`)
	webBlockEndPattern = regexp.MustCompile(`(?i)</(?:p|div|section|article|li|tr|h[1-6])>`)
	webTagPattern      = regexp.MustCompile(`(?s)<[^>]+>`)
	webMultiBlankLines = regexp.MustCompile(`\n{3,}`)
)

func extractWebTitle(body string) string {
	match := webTitlePattern.FindStringSubmatch(body)
	if len(match) < 2 {
		return ""
	}
	return strings.Join(strings.Fields(html.UnescapeString(match[1])), " ")
}

func extractWebText(body string) string {
	body = webScriptPattern.ReplaceAllString(body, " ")
	body = webStylePattern.ReplaceAllString(body, " ")
	body = webNoScriptPattern.ReplaceAllString(body, " ")
	body = webBreakPattern.ReplaceAllString(body, "\n")
	body = webBlockEndPattern.ReplaceAllString(body, "\n")
	body = webTagPattern.ReplaceAllString(body, " ")
	body = html.UnescapeString(body)
	lines := []string{}
	for _, line := range strings.Split(strings.ReplaceAll(body, "\r", ""), "\n") {
		line = strings.Join(strings.Fields(line), " ")
		if line != "" {
			lines = append(lines, line)
		}
	}
	return strings.TrimSpace(webMultiBlankLines.ReplaceAllString(strings.Join(lines, "\n"), "\n\n"))
}

func searchBingRSS(ctx context.Context, client *http.Client, query string, maxResults int) ([]webSearchEntry, error) {
	query = strings.TrimSpace(query)
	if query == "" || len([]rune(query)) > 500 {
		return nil, errors.New("搜索关键词为空或超过 500 字符")
	}
	if maxResults < 1 {
		maxResults = 5
	}
	if maxResults > 10 {
		maxResults = 10
	}
	endpoint := &url.URL{Scheme: "https", Host: "www.bing.com", Path: "/search"}
	values := endpoint.Query()
	values.Set("format", "rss")
	values.Set("q", query)
	endpoint.RawQuery = values.Encode()
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint.String(), nil)
	if err != nil {
		return nil, err
	}
	request.Header.Set("User-Agent", webUserAgent)
	request.Header.Set("Accept", "application/rss+xml, application/xml, text/xml")
	response, err := client.Do(request)
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return nil, fmt.Errorf("Bing 搜索失败 HTTP %d", response.StatusCode)
	}
	data, err := io.ReadAll(io.LimitReader(response.Body, 1024*1024))
	if err != nil {
		return nil, err
	}
	return parseBingRSS(data, maxResults)
}

func parseBingRSS(data []byte, maxResults int) ([]webSearchEntry, error) {
	var feed struct {
		Channel struct {
			Items []struct {
				Title       string `xml:"title"`
				Link        string `xml:"link"`
				Description string `xml:"description"`
			} `xml:"item"`
		} `xml:"channel"`
	}
	if err := xml.Unmarshal(data, &feed); err != nil {
		return nil, fmt.Errorf("无法解析 Bing RSS：%w", err)
	}
	result := []webSearchEntry{}
	for _, item := range feed.Channel.Items {
		title := strings.TrimSpace(html.UnescapeString(item.Title))
		link := strings.TrimSpace(item.Link)
		if title == "" || link == "" {
			continue
		}
		result = append(result, webSearchEntry{Title: title, URL: link, Snippet: truncateRunes(extractWebText(item.Description), 300)})
		if len(result) >= maxResults {
			break
		}
	}
	if len(result) == 0 {
		return nil, errors.New("Bing RSS 未返回可用结果")
	}
	return result, nil
}

func (app *DesktopAgentApp) executeWebTool(
	ctx context.Context, sessionID string, config desktopConfig, call modelToolCall, raw map[string]json.RawMessage,
) (string, error) {
	client := newWebHTTPClient(false)
	switch call.Function.Name {
	case "web_fetch":
		rawURL := rawString(raw, "url", "")
		parsed, err := normalizeWebURL(rawURL)
		if err != nil {
			return "", err
		}
		if err := app.authorizeTool(ctx, config, ApprovalRequest{
			ID: newID("approval"), SessionID: sessionID, ToolName: call.Function.Name,
			Summary: "抓取网页", Detail: parsed.String(), Arguments: call.Function.Arguments, Risk: "low",
		}); err != nil {
			return "", err
		}
		result, err := fetchWebPage(ctx, client, parsed, rawInt(raw, "maxChars", 4000))
		return marshalToolResult(map[string]any{"success": err == nil, "result": result}), err
	case "web_search":
		query := rawString(raw, "query", "")
		if err := app.authorizeTool(ctx, config, ApprovalRequest{
			ID: newID("approval"), SessionID: sessionID, ToolName: call.Function.Name,
			Summary: "搜索网页", Detail: truncateRunes(query, 500), Arguments: call.Function.Arguments, Risk: "low",
		}); err != nil {
			return "", err
		}
		entries, err := searchBingRSS(ctx, client, query, rawInt(raw, "maxResults", 5))
		return marshalToolResult(map[string]any{"success": err == nil, "source": "Bing RSS", "results": entries}), err
	default:
		return "", fmt.Errorf("未知网页工具：%s", call.Function.Name)
	}
}
