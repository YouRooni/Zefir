package main

import (
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"syscall"
	"time"
	"unsafe"

	ole "github.com/go-ole/go-ole"
	"github.com/go-ole/go-ole/oleutil"
	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/registry"
)

const (
	uninstallKey = `Software\Microsoft\Windows\CurrentVersion\Uninstall\Zefir`
	appExe       = "Zefir.exe"
	uninstallExe = "uninstall.exe"
	installMark  = ".zefir-install"
)

var (
	user32          = windows.NewLazySystemDLL("user32.dll")
	procPostMessage = user32.NewProc("PostMessageW")
)

func defaultDir() string {
	if p, err := windows.KnownFolderPath(windows.FOLDERID_UserProgramFiles, windows.KF_FLAG_CREATE); err == nil {
		return filepath.Join(p, "Zefir")
	}
	return filepath.Join(os.Getenv("LOCALAPPDATA"), "Programs", "Zefir")
}

func registeredDir() (string, string) {
	k, err := registry.OpenKey(registry.CURRENT_USER, uninstallKey, registry.QUERY_VALUE)
	if err != nil {
		return "", ""
	}
	defer k.Close()
	dir, _, _ := k.GetStringValue("InstallLocation")
	version, _, _ := k.GetStringValue("DisplayVersion")
	return dir, version
}

func writeUninstallEntry(dir, version string, size int64) error {
	k, _, err := registry.CreateKey(registry.CURRENT_USER, uninstallKey, registry.SET_VALUE)
	if err != nil {
		return err
	}
	defer k.Close()
	uninstall := filepath.Join(dir, uninstallExe)
	for name, value := range map[string]string{
		"DisplayName":          "Zefir",
		"DisplayVersion":       version,
		"Publisher":            "YouRooni",
		"DisplayIcon":          filepath.Join(dir, appExe),
		"InstallLocation":      dir,
		"UninstallString":      `"` + uninstall + `" --uninstall`,
		"QuietUninstallString": `"` + uninstall + `" --uninstall --silent`,
		"URLInfoAbout":         "https://github.com/YouRooni/Zefir",
	} {
		if err := k.SetStringValue(name, value); err != nil {
			return err
		}
	}
	for name, value := range map[string]uint32{
		"NoModify":      1,
		"NoRepair":      1,
		"EstimatedSize": uint32(size >> 10),
	} {
		if err := k.SetDWordValue(name, value); err != nil {
			return err
		}
	}
	return nil
}

func removeUninstallEntry() {
	registry.DeleteKey(registry.CURRENT_USER, uninstallKey)
}

func desktopLink() string {
	p, err := windows.KnownFolderPath(windows.FOLDERID_Desktop, 0)
	if err != nil {
		return ""
	}
	return filepath.Join(p, "Zefir.lnk")
}

func startMenuLink() string {
	p, err := windows.KnownFolderPath(windows.FOLDERID_Programs, 0)
	if err != nil {
		return ""
	}
	return filepath.Join(p, "Zefir.lnk")
}

func fileExists(path string) bool {
	_, err := os.Stat(path)
	return err == nil
}

func createShortcuts(dir string, links ...string) error {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	if err := ole.CoInitializeEx(0, ole.COINIT_APARTMENTTHREADED); err != nil {
		var oe *ole.OleError
		if !errors.As(err, &oe) || oe.Code() != 1 {
			return err
		}
	}
	defer ole.CoUninitialize()
	unknown, err := oleutil.CreateObject("WScript.Shell")
	if err != nil {
		return err
	}
	defer unknown.Release()
	shell, err := unknown.QueryInterface(ole.IID_IDispatch)
	if err != nil {
		return err
	}
	defer shell.Release()
	target := filepath.Join(dir, appExe)
	for _, link := range links {
		if link == "" {
			continue
		}
		v, err := oleutil.CallMethod(shell, "CreateShortcut", link)
		if err != nil {
			return err
		}
		lnk := v.ToIDispatch()
		for prop, value := range map[string]string{
			"TargetPath":       target,
			"WorkingDirectory": dir,
			"IconLocation":     target + ",0",
			"Description":      "Zefir",
		} {
			if _, err := oleutil.PutProperty(lnk, prop, value); err != nil {
				lnk.Release()
				return err
			}
		}
		_, err = oleutil.CallMethod(lnk, "Save")
		lnk.Release()
		if err != nil {
			return err
		}
	}
	return nil
}

