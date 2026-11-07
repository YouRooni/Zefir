package main

import (
	"context"
	"errors"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"syscall"
	"time"

	"golang.org/x/sys/windows"
)

var (
	errAppBusy    = errors.New("app busy")
	errDirNotOurs = errors.New("dir not ours")
	errNoAccess   = errors.New("no access")
)

func accessError(err error) error {
	if errors.Is(err, fs.ErrPermission) || errors.Is(err, windows.ERROR_ACCESS_DENIED) || errors.Is(err, syscall.EACCES) {
		return errNoAccess
	}
	return err
}

func dirUsable(dir string) error {
	entries, err := os.ReadDir(dir)
	if errors.Is(err, fs.ErrNotExist) {
		return nil
	}
	if err != nil {
		return accessError(err)
	}
	if len(entries) == 0 || fileExists(filepath.Join(dir, installMark)) {
		return nil
	}
	return errDirNotOurs
}

func normalizeDir(picked string) string {
	if strings.EqualFold(filepath.Base(picked), "Zefir") {
		return filepath.Clean(picked)
	}
	return filepath.Join(picked, "Zefir")
}

func renameRetry(from, to string) error {
	var err error
	for range 25 {
		if err = os.Rename(from, to); err == nil {
			return nil
		}
		time.Sleep(200 * time.Millisecond)
	}
	return err
}

func install(ctx context.Context, p *payload, dir string, desktop bool, done *atomic.Int64) error {
	dir = filepath.Clean(dir)
	if err := dirUsable(dir); err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(dir), 0o755); err != nil {
		return accessError(err)
	}
	parent, base := filepath.Dir(dir), filepath.Base(dir)
	staging, err := os.MkdirTemp(parent, base+".new-*")
	if err != nil {
		return accessError(err)
	}
	defer os.RemoveAll(staging)
	if err := p.extract(ctx, staging, done); err != nil {
		return accessError(err)
	}
	if err := p.writeStub(filepath.Join(staging, uninstallExe)); err != nil {
		return accessError(err)
	}
	if err := os.WriteFile(filepath.Join(staging, installMark), []byte(p.meta.Version), 0o644); err != nil {
		return accessError(err)
	}
	if err := ctx.Err(); err != nil {
		return err
	}

	old := ""
	if fileExists(dir) {
		reserved, err := os.MkdirTemp(parent, base+".old-*")
		if err != nil {
			return accessError(err)
		}
		os.Remove(reserved)
		if err := renameRetry(dir, reserved); err != nil {
			return errAppBusy
		}
		old = reserved
	}
	if err := renameRetry(staging, dir); err != nil {
		if old != "" {
			if rerr := renameRetry(old, dir); rerr != nil {
				return errors.Join(accessError(err), rerr)
			}
		}
		return accessError(err)
	}
	if old != "" {
		os.RemoveAll(old)
	}

	links := []string{startMenuLink()}
	if desktop {
		links = append(links, desktopLink())
	} else if l := desktopLink(); l != "" {
		os.Remove(l)
	}
	if err := createShortcuts(dir, links...); err != nil {
		return err
	}
	return writeUninstallEntry(dir, p.meta.Version, p.meta.Size)
}

func uninstall(dir string, removeData bool) error {
	if err := closeProcesses(dir); err != nil {
		return err
	}
	if !fileExists(filepath.Join(dir, installMark)) {
		return errDirNotOurs
	}
	if err := os.RemoveAll(dir); err != nil {
		return accessError(err)
	}
	for _, link := range []string{startMenuLink(), desktopLink()} {
		if link != "" {
			os.Remove(link)
		}
	}
	removeUninstallEntry()
	if removeData {
		if home, err := os.UserHomeDir(); err == nil {
			os.RemoveAll(filepath.Join(home, ".zefir"))
		}
	}
	return nil
}
