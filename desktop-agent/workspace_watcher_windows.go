//go:build windows

package main

import (
	"sync"
	"unicode/utf16"
	"unsafe"

	"golang.org/x/sys/windows"
)

func startNativeWorkspaceWatcher(root string, emit func(string, int)) (func(), error) {
	path, err := windows.UTF16PtrFromString(root)
	if err != nil {
		return nil, err
	}
	handle, err := windows.CreateFile(
		path,
		windows.FILE_LIST_DIRECTORY,
		windows.FILE_SHARE_READ|windows.FILE_SHARE_WRITE|windows.FILE_SHARE_DELETE,
		nil,
		windows.OPEN_EXISTING,
		windows.FILE_FLAG_BACKUP_SEMANTICS,
		0,
	)
	if err != nil {
		return nil, err
	}
	var once sync.Once
	stop := func() {
		once.Do(func() {
			_ = windows.CancelIoEx(handle, nil)
			_ = windows.CloseHandle(handle)
		})
	}
	go func() {
		buffer := make([]byte, 64*1024)
		mask := uint32(windows.FILE_NOTIFY_CHANGE_FILE_NAME | windows.FILE_NOTIFY_CHANGE_DIR_NAME |
			windows.FILE_NOTIFY_CHANGE_ATTRIBUTES | windows.FILE_NOTIFY_CHANGE_SIZE |
			windows.FILE_NOTIFY_CHANGE_LAST_WRITE | windows.FILE_NOTIFY_CHANGE_CREATION)
		for {
			var returned uint32
			err := windows.ReadDirectoryChanges(handle, &buffer[0], uint32(len(buffer)), true, mask, &returned, nil, 0)
			if err != nil || returned == 0 {
				return
			}
			for offset := uint32(0); offset < returned; {
				info := (*windows.FileNotifyInformation)(unsafe.Pointer(&buffer[offset]))
				nameLength := int(info.FileNameLength / 2)
				name := string(utf16.Decode(unsafe.Slice(&info.FileName, nameLength)))
				action := map[uint32]int{
					windows.FILE_ACTION_ADDED: workspaceChangeAdded, windows.FILE_ACTION_REMOVED: workspaceChangeRemoved,
					windows.FILE_ACTION_MODIFIED: workspaceChangeModified, windows.FILE_ACTION_RENAMED_OLD_NAME: workspaceChangeRenamedFrom,
					windows.FILE_ACTION_RENAMED_NEW_NAME: workspaceChangeRenamedTo,
				}[info.Action]
				if action != 0 {
					emit(name, action)
				}
				if info.NextEntryOffset == 0 {
					break
				}
				offset += info.NextEntryOffset
			}
		}
	}()
	return stop, nil
}
