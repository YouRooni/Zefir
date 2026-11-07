package main

//go:generate go run github.com/akavel/rsrc@v0.10.2 -arch amd64 -manifest zefir.manifest -ico ../desktopApp/icons/zefir.ico -o rsrc_windows_amd64.syso

import (
	"context"
	"flag"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync/atomic"

	"golang.org/x/sys/windows"
)

func main() {
	uninstallMode := flag.Bool("uninstall", false, "")
	silent := flag.Bool("silent", false, "")
	dirFlag := flag.String("dir", "", "")
	noDesktop := flag.Bool("no-desktop", false, "")
	cleanup := flag.Bool("cleanup", false, "")
	flag.Parse()

	p, err := openPayload()
	if err != nil {
		fail(*silent, err)
	}
	afterExit := func() {}
	regDir, regVersion := registeredDir()
	dir := *dirFlag
	if dir == "" {
		dir = regDir
	}

	if *uninstallMode || p == nil {
		*uninstallMode = true
		exe, _ := os.Executable()
		if dir == "" {
			dir = filepath.Dir(exe)
		}
		if !*cleanup && strings.HasPrefix(strings.ToLower(exe), strings.ToLower(filepath.Clean(dir))+`\`) {
			if err := relaunchFromTemp(exe, dir, *silent); err != nil {
				fail(*silent, err)
			}
			return
		}
		if *cleanup {
			afterExit = func() { deleteSelfLater(exe) }
			defer afterExit()
		}
	} else if dir == "" {
		dir = defaultDir()
	}

	if *silent {
		runSilent(p, dir, *uninstallMode, !*noDesktop)
		return
	}

	name, _ := windows.UTF16PtrFromString("Local\\dev.rooni.zefir.setup")
	if _, err := windows.CreateMutex(nil, false, name); err == windows.ERROR_ALREADY_EXISTS {
		return
	}
	runUI(setup{
		payload:   p,
		dir:       dir,
		upgrading: regDir != "" && regVersion != "" && !*uninstallMode,
		uninstall: *uninstallMode,
		desktop:   desktopDefault(regDir != ""),
		afterExit: afterExit,
	})
}

func desktopDefault(installed bool) bool {
	return !installed || fileExists(desktopLink())
}

func relaunchFromTemp(exe, dir string, silent bool) error {
	tmp, err := os.CreateTemp("", "zefir-uninstall-*.exe")
	if err != nil {
		return err
	}
	src, err := os.ReadFile(exe)
	if err == nil {
		_, err = tmp.Write(src)
	}
	tmp.Close()
	if err != nil {
		os.Remove(tmp.Name())
		return err
	}
	args := []string{"--uninstall", "--cleanup", "--dir", dir}
	if silent {
		args = append(args, "--silent")
	}
	cmd := exec.Command(tmp.Name(), args...)
	if err := cmd.Start(); err != nil {
		os.Remove(tmp.Name())
		return err
	}
	return cmd.Process.Release()
}

func runSilent(p *payload, dir string, removing, desktop bool) {
	var err error
	if removing {
		err = uninstall(dir, false)
	} else {
		if err = closeProcesses(dir); err == nil {
			var done atomic.Int64
			err = install(context.Background(), p, dir, desktop, &done)
		}
	}
	if err != nil {
		fail(true, err)
	}
}

func fail(silent bool, err error) {
	if silent {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	title, _ := windows.UTF16PtrFromString("Zefir")
	text, _ := windows.UTF16PtrFromString(err.Error())
	windows.MessageBox(0, text, title, windows.MB_ICONERROR)
	os.Exit(1)
}
