# ODRFID MNE для Android

Нативное приложение для считывателя ODRFID MNE. Приложение написано на Kotlin и Jetpack Compose, работает с CDC ACM через Android USB Host.

Для запуска требуется Android 7.0 (API 24) или новее и поддержка USB Host/OTG.

## Требования к сборке

- Android Studio с JDK 17;
- Android SDK Platform 37;
- Android SDK Build Tools 36.0.0;
- Gradle 9.5.0 (зафиксирован wrapper-ом);
- AGP 9.3.1 и Kotlin/Compose Compiler 2.3.21.

Локальный путь SDK задаётся в некоммитящемся `local.properties`:

```properties
sdk.dir=/home/user/Android/Sdk
```

Сборка и проверки:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
./gradlew connectedDebugAndroidTest
```

Последняя команда требует запущенного Android-устройства или эмулятора. APK создаётся в `app/build/outputs/apk/debug/`.

## Сборка APK в GitHub Actions

Workflow [Build APK](.github/workflows/build-apk.yml) запускается при push, pull request
и вручную через **Actions → Build APK → Run workflow**. Он устанавливает JDK 17
и необходимые компоненты Android SDK, кэширует Gradle и собирает debug APK
командой `:app:assembleDebug`.

После успешной сборки скачайте `odrfid-mne-debug-<номер запуска>` в разделе
**Artifacts** на странице запуска и распакуйте архив. Внутри находится
`app-debug.apk`, подписанный отладочным ключом; секреты для сборки не требуются.
Артефакт Actions хранится 14 дней.

Для постоянного хранения APK в **Releases** отправьте тег с префиксом `v`,
например, после коммита и push изменений:

```bash
git tag v1.0.0
git push origin v1.0.0
```

После успешной сборки workflow создаст GitHub Release для этого тега и приложит
`app-debug.apk`. У файла в Releases нет срока хранения 14 дней; удаление
артефактов Actions на него не влияет. Повторный запуск для того же тега
сохраняет уже опубликованный APK. Публикацию также можно повторить вручную,
запустив workflow для тега `v*`.

## Отладка при подключённом считывателе

Для диагностики через ADB можно освободить USB-порт планшета, переведя отладку на Wi-Fi.
На Android 7 сначала подключите планшет к компьютеру по USB, включите USB-отладку
и разрешите доступ этому компьютеру. Компьютер и планшет должны быть в одной локальной сети.

```bash
adb -d shell ip -4 addr show wlan0
adb -d tcpip 5555
adb connect <IP-планшета>:5555
```

После успешного подключения по сети можно отключить кабель компьютера и подключить
считыватель через OTG. Для команд ADB используйте `-s <IP-планшета>:5555`.

Лог команд и ответов доступен в меню приложения «Диагностический журнал»; ключи и пароли в нём скрыты.

## Подключение

Приложение принимает только USB-устройства `0483:A26A`, проверяет наличие CDC communication/data interfaces и явно открывает `CdcAcmSerialDriver` из встроенной в APK библиотеки `usb-serial-for-android:3.11.0`. HID-интерфейсы считывателя не открываются. Параметры порта: 115200, 8N1, flow control выключен.

После выдачи системного USB-разрешения выполняются `ATI`, `AT+SCAN0`, `AT+RF=1`, затем раз в секунду — `AT+i` и при необходимости `AT+S`. При таймауте или нарушении CRLF-фрейминга соединение закрывается, чтобы поздний ответ не мог стать ответом следующей команды.

## Возможности v1

- обнаружение HF/LF-карт, UID HEX, подходящие LF Decimal/Wiegand-представления;
- чтение MIFARE Classic/Plus, Ultralight/EV1/NTAG и поддерживаемой LF-памяти;
- сеансовые Key A/B, Ultralight PWD, MIFARE Plus AES и T55xx password — только в памяти процесса;
- точечная запись пользовательских HF-областей с pre-read, одной командой записи и независимым read-back;
- `LFCLASS FAST/FULL`, read-only fallback через `LFINFO`, T55xx/T5577/T5555 write с защитой P1/B0, password mode, lock и критическими фразами подтверждения;
- read-only режим для EM4x05/EM4x50;
- клонирование 4/7-байтного MIFARE UID на подтверждённую magic-карту и EM‑Marine на подтверждённую T5577/T55xx;
- компактный интерфейс «Карта»/«Память» и двухпанельный интерфейс от 600dp.

Не поддерживаются raw HID, NDEF, DFU, настройки считывателя, история, dump import/export, фоновый мониторинг и запись служебных HF-областей. DESFire и другие ISO 14443-4 карты отображаются без неподдерживаемого редактора.

## Архитектура

- `CdcTransport` — USB discovery, permission, attach/detach и CDC;
- `AtCommandClient` — одна последовательная очередь, строгий CRLF и typed responses;
- `ReaderRepository` — handshake, polling, память, write/clone safety flow;
- `ReaderViewModel` — единый `StateFlow` интерфейса и длительных операций.
