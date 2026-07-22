package desktopbridge

import (
	"bytes"
	"strings"
	"testing"
)

func TestAddColorOSElsaWhitelistAfterThirdPartyMarker(t *testing.T) {
	original := []byte("<?xml version=\"1.0\"?>\n<config>\n    <!--third white app -->\n    <whitePkg name=\"bin.mt.plus\" category=\"001\" />\n</config>\n")
	updated, changed, err := addColorOSElsaWhitelist(original)
	if err != nil {
		t.Fatal(err)
	}
	if !changed {
		t.Fatal("missing Murong entry should be inserted")
	}
	want := "<!--third white app -->\n    " + colorOSElsaEntry + "\n    <whitePkg name=\"bin.mt.plus\""
	if !strings.Contains(string(updated), want) {
		t.Fatalf("entry was inserted at the wrong location:\n%s", updated)
	}
	second, changed, err := addColorOSElsaWhitelist(updated)
	if err != nil || changed || !bytes.Equal(second, updated) {
		t.Fatalf("patch is not idempotent: changed=%t err=%v", changed, err)
	}
}

func TestAddColorOSElsaWhitelistPreservesCRLF(t *testing.T) {
	original := []byte("<config>\r\n\t<!-- third white app -->\r\n</config>\r\n")
	updated, changed, err := addColorOSElsaWhitelist(original)
	if err != nil || !changed {
		t.Fatalf("CRLF patch failed: changed=%t err=%v", changed, err)
	}
	if !bytes.Contains(updated, []byte("-->\r\n\t"+colorOSElsaEntry+"\r\n")) {
		t.Fatalf("CRLF or indentation was not preserved: %q", updated)
	}
}

func TestAddColorOSElsaWhitelistRejectsUnknownLayout(t *testing.T) {
	if _, _, err := addColorOSElsaWhitelist([]byte("<config/>")); err == nil {
		t.Fatal("unknown OEM layout should not be modified")
	}
}

func TestParseColorOSElsaPathsRejectsUnexpectedFindOutput(t *testing.T) {
	valid := colorOSElsaConfigPath + "\n/data/adb/modules/keepalive/system/data/oplus/os/bpm/sys_elsa_config_list.xml\n"
	paths, err := parseColorOSElsaPaths(valid)
	if err != nil || len(paths) != 2 {
		t.Fatalf("valid paths were rejected: %#v, %v", paths, err)
	}
	if _, err := parseColorOSElsaPaths("/data/local/tmp/sys_elsa_config_list.xml"); err == nil {
		t.Fatal("unexpected path should be rejected")
	}
}
