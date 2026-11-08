<p align="center">
  <img src="docs/assets/zefir.svg" width="88" height="88" alt="Логотип Zefir">
</p>

<h1 align="center">Zefir</h1>

<p align="center">Чистый и выразительный VPN-клиент.<br>Быстрый, приватный и современный.</p>

<p align="center">
  <a href="README.md">English</a> · Русский · <a href="README.zh-CN.md">简体中文</a>
</p>

<p align="center">
  <a href="https://github.com/YouRooni/Zefir/releases">Релизы</a> ·
  <a href="docs/README.md">Документация</a> ·
  <a href="https://github.com/YouRooni/Zefir/issues">Обратная связь</a>
</p>

Подключайте свои серверы и подписки. Zefir управляет соединением, выбором сервера, DNS и маршрутизацией. Доступ к VPN приложение не продаёт.

## Возможности

- **Импорт:** Clash YAML, Base64-подписки, ссылки на серверы и конфигурации WireGuard / AmneziaWG.
- **Подключение:** профили, выбор сервера и проверка задержки. VLESS, VMess, Trojan, Shadowsocks, Hysteria, Hysteria 2, TUIC, WireGuard и AmneziaWG через mihomo.
- **Маршрутизация:** настройки DNS и правила трафика, выбор приложений на Android, TUN и системный прокси на Windows и Linux.
- **Диагностика:** активные соединения, трафик, журналы и проверка сети. Экспорт и восстановление настроек.

Интерфейс доступен на русском, английском, упрощённом китайском, украинском и белорусском.

## Начало работы

Zefir находится в бете. Готовые сборки доступны в [Releases](https://github.com/YouRooni/Zefir/releases), либо приложение можно [собрать из исходников](docs/BUILDING.md).

| Платформа | Требования |
| --- | --- |
| Android | Android 8.0 или новее |
| Windows | Windows 10/11, x64; Java входит в готовую сборку |
| Linux | x64, системный прокси GNOME или KDE; Java входит в готовую сборку |

1. Установите APK на Android, распакуйте портативную Windows-сборку и запустите `Zefir.exe` или распакуйте архив для Linux и запустите `Zefir/bin/Zefir`.
2. Добавьте URL подписки, импортируйте файл конфигурации или вставьте ссылку на сервер.
3. Выберите сервер и подключитесь. Android запросит разрешение на VPN, а TUN на Windows требует прав администратора.

Выбор сборки, расположение данных и решение проблем описаны в [руководстве пользователя](docs/USAGE.md) на английском.

## Разработка

Интерфейс и логика приложения написаны на Kotlin с Compose Multiplatform. Сетевое ядро mihomo подключено через нативную обёртку FlClash.

[Сборка](docs/BUILDING.md) · [Архитектура](docs/ARCHITECTURE.md) · [Внесение изменений](CONTRIBUTING.md) · [Выпуск релиза](docs/RELEASING.md)

## Данные и поддержка

Нет аккаунтов Zefir, встроенной аналитики и автоматической отправки ошибок. Сетевые запросы зависят от используемых функций и провайдеров: [подробности](docs/PRIVACY.md).

Об ошибках пишите в [Issues](https://github.com/YouRooni/Zefir/issues). Об уязвимостях сообщайте лично в [Telegram @YouRooni](https://t.me/YouRooni) или на [studio.rooni@gmail.com](mailto:studio.rooni@gmail.com), подробнее в [SECURITY.md](SECURITY.md). Перед отправкой журналов и скриншотов удалите URL подписок, токены и пароли.

## Лицензия

[GNU GPL v3.0](LICENSE). Copyright © 2026 Rooni and contributors. Без гарантий. Лицензии и авторство стороннего кода перечислены в [NOTICE](NOTICE).

Author: [YouRooni.t.me](https://t.me/YouRooni)
