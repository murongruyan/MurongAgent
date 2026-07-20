package main

import (
	"net"
	"net/url"
	"strings"
	"testing"
)

func TestExtractWebTextAndTitle(t *testing.T) {
	body := `<html><head><title> Murong &amp; Agent </title><style>hidden</style></head><body><script>ignore()</script><h1>Hello</h1><p>Windows&nbsp;Agent</p></body></html>`
	if title := extractWebTitle(body); title != "Murong & Agent" {
		t.Fatal(title)
	}
	content := extractWebText(body)
	if !strings.Contains(content, "Hello") || !strings.Contains(content, "Windows Agent") || strings.Contains(content, "ignore") || strings.Contains(content, "hidden") {
		t.Fatal(content)
	}
}

func TestParseBingRSS(t *testing.T) {
	data := []byte(`<?xml version="1.0"?><rss><channel><item><title>First</title><link>https://example.com/one</link><description><![CDATA[<b>Snippet one</b>]]></description></item><item><title>Second</title><link>https://example.com/two</link><description>Snippet two</description></item></channel></rss>`)
	entries, err := parseBingRSS(data, 1)
	if err != nil || len(entries) != 1 || entries[0].Title != "First" || entries[0].Snippet != "Snippet one" {
		t.Fatalf("unexpected RSS entries: %#v, %v", entries, err)
	}
}

func TestWebURLRejectsPrivateTargets(t *testing.T) {
	parsed, _ := url.Parse("http://127.0.0.1:8080/private")
	if err := validateWebURL(parsed, false); err == nil {
		t.Fatal("expected loopback URL to be rejected")
	}
	if isPublicWebIP(net.ParseIP("169.254.169.254")) || isPublicWebIP(net.ParseIP("10.0.0.1")) || !isPublicWebIP(net.ParseIP("1.1.1.1")) {
		t.Fatal("unexpected public IP classification")
	}
}
