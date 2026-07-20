package main

import (
	"errors"
	"strings"
)

const (
	maxSessionModelRequests = 10_000_000
	maxSessionTokenCount    = int64(1_000_000_000_000_000)
)

func validateSessionUsage(usage SessionUsage) error {
	if usage.ModelRequests < 0 || usage.ModelRequests > maxSessionModelRequests ||
		usage.ReportedUsageRequests < 0 || usage.ReportedUsageRequests > usage.ModelRequests {
		return errors.New("会话模型请求统计无效")
	}
	for _, value := range []int64{usage.InputTokens, usage.OutputTokens, usage.TotalTokens, usage.CachedInputTokens, usage.ReasoningOutputTokens} {
		if value < 0 || value > maxSessionTokenCount {
			return errors.New("会话 Token 统计无效")
		}
	}
	if usage.TotalTokens < usage.InputTokens+usage.OutputTokens || usage.CachedInputTokens > usage.InputTokens || usage.ReasoningOutputTokens > usage.OutputTokens {
		return errors.New("会话 Token 明细不一致")
	}
	if len(usage.LastProviderProfileID) > 256 || len(usage.LastProviderID) > 128 || len(usage.LastModel) > 512 {
		return errors.New("会话模型来源过长")
	}
	return nil
}

func normalizeSessionUsage(usage SessionUsage) SessionUsage {
	usage.LastProviderProfileID = strings.TrimSpace(usage.LastProviderProfileID)
	usage.LastProviderID = strings.TrimSpace(usage.LastProviderID)
	usage.LastModel = strings.TrimSpace(usage.LastModel)
	if validateSessionUsage(usage) != nil {
		return SessionUsage{}
	}
	return usage
}

func (store *desktopStore) recordModelUsage(sessionID string, profile ProviderProfile, usage *modelTokenUsage) error {
	store.mu.Lock()
	defer store.mu.Unlock()
	session := store.sessions[strings.TrimSpace(sessionID)]
	if session == nil {
		return errors.New("会话不存在")
	}
	previous := session.Usage
	updated := previous
	updated.ModelRequests++
	updated.LastProviderProfileID = strings.TrimSpace(profile.ID)
	updated.LastProviderID = strings.TrimSpace(profile.ProviderID)
	updated.LastModel = strings.TrimSpace(profile.Model)
	if validModelTokenUsage(usage) {
		updated.ReportedUsageRequests++
		updated.InputTokens += usage.InputTokens
		updated.OutputTokens += usage.OutputTokens
		updated.TotalTokens += usage.TotalTokens
		updated.CachedInputTokens += usage.CachedInputTokens
		updated.ReasoningOutputTokens += usage.ReasoningOutputTokens
	}
	if err := validateSessionUsage(updated); err != nil {
		return err
	}
	session.Usage = updated
	if err := store.saveSessionsLocked(); err != nil {
		session.Usage = previous
		return err
	}
	return nil
}
