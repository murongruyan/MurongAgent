package main

import (
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"
)

const (
	sessionHandoffVersion               = 1
	sessionExecutionOwnerDesktop        = "desktop"
	sessionExecutionOwnerAndroid        = "android"
	remoteSessionHandoffMaxRawBytes     = 1536 * 1024
	remoteSessionHandoffMaxEncodedBytes = 3 * 1024 * 1024
)

type SessionHandoffPackage struct {
	Token            string
	PortableSession  string
	BaseMessageCount int
}

func sessionExecutionOwner(session *ChatSession) string {
	if session != nil && session.ExecutionHandoff.Owner == sessionExecutionOwnerAndroid {
		return sessionExecutionOwnerAndroid
	}
	return sessionExecutionOwnerDesktop
}

func requireDesktopExecutionAuthority(session *ChatSession) error {
	if sessionExecutionOwner(session) != sessionExecutionOwnerDesktop {
		return errors.New("当前任务已由手机接管；请先从手机归还，或在电脑端强制收回执行权")
	}
	return nil
}

func normalizeSessionExecutionHandoff(value SessionExecutionHandoff, messageCount int) (SessionExecutionHandoff, bool) {
	if value.Owner == "" || value.Owner == sessionExecutionOwnerDesktop {
		return SessionExecutionHandoff{}, value != (SessionExecutionHandoff{})
	}
	normalized := value
	normalized.Owner = strings.ToLower(strings.TrimSpace(value.Owner))
	normalized.Token = strings.TrimSpace(value.Token)
	normalized.BaseDigest = strings.ToLower(strings.TrimSpace(value.BaseDigest))
	if normalized.Owner != sessionExecutionOwnerAndroid ||
		normalized.Version != sessionHandoffVersion ||
		!strings.HasPrefix(normalized.Token, "handoff-") ||
		len(normalized.Token) != len("handoff-")+64 ||
		len(normalized.BaseDigest) != sha256.Size*2 ||
		normalized.BaseMessageCount < 0 || normalized.BaseMessageCount > messageCount ||
		normalized.StartedAt <= 0 {
		return SessionExecutionHandoff{}, true
	}
	if _, err := hex.DecodeString(strings.TrimPrefix(normalized.Token, "handoff-")); err != nil {
		return SessionExecutionHandoff{}, true
	}
	if _, err := hex.DecodeString(normalized.BaseDigest); err != nil {
		return SessionExecutionHandoff{}, true
	}
	return normalized, normalized != value
}

func validateSessionExecutionHandoff(value SessionExecutionHandoff, messageCount int) error {
	normalized, changed := normalizeSessionExecutionHandoff(value, messageCount)
	if changed || normalized != value {
		return errors.New("会话执行权接管状态无效")
	}
	return nil
}

func newSessionHandoffToken() (string, error) {
	buffer := make([]byte, 32)
	if _, err := rand.Read(buffer); err != nil {
		return "", fmt.Errorf("无法生成接管令牌：%w", err)
	}
	return "handoff-" + hex.EncodeToString(buffer), nil
}

func validateRemoteHandoffDocument(data []byte) error {
	if len(data) == 0 || len(data) > remoteSessionHandoffMaxRawBytes {
		return errors.New("跨端接管内容为空或超过 1.5 MiB；请先压缩或分叉较短的任务")
	}
	encoded, err := json.Marshal(string(data))
	if err != nil || len(encoded) > remoteSessionHandoffMaxEncodedBytes {
		return errors.New("跨端接管内容转义后超过安全传输上限；请先压缩或分叉较短的任务")
	}
	return nil
}

func portablePrefixDigest(session *ChatSession, count int) (string, error) {
	if session == nil || count < 0 || count > len(session.Messages) {
		return "", errors.New("接管消息前缀无效")
	}
	type digestMessage struct {
		Role      string `json:"role"`
		Content   string `json:"content"`
		CreatedAt int64  `json:"createdAtEpochMillis"`
	}
	messages := portableCrossPlatformMessages(session.Messages[:count])
	digestInput := make([]digestMessage, 0, len(messages))
	for _, message := range messages {
		digestInput = append(digestInput, digestMessage{
			Role: message.Role, Content: message.Content, CreatedAt: message.CreatedAt,
		})
	}
	encoded, err := json.Marshal(digestInput)
	if err != nil {
		return "", err
	}
	digest := sha256.Sum256(encoded)
	return hex.EncodeToString(digest[:]), nil
}

