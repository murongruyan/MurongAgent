package main

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"sort"
	"strings"

	desktopbridge "github.com/murong-agent/desktop-bridge"
)

const (
	maximumSyncedSessions        = 10_000
	maximumSyncedSessionBytes    = 32 << 20
	syncedSessionConflictHashLen = 12
)

type syncedSessionMergeResult struct {
	Imported  int
	Conflicts int
	Skipped   int
}

func (store *desktopStore) exportSyncedSessions() ([]desktopbridge.SyncedSession, error) {
	store.mu.Lock()
	sessions := make([]*ChatSession, 0, len(store.sessions))
	for _, session := range store.sessions {
		sessions = append(sessions, cloneSession(session))
	}
	store.mu.Unlock()
	sort.Slice(sessions, func(i, j int) bool { return sessions[i].UpdatedAt > sessions[j].UpdatedAt })
	records := make([]desktopbridge.SyncedSession, 0, len(sessions))
	for _, session := range sessions {
		document, err := encodeCrossPlatformSession(session)
		if err != nil {
			return nil, err
		}
		originPlatform, originSessionID := syncedSessionOrigin(
			desktopSourcePlatform(),
			session.ID,
			session.SyncOriginPlatform,
			session.SyncOriginSessionID,
		)
		records = append(records, desktopbridge.SyncedSession{
			SourceSessionID: session.ID,
			OriginPlatform:  originPlatform,
			OriginSessionID: originSessionID,
			Document:        json.RawMessage(document),
		})
	}
	if err := validateSyncedSessions(desktopSourcePlatform(), records); err != nil {
		return nil, err
	}
	return records, nil
}

func validateSyncedSessions(sourcePlatform string, records []desktopbridge.SyncedSession) error {
	if len(records) > maximumSyncedSessions {
		return fmt.Errorf("聊天记录数量超过 %d 个", maximumSyncedSessions)
	}
	seen := make(map[string]struct{}, len(records))
	for _, record := range records {
		id := strings.TrimSpace(record.SourceSessionID)
		if id == "" || len(id) > 500 || strings.ContainsAny(id, "\x00\r\n") {
			return errors.New("同步聊天记录包含无效会话 ID")
		}
		if _, exists := seen[id]; exists {
			return errors.New("同步聊天记录包含重复会话 ID")
		}
		seen[id] = struct{}{}
		originPlatform, originSessionID := syncedSessionOrigin(
			sourcePlatform,
			id,
			record.OriginPlatform,
			record.OriginSessionID,
		)
		if !supportedSyncedSessionPlatform(originPlatform) {
			return errors.New("同步聊天记录包含无效来源平台")
		}
		if err := validateSyncedSessionID(originSessionID); err != nil {
			return errors.New("同步聊天记录包含无效原始会话 ID")
		}
		if len(record.Document) == 0 || len(record.Document) > maximumSyncedSessionBytes {
			return errors.New("同步聊天记录包含空白或过大会话")
		}
		if _, _, err := decodeSyncedSessionRecord(sourcePlatform, record); err != nil {
			return err
		}
	}
	return nil
}

func (store *desktopStore) mergeSyncedSessions(
	sourcePlatform string,
	records []desktopbridge.SyncedSession,
) (syncedSessionMergeResult, error) {
	if len(records) == 0 {
		return syncedSessionMergeResult{}, nil
	}
	if err := validateSyncedSessions(sourcePlatform, records); err != nil {
		return syncedSessionMergeResult{}, err
	}
	store.mu.Lock()
	defer store.mu.Unlock()

	added := make([]string, 0, len(records))
	result := syncedSessionMergeResult{}
	for _, record := range records {
		incoming, portable, err := decodeSyncedSessionRecord(sourcePlatform, record)
		if err != nil {
			return syncedSessionMergeResult{}, err
		}
		canonical, err := json.Marshal(portable)
		if err != nil {
			return syncedSessionMergeResult{}, err
		}
		originPlatform, originSessionID := syncedSessionOrigin(
			sourcePlatform,
			record.SourceSessionID,
			record.OriginPlatform,
			record.OriginSessionID,
		)
		primaryID := syncedSessionID(originPlatform, originSessionID)
		if originPlatform == desktopSourcePlatform() {
			primaryID = originSessionID
		}
		targetID := primaryID
		if existing := store.sessions[primaryID]; existing != nil {
			equivalent, equivalentErr := desktopSessionPortableEqual(existing, canonical)
			if equivalentErr != nil {
				return syncedSessionMergeResult{}, equivalentErr
			}
			if equivalent {
				result.Skipped++
				continue
			}
			digest := sha256.Sum256(canonical)
			targetID = primaryID + "-" + hex.EncodeToString(digest[:])[:syncedSessionConflictHashLen]
			if conflict := store.sessions[targetID]; conflict != nil {
				equivalent, equivalentErr = desktopSessionPortableEqual(conflict, canonical)
				if equivalentErr != nil {
					return syncedSessionMergeResult{}, equivalentErr
				}
				if equivalent {
					result.Skipped++
					continue
				}
				return syncedSessionMergeResult{}, errors.New("同步聊天记录冲突副本 ID 碰撞")
			}
			result.Conflicts++
		}
		incoming.ID = targetID
		incoming.SyncOriginPlatform = originPlatform
		incoming.SyncOriginSessionID = originSessionID
		store.sessions[targetID] = incoming
		added = append(added, targetID)
		result.Imported++
	}
	if len(added) == 0 {
		return result, nil
	}
	if err := store.saveSessionsLocked(); err != nil {
		for _, id := range added {
			delete(store.sessions, id)
		}
		return syncedSessionMergeResult{}, err
	}
	return result, nil
}

