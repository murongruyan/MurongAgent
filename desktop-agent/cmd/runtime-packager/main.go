package main

import (
	"archive/tar"
	"compress/gzip"
	"flag"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

func main() {
	source := flag.String("source", "", "runtime directory to package")
	output := flag.String("output", "", "destination .tgz file")
	flag.Parse()
	if err := packageDirectory(*source, *output); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func packageDirectory(source, output string) error {
	sourceRoot, err := filepath.Abs(strings.TrimSpace(source))
	if err != nil || sourceRoot == "" {
		return fmt.Errorf("invalid source directory")
	}
	info, err := os.Stat(sourceRoot)
	if err != nil || !info.IsDir() {
		return fmt.Errorf("runtime source directory does not exist: %s", source)
	}
	outputPath, err := filepath.Abs(strings.TrimSpace(output))
	if err != nil || outputPath == "" || !strings.HasSuffix(strings.ToLower(outputPath), ".tgz") {
		return fmt.Errorf("invalid output archive")
	}
	if err := os.MkdirAll(filepath.Dir(outputPath), 0o755); err != nil {
		return err
	}
	paths := make([]string, 0, 256)
	if err := filepath.WalkDir(sourceRoot, func(path string, entry fs.DirEntry, walkErr error) error {
		if walkErr != nil {
			return walkErr
		}
		if path == sourceRoot {
			return nil
		}
		if entry.Type()&os.ModeSymlink != 0 {
			return fmt.Errorf("runtime source contains a symbolic link: %s", path)
		}
		paths = append(paths, path)
		return nil
	}); err != nil {
		return err
	}
	sort.Strings(paths)
	if len(paths) == 0 {
		return fmt.Errorf("runtime source is empty")
	}

	partial := outputPath + ".partial"
	_ = os.Remove(partial)
	file, err := os.OpenFile(partial, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o644)
	if err != nil {
		return err
	}
	gzipWriter, err := gzip.NewWriterLevel(file, gzip.BestCompression)
	if err != nil {
		_ = file.Close()
		_ = os.Remove(partial)
		return err
	}
	tarWriter := tar.NewWriter(gzipWriter)
	epoch := time.Unix(0, 0).UTC()
	writeErr := func() error {
		for _, path := range paths {
			info, err := os.Stat(path)
			if err != nil {
				return err
			}
			relative, err := filepath.Rel(sourceRoot, path)
			if err != nil {
				return err
			}
			header, err := tar.FileInfoHeader(info, "")
			if err != nil {
				return err
			}
			header.Name = filepath.ToSlash(relative)
			header.ModTime = epoch
			header.AccessTime = epoch
			header.ChangeTime = epoch
			header.Uid = 0
			header.Gid = 0
			header.Uname = ""
			header.Gname = ""
			if err := tarWriter.WriteHeader(header); err != nil {
				return err
			}
			if info.IsDir() {
				continue
			}
			input, err := os.Open(path)
			if err != nil {
				return err
			}
			_, copyErr := io.Copy(tarWriter, input)
			closeErr := input.Close()
			if copyErr != nil {
				return copyErr
			}
			if closeErr != nil {
				return closeErr
			}
		}
		return nil
	}()
	closeTarErr := tarWriter.Close()
	closeGzipErr := gzipWriter.Close()
	closeFileErr := file.Close()
	if writeErr != nil || closeTarErr != nil || closeGzipErr != nil || closeFileErr != nil {
		_ = os.Remove(partial)
		for _, candidate := range []error{writeErr, closeTarErr, closeGzipErr, closeFileErr} {
			if candidate != nil {
				return candidate
			}
		}
	}
	if err := os.Remove(outputPath); err != nil && !os.IsNotExist(err) {
		_ = os.Remove(partial)
		return err
	}
	return os.Rename(partial, outputPath)
}