func (store *desktopStore) beginSessionHandoff(id string) (SessionHandoffPackage, *ChatSession, error) {
	store.mu.Lock()
	defer store.mu.Unlock()
	session := store.sessions[strings.TrimSpace(id)]
	if session == nil {
		return SessionHandoffPackage{}, nil, errors.New("会话不存在")
	}
	if err := requireDesktopExecutionAuthority(session); err != nil {
		return SessionHandoffPackage{}, nil, err
	}
	if len(session.Messages) == 0 && strings.TrimSpace(session.Goal) == "" {
		return SessionHandoffPackage{}, nil, errors.New("空任务无需接管；请先发送一条消息或设置目标")
	}
	for _, job := range session.BackgroundSubagentJobs {
		if activeSubagentJobStatuses[job.Status] {
			return SessionHandoffPackage{}, nil, errors.New("当前任务仍有后台子代理，结束后才能交给手机")
		}
	}
	if plan := session.WorkflowPlan; plan != nil && plan.Status != workflowPlanCompleted {
		return SessionHandoffPackage{}, nil, errors.New("当前任务仍有未完成的规范计划；请先完成或清除计划再交给手机")
	}
	portable, err := encodeCrossPlatformSession(session)
	if err != nil {
		return SessionHandoffPackage{}, nil, err
	}
	if err := validateRemoteHandoffDocument(portable); err != nil {
		return SessionHandoffPackage{}, nil, err
	}
	digest, err := portablePrefixDigest(session, len(session.Messages))
	if err != nil {
		return SessionHandoffPackage{}, nil, err
	}
	token, err := newSessionHandoffToken()
	if err != nil {
		return SessionHandoffPackage{}, nil, err
	}
	previous := session.ExecutionHandoff
	session.ExecutionHandoff = SessionExecutionHandoff{
		Version: sessionHandoffVersion, Owner: sessionExecutionOwnerAndroid, Token: token,
		BaseMessageCount: len(session.Messages), BaseDigest: digest, StartedAt: time.Now().UnixMilli(),
	}
	if err := store.saveSessionsLocked(); err != nil {
		session.ExecutionHandoff = previous
		return SessionHandoffPackage{}, nil, err
	}
	return SessionHandoffPackage{
		Token: token, PortableSession: string(portable), BaseMessageCount: len(session.Messages),
	}, cloneSession(session), nil
}

func (store *desktopStore) returnSessionHandoff(id, token, portableDocument string) (*ChatSession, error) {
	data := []byte(portableDocument)
	if err := validateRemoteHandoffDocument(data); err != nil {
		return nil, err
	}
	var header struct {
		SourcePlatform string `json:"sourcePlatform"`
	}
	if err := json.Unmarshal(data, &header); err != nil || header.SourcePlatform != crossPlatformSourceAndroid {
		return nil, errors.New("归还内容必须由 Murong Android 生成")
	}
	returned, err := decodeCrossPlatformSession(data)
	if err != nil {
		return nil, err
	}

	store.mu.Lock()
	defer store.mu.Unlock()
	session := store.sessions[strings.TrimSpace(id)]
	if session == nil {
		return nil, errors.New("会话不存在")
	}
	handoff := session.ExecutionHandoff
	if sessionExecutionOwner(session) != sessionExecutionOwnerAndroid {
		return nil, errors.New("当前任务没有交给手机，归还请求已经失效")
	}
	if subtle.ConstantTimeCompare([]byte(strings.TrimSpace(token)), []byte(handoff.Token)) != 1 {
		return nil, errors.New("接管令牌无效；电脑可能已经强制收回执行权")
	}
	if len(returned.Messages) < handoff.BaseMessageCount {
		return nil, errors.New("手机任务缺少接管前的消息，不能安全归还")
	}
	currentDigest, err := portablePrefixDigest(session, handoff.BaseMessageCount)
	if err != nil || currentDigest != handoff.BaseDigest || len(session.Messages) != handoff.BaseMessageCount {
		return nil, errors.New("电脑原任务在接管期间发生变化，已拒绝自动合并")
	}
	returnedDigest, err := portablePrefixDigest(returned, handoff.BaseMessageCount)
	if err != nil || returnedDigest != handoff.BaseDigest {
		return nil, errors.New("手机任务修改了接管前历史，已拒绝自动合并")
	}

	previous := cloneSession(session)
	for _, returnedMessage := range returned.Messages[handoff.BaseMessageCount:] {
		returnedMessage.ID = newID("message")
		returnedMessage.Context = nil
		returnedMessage.ImageAttachments = nil
		returnedMessage.WorkspaceChanges = nil
		returnedMessage.WorkspaceChangesOmitted = 0
		session.Messages = append(session.Messages, returnedMessage)
	}
	if title := truncateRunes(returned.Title, 60); title != "" {
		session.Title = title
	}
	session.Goal = truncateRunes(returned.Goal, 20_000)
	session.Usage = mergeReturnedSessionUsage(session.Usage, returned.Usage)
	if strings.TrimSpace(returned.Compression.Summary) != "" {
		session.Compression = remapSessionCompression(returned.Compression, returned.Messages, session.Messages)
	} else {
		session.Compression = normalizeSessionCompression(session.Compression, session.Messages)
	}
	session.CodexThreadID = ""
	session.CodexSyncedID = ""
	session.CodexToolsVersion = 0
	session.ExecutionHandoff = SessionExecutionHandoff{}
	session.UpdatedAt = time.Now().UnixMilli()
	if err := store.saveSessionsLocked(); err != nil {
		store.sessions[session.ID] = previous
		return nil, err
	}
	return cloneSession(session), nil
}