func decodeSyncedSessionRecord(
	sourcePlatform string,
	record desktopbridge.SyncedSession,
) (*ChatSession, CrossPlatformSession, error) {
	raw := []byte(record.Document)
	session, err := decodeCrossPlatformSession(raw)
	if err != nil {
		return nil, CrossPlatformSession{}, fmt.Errorf("同步聊天记录无效：%w", err)
	}
	var envelope CrossPlatformSessionEnvelope
	if err := json.Unmarshal(raw, &envelope); err != nil {
		return nil, CrossPlatformSession{}, fmt.Errorf("同步聊天记录无效：%w", err)
	}
	if envelope.SourcePlatform != sourcePlatform {
		return nil, CrossPlatformSession{}, errors.New("同步聊天记录来源与同步设备不一致")
	}
	prepareSyncedDesktopSession(session)
	// Compare and hash the representation that Desktop can actually persist.
	// This keeps retries idempotent when a valid sender value is normalized on
	// import (for example, leading/trailing whitespace in a legacy title).
	normalizedRaw, err := encodeCrossPlatformSession(session)
	if err != nil {
		return nil, CrossPlatformSession{}, fmt.Errorf("同步聊天记录规范化失败：%w", err)
	}
	var normalized CrossPlatformSessionEnvelope
	if err := json.Unmarshal(normalizedRaw, &normalized); err != nil {
		return nil, CrossPlatformSession{}, fmt.Errorf("同步聊天记录规范化失败：%w", err)
	}
	return session, normalized.Session, nil
}

func prepareSyncedDesktopSession(session *ChatSession) {
	sourceMessages := append([]ChatMessage(nil), session.Messages...)
	for index := range session.Messages {
		session.Messages[index].ID = newID("message")
		session.Messages[index].Context = nil
		session.Messages[index].ImageAttachments = nil
	}
	session.Compression = remapSessionCompression(session.Compression, sourceMessages, session.Messages)
	session.ProjectPath = ""
	session.PlanModeEnabled = false
	session.WorkflowPlan = nil
	session.CodexThreadID = ""
	session.CodexSyncedID = ""
	session.CodexToolsVersion = 0
	session.BackgroundSubagentJobs = nil
	session.ExecutionHandoff = SessionExecutionHandoff{}
	if messageGoal := sessionGoalFromMessages(session.Messages); messageGoal != "" {
		session.Goal = messageGoal
	} else {
		session.Goal = truncateRunes(session.Goal, 20_000)
	}
}

func desktopSessionPortableEqual(session *ChatSession, incoming []byte) (bool, error) {
	raw, err := encodeCrossPlatformSession(session)
	if err != nil {
		return false, err
	}
	var envelope CrossPlatformSessionEnvelope
	if err := json.Unmarshal(raw, &envelope); err != nil {
		return false, err
	}
	canonical, err := json.Marshal(envelope.Session)
	if err != nil {
		return false, err
	}
	return bytes.Equal(canonical, incoming), nil
}

func syncedSessionID(sourcePlatform, sourceSessionID string) string {
	digest := sha256.Sum256([]byte(sourcePlatform + "\x00" + strings.TrimSpace(sourceSessionID)))
	return "portable-" + sourcePlatform + "-" + hex.EncodeToString(digest[:])[:24]
}

func syncedSessionOrigin(sourcePlatform, sourceSessionID, originPlatform, originSessionID string) (string, string) {
	originPlatform = strings.TrimSpace(originPlatform)
	originSessionID = strings.TrimSpace(originSessionID)
	if originPlatform == "" || originSessionID == "" {
		return strings.TrimSpace(sourcePlatform), strings.TrimSpace(sourceSessionID)
	}
	return originPlatform, originSessionID
}

func supportedSyncedSessionPlatform(value string) bool {
	switch value {
	case crossPlatformSourceWindows, crossPlatformSourceMacOS, crossPlatformSourceLinux, crossPlatformSourceAndroid, "desktop":
		return true
	default:
		return false
	}
}

func validateSyncedSessionID(value string) error {
	value = strings.TrimSpace(value)
	if value == "" || len(value) > 500 || strings.ContainsAny(value, "\x00\r\n") {
		return errors.New("invalid synced session id")
	}
	return nil
}
