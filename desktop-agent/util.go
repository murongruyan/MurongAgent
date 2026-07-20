package main

import (
	"crypto/rand"
	"encoding/csv"
	"encoding/hex"
	"os/exec"
	"strconv"
	"strings"
)

func newID(prefix string) string {
	buffer := make([]byte, 12)
	if _, err := rand.Read(buffer); err != nil {
		panic(err)
	}
	return prefix + "-" + hex.EncodeToString(buffer)
}

func tasklistProcessID(executableName string) int {
	command := exec.Command("tasklist.exe", "/FI", "IMAGENAME eq "+executableName, "/FO", "CSV", "/NH")
	prepareHiddenCommand(command)
	output, err := command.Output()
	if err != nil {
		return 0
	}
	rows, err := csv.NewReader(strings.NewReader(strings.TrimSpace(string(output)))).ReadAll()
	if err != nil || len(rows) == 0 || len(rows[0]) < 2 || !strings.EqualFold(rows[0][0], executableName) {
		return 0
	}
	processID, _ := strconv.Atoi(strings.ReplaceAll(rows[0][1], ",", ""))
	return processID
}
