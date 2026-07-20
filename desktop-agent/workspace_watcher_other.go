//go:build !windows

package main

import (
	"io/fs"
	"os"
	"path/filepath"
	"sync"

	"github.com/fsnotify/fsnotify"
)

func startNativeWorkspaceWatcher(root string, emit func(string, int)) (func(), error) {
	watcher, err := fsnotify.NewWatcher()
	if err != nil {
		return nil, err
	}
	addTree := func(path string) error {
		return filepath.WalkDir(path, func(candidate string, entry fs.DirEntry, walkErr error) error {
			if walkErr != nil {
				return nil
			}
			if entry.IsDir() {
				return watcher.Add(candidate)
			}
			return nil
		})
	}
	if err := addTree(root); err != nil {
		_ = watcher.Close()
		return nil, err
	}
	var once sync.Once
	stop := func() { once.Do(func() { _ = watcher.Close() }) }
	go func() {
		for {
			select {
			case event, ok := <-watcher.Events:
				if !ok {
					return
				}
				relative, relErr := filepath.Rel(root, event.Name)
				if relErr != nil || relative == "." || relative == ".." || filepath.IsAbs(relative) {
					continue
				}
				if event.Op&fsnotify.Create != 0 {
					if info, statErr := os.Stat(event.Name); statErr == nil && info.IsDir() {
						_ = addTree(event.Name)
					}
					emit(relative, workspaceChangeAdded)
				}
				if event.Op&fsnotify.Write != 0 || event.Op&fsnotify.Chmod != 0 {
					emit(relative, workspaceChangeModified)
				}
				if event.Op&fsnotify.Remove != 0 {
					emit(relative, workspaceChangeRemoved)
				}
				if event.Op&fsnotify.Rename != 0 {
					emit(relative, workspaceChangeRenamedFrom)
				}
			case _, ok := <-watcher.Errors:
				if !ok {
					return
				}
			}
		}
	}()
	return stop, nil
}
