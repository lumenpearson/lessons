# Сборка APK

## Через GitHub Actions

### Собрать прямо сейчас

**Actions → APK → Run workflow.** Ничего пушить не нужно.

| Поле | Что делает |
| --- | --- |
| `build_type` | `release` (по умолчанию), `debug` или `both` |
| `version_name` | что записать в versionName; пусто — значение из `build.gradle.kts` |

APK лежит в артефакте **lessons-apk** на странице прогона, хранится 90 дней.
`versionCode` всегда равен номеру прогона, поэтому две сборки невозможно
перепутать.

Обычный CI (`Actions → CI`) тоже собирает и debug, и release на каждом
пуше и pull request — там артефакты `app-debug` и `app-release-unsigned-key`.
Release собирается всегда, а не только при релизе: R8 и сжатие ресурсов — это
классический источник «в debug работало, а в установленном APK нет», и ловить
такое на pull request дешевле, чем на пользователях.

### Выпустить релиз

```bash
git tag v0.2.0
git push origin v0.2.0
```

Тег вида `v*` собирает release, создаёт GitHub Release с автоматическими
release notes и прикладывает APK. `versionName` берётся из тега без `v`.

## Подпись

Без настройки release-APK подписывается **debug-ключом**. Он ставится на
телефон, проходит через R8 — то есть годится для теста, — но публиковать его
нельзя: debug-ключ одинаковый у всех и не даёт никаких гарантий авторства.
Сводка прогона прямо пишет, каким ключом подписано.

Неподписанный APK не ставится вообще, поэтому запасной вариант — именно
debug-ключ, а не его отсутствие.

### Настроить свой ключ

1. Создайте хранилище (один раз, храните его вне репозитория и не теряйте —
   потерянный ключ означает, что обновить установленное приложение уже нельзя):

   ```bash
   keytool -genkey -v -keystore release.jks \
     -alias lessons -keyalg RSA -keysize 4096 -validity 10000
   ```

2. Закодируйте его в base64:

   ```bash
   base64 -w0 release.jks   # macOS: base64 -i release.jks
   ```

3. Добавьте четыре секрета в **Settings → Secrets and variables → Actions**:

   | Секрет | Значение |
   | --- | --- |
   | `KEYSTORE_BASE64` | вывод предыдущей команды |
   | `KEYSTORE_PASSWORD` | пароль хранилища |
   | `KEY_ALIAS` | `lessons` |
   | `KEY_PASSWORD` | пароль ключа |

Дальше всё автоматически. Workflow декодирует хранилище во временный каталог
рабочей машины, а не в рабочую копию, и удаляет его до того, как что-либо
загружается, — так ключ не может утечь через артефакт.

## Локально

```bash
cd android
./gradlew assembleDebug     # app/build/outputs/apk/debug/
./gradlew assembleRelease   # app/build/outputs/apk/release/
```

Чтобы подписывать локально своим ключом, положите в `~/.gradle/gradle.properties`
(файл вне репозитория, туда же, куда обычно кладут секреты):

```properties
lessons.keystore.file=/абсолютный/путь/release.jks
lessons.keystore.password=...
lessons.key.alias=lessons
lessons.key.password=...
```

Те же четыре значения читаются из переменных окружения
`LESSONS_KEYSTORE_FILE`, `LESSONS_KEYSTORE_PASSWORD`, `LESSONS_KEY_ALIAS`,
`LESSONS_KEY_PASSWORD` — это то, что использует CI.

## Что нужно из окружения

| | |
| --- | --- |
| JDK | 21 |
| Android SDK | compileSdk 37, minSdk 26 |
| Gradle | через wrapper, 9.5.0 |

Wrapper с jar лежит в репозитории, так что `./gradlew` работает на свежем
клоне без предустановленного Gradle.
