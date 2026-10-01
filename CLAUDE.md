# Tik Tak — инструкция для Claude (прочитай первой)

Android-приложение «Tik Tak» — клон TickTick, выросший из «Обратного отсчёта».
Kotlin 2.0, Jetpack Compose, Room, Flow/ViewModel, ручной DI (`AppContainer`), Glance-виджеты.
Статус на 2026-10-01: выпущен `build-33` (Tik Tak 2.4): события отдельно от задач (база v5), календарь
в стиле Google, виджет «Расписание», объединение дублей при синхронизации, ускорение (BACKLOG A5).
Ранее 2.3 (`build-29`): новый логотип и выбор цвета значка,
временная шкала календаря, раздел «Статистика», таймер «Стоп/Сбросить» и будильник, категории у флажка.
Ранее 2.2 (`build-24`): синхронизация через Supabase и резервная копия (база v4), панель с «Ещё»,
свои цвета виджетов, сортировка, анимации (`ui/Motion.kt`); 2.1 (`build-19`): корзина, навигация как
в TickTick, календарь «3 дня»/«Год», цвет задач, события-отсчёты. `tasks` = `main`. Что делать дальше — в [BACKLOG.md](BACKLOG.md), раздел «Следующая сессия».

## Как работать с пользователем

- Пишет и читает по-русски; не Android-разработчик.
- Хочет автономной работы: не спрашивать по каждому решению, делать правильно самому.
  Цикл на каждый шаг: сделать → собрать → тесты → проверить на эмуляторе (скриншоты) →
  коммит → push в `tasks` → дождаться зелёного CI → коротко отчитаться, что изменено и как проверить.
- Спрашивать только про то, что уходит на телефоны пользователей (релиз из `main`).

## Git и релизы

- Работа идёт в ветке `tasks`. Push в `tasks` безопасен: CI собирает только APK-артефакт.
- Push в `main` = релиз: CI публикует `build-N`, и приложения сами предлагают обновиться.
  Claude не может пушить в `main` (auto-mode классификатор блокирует это как production deploy).
  Когда этап готов к релизу — дать пользователю команды:
  `git checkout main`, `git merge --no-ff tasks -m "…"`, `git push origin main`
  (сообщение коммита становится описанием релиза), затем проверить CI и релиз через GitHub API
  (`/repos/dstpnklen20-hue/countdown-widget/actions/runs?branch=main`, `/releases/latest`).
  После релиза вернуться на `tasks` и сделать `git merge --ff-only main`.
- `versionCode` = номер запуска CI (`github.run_number`), локальные сборки имеют код 1
  (автообновление для них отключено).
- `job_log.txt` в корне — файл пользователя, не коммитить и не удалять.

## Нельзя менять (иначе обновление не встанет или пропадут данные/виджеты)

- `applicationId = com.claudecode.countdown` и `namespace`; ключ подписи (в GitHub Secrets).
- Имена классов `MainActivity` и `widget.CountdownWidgetProvider`.
- Значок приложения (`AppIcon.kt`): цветовые варианты — `activity-alias` `.IconLight` … `.IconViolet`
  к `MainActivity`; включён ровно один вход (MainActivity = «Классика»). Алиасы НЕ удалять и не
  переименовывать (у кого-то он единственный вход в приложение). Открывать приложение только через
  `MainActivity.launchIntent()`/`openTaskIntent()`: прямой Intent на выключенную MainActivity падает.
- Имя репозитория GitHub `countdown-widget` (на него смотрит `Updater`).
- Никогда `fallbackToDestructiveMigration`; старые SharedPreferences (`countdowns_data`,
  `countdowns_widget_map`) не удалять — это резервная копия данных версии 1.x.

## Локальная среда (Windows)

- JDK 17 и Android SDK установлены в `F:\AndroidDev` (`jdk-17*`, `sdk`, AVD `tiktak` в `F:\AndroidDev\avd`).
  `local.properties`: `sdk.dir=F:/AndroidDev/sdk` (прямые слэши!). Системный JDK 26 для Gradle 8.7 не подходит.
- Сборка/тесты: задать `JAVA_HOME` на `F:\AndroidDev\jdk-17*`, затем
  `gradlew :core:test :app:testDebugUnitTest assembleDebug` (иногда полезно `lintDebug`).
  Если Bash-инструмент отказывает (сбой классификатора), то же самое работает через PowerShell.
- Эмулятор: `ANDROID_SDK_ROOT=F:/AndroidDev/sdk ANDROID_AVD_HOME=F:/AndroidDev/avd
  F:/AndroidDev/sdk/emulator/emulator.exe -avd tiktak -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect`
  (WHPX работает). В Git Bash для adb ставить `MSYS_NO_PATHCONV=1`, иначе пути `/data/...` портятся.
  `adb root` даёт `sqlite3` к `/data/data/com.claudecode.countdown/databases/tiktak.db`.
  Нажатия по тексту: `uiautomator dump` + поиск `text=`/`content-desc=` → `input tap` по центру bounds.
  `adb input text` не умеет кириллицу — проверять английскими фразами.
- Проверка обновления со старой версии: собрать коммит `375de6b` в отдельном worktree, установить,
  положить prefs старого формата, затем поставить новую сборку поверх.

## Архитектура (где что лежит)

- `core/` (чистый Kotlin, быстрые тесты): `RepeatRule` (RRULE: FREQ/INTERVAL/BYDAY/BYMONTHDAY/COUNT/UNTIL),
  `RepeatText` (описания по-русски), `QuickAddParser` (RU/EN: даты, время, повторы, `!приоритет`, `#тег`, `~список`).
