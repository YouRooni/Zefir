package main

import (
	"strings"

	"golang.org/x/sys/windows"
)

type texts struct {
	version, noAdmin, folder, desktop, install, update, cancel                             string
	installing, updating, cancelling, installed, updated, launch, closeBtn                 string
	removeTitle, removeHint, removeData, remove, removing, removed                         string
	runningTitle, runningHint, closeApp, errTitle, retry, errBusy, errNotOurs, errNoAccess string
	pickFolder                                                                             string
}

var translations = map[string]texts{
	"ru": {
		version: "Версия %s", noAdmin: "Права администратора не нужны", folder: "Папка установки", desktop: "Ярлык на рабочем столе",
		install: "Установить", update: "Обновить", cancel: "Отмена",
		installing: "Устанавливаем", updating: "Обновляем", cancelling: "Отменяем", installed: "Zefir установлен", updated: "Zefir обновлён", launch: "Запустить", closeBtn: "Закрыть",
		removeTitle: "Удалить Zefir?", removeHint: "Приложение будет удалено с этого компьютера", removeData: "Удалить настройки и профили", remove: "Удалить", removing: "Удаляем", removed: "Zefir удалён",
		runningTitle: "Zefir запущен", runningHint: "Его нужно закрыть, VPN при этом отключится", closeApp: "Закрыть", errTitle: "Не получилось", retry: "Назад",
		errBusy: "Файлы Zefir заняты. Закройте приложение и попробуйте снова.", errNotOurs: "В этой папке уже есть другие файлы. Выберите другую.", errNoAccess: "Нет доступа к папке. Выберите другую.",
		pickFolder: "Папка для Zefir",
	},
	"uk": {
		version: "Версія %s", noAdmin: "Права адміністратора не потрібні", folder: "Папка встановлення", desktop: "Ярлик на робочому столі",
		install: "Встановити", update: "Оновити", cancel: "Скасувати",
		installing: "Встановлюємо", updating: "Оновлюємо", cancelling: "Скасовуємо", installed: "Zefir встановлено", updated: "Zefir оновлено", launch: "Запустити", closeBtn: "Закрити",
		removeTitle: "Видалити Zefir?", removeHint: "Застосунок буде видалено з цього комп'ютера", removeData: "Видалити налаштування та профілі", remove: "Видалити", removing: "Видаляємо", removed: "Zefir видалено",
		runningTitle: "Zefir запущено", runningHint: "Його треба закрити, VPN при цьому вимкнеться", closeApp: "Закрити", errTitle: "Не вийшло", retry: "Назад",
		errBusy: "Файли Zefir зайняті. Закрийте застосунок і спробуйте ще раз.", errNotOurs: "У цій папці вже є інші файли. Виберіть іншу.", errNoAccess: "Немає доступу до папки. Виберіть іншу.",
		pickFolder: "Папка для Zefir",
	},
	"be": {
		version: "Версія %s", noAdmin: "Правы адміністратара не патрэбныя", folder: "Папка ўсталёўкі", desktop: "Ярлык на працоўным стале",
		install: "Усталяваць", update: "Абнавіць", cancel: "Скасаваць",
		installing: "Усталёўваем", updating: "Абнаўляем", cancelling: "Скасоўваем", installed: "Zefir усталяваны", updated: "Zefir абноўлены", launch: "Запусціць", closeBtn: "Закрыць",
		removeTitle: "Выдаліць Zefir?", removeHint: "Праграма будзе выдалена з гэтага камп'ютара", removeData: "Выдаліць налады і профілі", remove: "Выдаліць", removing: "Выдаляем", removed: "Zefir выдалены",
		runningTitle: "Zefir запушчаны", runningHint: "Яго трэба закрыць, VPN пры гэтым адключыцца", closeApp: "Закрыць", errTitle: "Не атрымалася", retry: "Назад",
		errBusy: "Файлы Zefir занятыя. Закрыйце праграму і паспрабуйце зноў.", errNotOurs: "У гэтай папцы ўжо ёсць іншыя файлы. Выберыце іншую.", errNoAccess: "Няма доступу да папкі. Выберыце іншую.",
		pickFolder: "Папка для Zefir",
	},
	"en": {
		version: "Version %s", noAdmin: "No administrator rights needed", folder: "Install folder", desktop: "Desktop shortcut",
		install: "Install", update: "Update", cancel: "Cancel",
		installing: "Installing", updating: "Updating", cancelling: "Cancelling", installed: "Zefir is installed", updated: "Zefir is updated", launch: "Launch", closeBtn: "Close",
		removeTitle: "Remove Zefir?", removeHint: "The app will be removed from this computer", removeData: "Also remove settings and profiles", remove: "Remove", removing: "Removing", removed: "Zefir is removed",
		runningTitle: "Zefir is running", runningHint: "It has to close, and the VPN will disconnect", closeApp: "Close it", errTitle: "Something went wrong", retry: "Back",
		errBusy: "Zefir files are in use. Close the app and try again.", errNotOurs: "This folder already has other files. Choose another one.", errNoAccess: "No access to this folder. Choose another one.",
		pickFolder: "Folder for Zefir",
	},
	"zh": {
		version: "版本 %s", noAdmin: "无需管理员权限", folder: "安装文件夹", desktop: "桌面快捷方式",
		install: "安装", update: "更新", cancel: "取消",
		installing: "正在安装", updating: "正在更新", cancelling: "正在取消", installed: "Zefir 已安装", updated: "Zefir 已更新", launch: "启动", closeBtn: "关闭",
		removeTitle: "卸载 Zefir？", removeHint: "应用将从这台电脑上移除", removeData: "同时删除设置和配置文件", remove: "卸载", removing: "正在卸载", removed: "Zefir 已卸载",
		runningTitle: "Zefir 正在运行", runningHint: "需要关闭它，VPN 将断开", closeApp: "关闭", errTitle: "出错了", retry: "返回",
		errBusy: "Zefir 文件被占用。请关闭应用后重试。", errNotOurs: "此文件夹已有其他文件，请另选一个。", errNoAccess: "无法访问此文件夹，请另选一个。",
		pickFolder: "Zefir 文件夹",
	},
}

func systemLang() string {
	langs, err := windows.GetUserPreferredUILanguages(windows.MUI_LANGUAGE_NAME)
	if err != nil {
		return "en"
	}
	for _, l := range langs {
		code, _, _ := strings.Cut(strings.ToLower(l), "-")
		if _, ok := translations[code]; ok {
			return code
		}
	}
	return "en"
}
