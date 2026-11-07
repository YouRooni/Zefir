package main

import (
	"errors"
	"runtime"
	"syscall"
	"unsafe"

	ole "github.com/go-ole/go-ole"
	"golang.org/x/sys/windows"
)

const (
	vtRelease        = 2
	vtShow           = 3
	vtSetOptions     = 9
	vtGetOptions     = 10
	vtSetFolder      = 12
	vtSetTitle       = 17
	vtGetResult      = 20
	vtGetDisplayName = 5

	fosPickFolders     = 0x20
	fosForceFileSystem = 0x40
	sigdnFileSysPath   = 0x80058000
)

var (
	clsidFileOpenDialog = ole.NewGUID("{DC1C5A9C-E88A-4dde-A5A1-60F82A20AEF7}")
	iidFileOpenDialog   = ole.NewGUID("{d57c7288-d4ad-4768-be02-9d969532d960}")
	iidShellItem        = ole.NewGUID("{43826d1e-e718-42ee-bc55-a1e261c37bfe}")

	shell32                         = windows.NewLazySystemDLL("shell32.dll")
	procSHCreateItemFromParsingName = shell32.NewProc("SHCreateItemFromParsingName")

	errDialogCancelled = errors.New("cancelled")
)

func comCall(obj unsafe.Pointer, method int, args ...uintptr) error {
	table := *(*unsafe.Pointer)(obj)
	fn := *(*uintptr)(unsafe.Add(table, uintptr(method)*unsafe.Sizeof(uintptr(0))))
	hr, _, _ := syscall.SyscallN(fn, append([]uintptr{uintptr(obj)}, args...)...)
	if int32(hr) < 0 {
		if uint32(hr) == 0x800704C7 {
			return errDialogCancelled
		}
		return syscall.Errno(hr)
	}
	return nil
}

func chooseFolder(owner uintptr, title, start string) (string, error) {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	if err := ole.CoInitializeEx(0, ole.COINIT_APARTMENTTHREADED); err != nil {
		var oe *ole.OleError
		if !errors.As(err, &oe) || oe.Code() != 1 {
			return "", err
		}
	}
	defer ole.CoUninitialize()

	unknown, err := ole.CreateInstance(clsidFileOpenDialog, iidFileOpenDialog)
	if err != nil {
		return "", err
	}
	dialog := unsafe.Pointer(unknown)
	defer comCall(dialog, vtRelease)

	var options uint32
	if err := comCall(dialog, vtGetOptions, uintptr(unsafe.Pointer(&options))); err != nil {
		return "", err
	}
	if err := comCall(dialog, vtSetOptions, uintptr(options|fosPickFolders|fosForceFileSystem)); err != nil {
		return "", err
	}
	titlePtr, err := windows.UTF16PtrFromString(title)
	if err != nil {
		return "", err
	}
	if err := comCall(dialog, vtSetTitle, uintptr(unsafe.Pointer(titlePtr))); err != nil {
		return "", err
	}
	if path, err := windows.UTF16PtrFromString(start); err == nil {
		var item unsafe.Pointer
		hr, _, _ := procSHCreateItemFromParsingName.Call(uintptr(unsafe.Pointer(path)), 0, uintptr(unsafe.Pointer(iidShellItem)), uintptr(unsafe.Pointer(&item)))
		if int32(hr) >= 0 && item != nil {
			comCall(dialog, vtSetFolder, uintptr(item))
			comCall(item, vtRelease)
		}
	}
	if err := comCall(dialog, vtShow, owner); err != nil {
		return "", err
	}
	var result unsafe.Pointer
	if err := comCall(dialog, vtGetResult, uintptr(unsafe.Pointer(&result))); err != nil {
		return "", err
	}
	defer comCall(result, vtRelease)
	var name *uint16
	if err := comCall(result, vtGetDisplayName, sigdnFileSysPath, uintptr(unsafe.Pointer(&name))); err != nil {
		return "", err
	}
	defer windows.CoTaskMemFree(unsafe.Pointer(name))
	return windows.UTF16PtrToString(name), nil
}