- `app/.../data/db/`: `Entities.kt` (у синхронизируемых сущностей id UUID, createdAt, updatedAt, deleted),
  `Daos.kt`, `AppDatabase.kt` (версия 5), `Migrations.kt`, `DatabaseSeeder.kt` (Inbox + импорт отсчётов 1.x).
- `app/.../data/`: `TaskRepository` (единая точка записи задач; `onChanged` → виджеты и будильник),
  `HabitRepository`/`FocusRepository`, `CountdownRepository` (мост для старых View-экранов).
- `app/.../domain/`: умные списки и группировка, повторы задач, напоминания, проекция календаря, статистика привычек.
- События и задачи: одна таблица `tasks`; `isEvent` = событие (сон, обед: `startAt`..`dueAt`, без галочки, не в списках
  и матрице, `matches()`), `displayMode = COUNTDOWN` = отсчёт. Календарь берёт отрезки по дням из `calendarEntries`
  (`CalendarEntry.start/end/part`, сон через полночь — на двух днях), раскладка блоков — `layoutBlocks` (lane + depth).
  Экраны: `ui/calendar/` (`CalendarScreen` — режимы и мини-месяц, `TimeGrid`, `Schedule` — расписание и картинки
  месяцев), создание и время событий — `ui/EventEditor.kt`.
- `app/.../ui/`: Compose-экраны (tasks, detail, calendar, matrix, focus, habits, settings), `Theme.kt`
  (мост к `ThemeManager`), `Navigation.kt` (панель: снизу на телефоне с «Ещё», колонка на планшете),
  `Motion.kt` (общие параметры анимаций — брать их, а не свои tween), `EmptyArt.kt` (картинки пустых
  списков), `Undo.kt` (общий Snackbar «Отменить»).
- `data/AppSettings.kt`: настройки приложения (StateFlow): закреплённые разделы `Tool` и лимит панели
  (`barLayout`), сортировка по спискам, картинка пустого списка. Тема — в `ThemeManager` (у виджетов
  может быть своя: `Target.WIDGETS`); после смены темы вызывать `container.refreshAllWidgets()`.
- `data/sync/`: `RowStore` (строки любой таблицы как JSON, слияние «новее побеждает»), `Backup` (файл),
  `SyncEngine` (отправить изменения с `updatedAt` ≥ отметки − 10 с, забрать с сервера по `server_updated_at`
  с перекрытием 60 с), `Supabase.kt` (HTTP: вход/обновление токена, таблица `sync_records`), `SyncManager`
  (сессия в prefs `sync`, запуск через 4 с после записи в базу — следит сам через InvalidationTracker,
  при открытии и раз в час через WorkManager), `Dedupe.kt` (после pull объединяет копии одного и того же с разных
  устройств по содержимому; первая синхронизация версии — полная чистка). Сервер: `supabase/schema.sql`, проект `bjwpdcenqeckobvtpvwy`,
  подтверждение почты выключено. Новая синхронизируемая таблица = добавить в `SyncTable` (нужны
  `updatedAt` и `deleted`). Удалённое навсегда — через `sync.forget()`. Для проверок на эмуляторе
  на сервере заведён тестовый аккаунт `emulator-test@example.org` (пароль в репозиторий не пишем).
- `reminders/` (один точный будильник + «водяной знак» доставленного), `pomodoro/` (состояние в prefs + будильник),
  `widget/` (RemoteViews-отсчёт, Glance «Сегодня», «Расписание» и «Быстро добавить»), `BootReceiver` (перезагрузка/время/обновление).
- Всё ещё на старом View: `EditCountdownActivity`, `widget/WidgetConfigureActivity` (`SettingsActivity` удалён).

## Изменение схемы БД

Поднять `version`, собрать (`kspDebugKotlin`) → появится `app/schemas/.../N.json`; скопировать оттуда
`createSql` в новый `Migration` в `Migrations.kt`, добавить в `ALL_MIGRATIONS`; дополнить `MigrationTest`
(он строит настоящую базу старой версии из json-схемы и открывает её Room — проверено, что ловит ошибки).
Проверить на эмуляторе обновление поверх установленной версии.

## Грабли, на которые уже наступали

- Glance: данные наблюдать через Flow ВНУТРИ `provideContent`, иначе `update()` не обновит живую сессию.
- После обновления APK Glance-виджеты висят на загрузке — `BootReceiver` на `MY_PACKAGE_REPLACED` вызывает `redrawWidgets()`.
- `FocusRequester.requestFocus()` в `ModalBottomSheet`/диалоге — только после `awaitFrame()` и в `runCatching`.
- `LazyRow` держит позицию по ключу видимого элемента: вставка слева уходит за экран.
- Robolectric не видит assets из `test` — поэтому свой `MigrationTest` вместо `MigrationTestHelper`.
- Debug-сборка (её и публикует CI) не debuggable (`isDebuggable = false`, иначе Compose тормозит) и
  полностью оптимизируется R8 с `-dontobfuscate`; при рефлексии добавить keep-правила и прогнать все экраны.
- Зависимости закреплены под compileSdk 34 (AGP 8.5.2); обновление библиотек потребует compileSdk 35.
- `SharedPreferences.commit()` в сидере и планировщике намеренный (флаги должны записаться синхронно).

## Бэклог

Весь список дел — в [BACKLOG.md](BACKLOG.md): идеи пользователя (главный приоритет, пункты с ❓ сначала
уточнить) и технические доработки. Новые идеи пользователя добавлять туда же, отмечать сделанное.
