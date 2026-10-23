# Solegram: исходный код

Здесь лежит полный исходный код Solegram, неофициального клиента Telegram для Android, версии **2.2.1** на основе **Telegram 13.0.3**. Код выпущен под лицензией **GPL-2.0**, как и исходный код Telegram (см. `LICENSE`).

Solegram не связан с Telegram FZ-LLC. Оригинальный README Telegram лежит в `TELEGRAM_README.md`.

## Где что лежит

- `TMessagesProj/src/main/java/com/solegram/`: весь код Solegram (настройки, стекло, ИИ, фильтры, плагины, прокси, напоминания и т. д.).
- `TMessagesProj/src/main/java/org/telegram/`: код Telegram. Правки Solegram в нём отмечены комментариями `// Solegram:`.
- `solegram/strings/`: строки Solegram на русском и английском. Скрипт `solegram/tools/sync_strings.py` переносит их в ресурсы.

## Сборка

1. Установите JDK 17, Android SDK (API 36), NDK `27.2.12479018` и CMake.
2. Скачайте нативные зависимости точно тех версий, с которыми собирается Solegram (список в `SUBMODULES.txt`):

   ```sh
   ./get_submodules.sh
   ```

3. Создайте `local.properties` в корне:

   ```properties
   sdk.dir=/путь/к/Android/sdk
   # Свои ключи API Telegram: https://my.telegram.org → API development tools
   SOLEGRAM_API_ID=12345
   SOLEGRAM_API_HASH=0123456789abcdef0123456789abcdef
   # Подпись релизной сборки своим ключом
   SOLEGRAM_KEYSTORE=/путь/к/keystore.jks
   SOLEGRAM_STORE_PASSWORD=...
   SOLEGRAM_KEY_ALIAS=...
   SOLEGRAM_KEY_PASSWORD=...
   ```

   Ключи API и подписи в репозитории не хранятся, у каждого сборщика они свои. Файлы `TMessagesProj/config/release.keystore` и `google-services.json` — публичные заглушки из исходников Telegram.

4. Соберите APK:

   ```sh
   ./gradlew :TMessagesProj_App:assembleAfatRelease
   ```

   APK появится в `TMessagesProj_App/build/outputs/apk/afat/release/`.

Готовые APK, подписанные ключом Solegram, лежат в папке `apk/` в корне репозитория.