func processesIn(dir string) []uint32 {
	snap, err := windows.CreateToolhelp32Snapshot(windows.TH32CS_SNAPPROCESS, 0)
	if err != nil {
		return nil
	}
	defer windows.CloseHandle(snap)
	prefix := strings.ToLower(filepath.Clean(dir)) + `\`
	self := windows.GetCurrentProcessId()
	var found []uint32
	entry := windows.ProcessEntry32{Size: uint32(unsafe.Sizeof(windows.ProcessEntry32{}))}
	for err = windows.Process32First(snap, &entry); err == nil; err = windows.Process32Next(snap, &entry) {
		if entry.ProcessID == self || entry.ProcessID == 0 {
			continue
		}
		h, err := windows.OpenProcess(windows.PROCESS_QUERY_LIMITED_INFORMATION, false, entry.ProcessID)
		if err != nil {
			continue
		}
		buf := make([]uint16, windows.MAX_LONG_PATH)
		size := uint32(len(buf))
		err = windows.QueryFullProcessImageName(h, 0, &buf[0], &size)
		windows.CloseHandle(h)
		if err == nil && strings.HasPrefix(strings.ToLower(windows.UTF16ToString(buf[:size])), prefix) {
			found = append(found, entry.ProcessID)
		}
	}
	return found
}

func closeProcesses(dir string) error {
	pids := processesIn(dir)
	if len(pids) == 0 {
		return nil
	}
	wanted := map[uint32]bool{}
	for _, pid := range pids {
		wanted[pid] = true
	}
	windows.EnumWindows(syscall.NewCallback(func(hwnd windows.HWND, _ uintptr) uintptr {
		var pid uint32
		windows.GetWindowThreadProcessId(hwnd, &pid)
		if wanted[pid] && windows.IsWindowVisible(hwnd) {
			procPostMessage.Call(uintptr(hwnd), 0x0010, 0, 0)
		}
		return 1
	}), nil)
	for range 40 {
		if len(processesIn(dir)) == 0 {
			return nil
		}
		time.Sleep(200 * time.Millisecond)
	}
	for _, pid := range processesIn(dir) {
		if h, err := windows.OpenProcess(windows.PROCESS_TERMINATE, false, pid); err == nil {
			windows.TerminateProcess(h, 0)
			windows.CloseHandle(h)
		}
	}
	for range 15 {
		if len(processesIn(dir)) == 0 {
			return nil
		}
		time.Sleep(200 * time.Millisecond)
	}
	return errAppBusy
}

func launchApp(dir string) error {
	cmd := exec.Command(filepath.Join(dir, appExe))
	cmd.Dir = dir
	if err := cmd.Start(); err != nil {
		return err
	}
	return cmd.Process.Release()
}

func deleteSelfLater(path string) {
	cmd := exec.Command("cmd.exe", "/c", "ping -n 4 127.0.0.1 >nul & del /f /q \""+path+"\"")
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: windows.CREATE_NO_WINDOW}
	if cmd.Start() == nil {
		cmd.Process.Release()
	}
}

func systemDark() bool {
	k, err := registry.OpenKey(registry.CURRENT_USER, `Software\Microsoft\Windows\CurrentVersion\Themes\Personalize`, registry.QUERY_VALUE)
	if err != nil {
		return false
	}
	defer k.Close()
	v, _, err := k.GetIntegerValue("AppsUseLightTheme")
	return err == nil && v == 0
}