func mergeReturnedSessionUsage(current, returned SessionUsage) SessionUsage {
	merged := current
	merged.InputTokens = max(current.InputTokens, returned.InputTokens)
	merged.OutputTokens = max(current.OutputTokens, returned.OutputTokens)
	merged.TotalTokens = max(current.TotalTokens, returned.TotalTokens, merged.InputTokens+merged.OutputTokens)
	merged.CachedInputTokens = min(merged.InputTokens, max(current.CachedInputTokens, returned.CachedInputTokens))
	merged.ReasoningOutputTokens = min(merged.OutputTokens, max(current.ReasoningOutputTokens, returned.ReasoningOutputTokens))
	if strings.TrimSpace(returned.LastProviderID) != "" {
		merged.LastProviderID = returned.LastProviderID
	}
	if strings.TrimSpace(returned.LastModel) != "" {
		merged.LastModel = returned.LastModel
	}
	return normalizeSessionUsage(merged)
}

func (store *desktopStore) abortSessionHandoff(id, token string) (*ChatSession, error) {
	store.mu.Lock()
	defer store.mu.Unlock()
	session := store.sessions[strings.TrimSpace(id)]
	if session == nil {
		return nil, errors.New("会话不存在")
	}
	if sessionExecutionOwner(session) != sessionExecutionOwnerAndroid {
		return nil, errors.New("当前任务没有交给手机")
	}
	if subtle.ConstantTimeCompare([]byte(strings.TrimSpace(token)), []byte(session.ExecutionHandoff.Token)) != 1 {
		return nil, errors.New("接管令牌无效")
	}
	previous := session.ExecutionHandoff
	session.ExecutionHandoff = SessionExecutionHandoff{}
	if err := store.saveSessionsLocked(); err != nil {
		session.ExecutionHandoff = previous
		return nil, err
	}
	return cloneSession(session), nil
}

func (store *desktopStore) forceReclaimSessionHandoff(id string) (*ChatSession, error) {
	store.mu.Lock()
	defer store.mu.Unlock()
	session := store.sessions[strings.TrimSpace(id)]
	if session == nil {
		return nil, errors.New("会话不存在")
	}
	if sessionExecutionOwner(session) != sessionExecutionOwnerAndroid {
		return nil, errors.New("当前任务的执行权已经在电脑")
	}
	previous := session.ExecutionHandoff
	session.ExecutionHandoff = SessionExecutionHandoff{}
	if err := store.saveSessionsLocked(); err != nil {
		session.ExecutionHandoff = previous
		return nil, err
	}
	return cloneSession(session), nil
}

func (store *desktopStore) hasActiveSessionHandoff() bool {
	store.mu.Lock()
	defer store.mu.Unlock()
	for _, session := range store.sessions {
		if sessionExecutionOwner(session) == sessionExecutionOwnerAndroid {
			return true
		}
	}
	return false
}
