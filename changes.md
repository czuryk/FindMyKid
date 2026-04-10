# Changes Log

2026-03-31 01:57 - Исправление критического бага: сервис убивался системой сразу после старта
 - TrackingService: перенесён startForeground() из onStartCommand() в onCreate() — Android убивал сервис за невызов startForeground() в течение 5 сек
 - TrackingService: добавлена защита Throwable вокруг создания SettingsManager и трекеров в onCreate()
 - TrackingService: первая задача запускается с задержкой 3 сек для полной инициализации процесса
 - TrackingService: добавлено диагностическое логирование PID и ключевых точек выполнения
 - SettingsManager: catch (GeneralSecurityException | IOException) заменён на catch (Throwable) — KeyStore может бросать unchecked исключения при холодном старте
 - LocationTracker: location callbacks перенесены с main Looper на отдельный HandlerThread — устранён потенциальный deadlock при холодном старте
 - ServiceGuardWorker: добавлена проверка выживания сервиса через 5 сек после запуска

2026-03-31 02:25 - Исправление получения координат: GPS приоритет + Network fallback
 - LocationTracker: полная переработка — запрос GPS и Network параллельно, ожидание GPS 10 сек, если нет — берём Network
 - LocationTracker: добавлен класс LocationResult (location + source tag)
 - LocationTracker: добавлен PASSIVE_PROVIDER как третий fallback для lastKnownLocation
 - LocationTracker: getLastKnownLocationSafe перебирает ВСЕ провайдеры и выбирает самую свежую координату
 - LocationTracker: таймаут увеличен до 20 сек, GPS priority wait = 10 сек
 - NetworkSender: добавлен параметр locationSource, отправляется как location.source в JSON (GPS/NET/LAST_GPS/LAST_NET/LAST_PASSIVE)
 - TrackingService: прокинут locationSource через всю цепочку

2026-03-31 02:55 - Исправление: requestSingleUpdate не срабатывает для Network провайдера
 - LocationTracker: полная переработка — requestSingleUpdate() заменён на современные API
 - API 30+: используется getCurrentLocation() — надёжный one-shot API, рекомендованный Google
 - API <30: используется requestLocationUpdates() вместо deprecated requestSingleUpdate()
 - Добавлен Fused провайдер (объединяет GPS+сотовые+WiFi) как основной
 - lastKnownLocation теперь пробует: fused → gps → network → passive
 - TrackingService: добавлена авто-пересоздание locationTracker если null

2026-03-31 03:26 - КОРНЕВАЯ ПРИЧИНА: ACCESS_BACKGROUND_LOCATION не запрашивался
 - SettingsActivity: ACCESS_BACKGROUND_LOCATION не запрашивалось при нажатии кнопки Permissions
 - SettingsActivity: полная переработка requestPermissions() — двухэтапный процесс: сначала foreground, потом background
 - SettingsActivity: добавлен onRequestPermissionsResult для автоматической цепочки запросов
 - LocationTracker: добавлена диагностика — логирует статус BACKGROUND_LOCATION permission
 - ServiceWatchdogReceiver: интервал проверки изменён с 15 на 5 минут

2026-04-10 12:25 - UX: предварительная проверка пермиссий при Save Settings и включении сервиса
 - SettingsActivity: добавлен checkAllPermissionsGranted() — проверяет FINE_LOCATION, BACKGROUND_LOCATION, POST_NOTIFICATIONS, Location Services (GPS/Network), Usage Stats (AppOpsManager), Battery Optimization
 - SettingsActivity: добавлен getMissingPermissions() — возвращает список отсутствующих разрешений в читаемом виде
 - SettingsActivity: добавлен showMissingPermissionsDialog() — AlertDialog с кнопками Grant (вызывает requestPermissions()) и Cancel
 - SettingsActivity: Save Settings — после сохранения проверяет пермиссии, если чего-то не хватает — показывает диалог
 - SettingsActivity: Background Tracking switch — при включении СНАЧАЛА проверяет пермиссии, если не хватает — НЕ включает сервис и показывает диалог
 - SettingsActivity: добавлен isUsageStatsPermissionGranted() — проверка через AppOpsManager

2026-04-10 14:39 - Debug Log тогглер и стиль кнопки Test Configuration
 - Logger: при выключенном Debug Log логи НЕ пишутся в файлы на устройстве (logcat остаётся)
 - SettingsManager: добавлен debugLogEnabled (по умолчанию false/Off)
 - SettingsActivity: добавлен тогглер Debug Log в секции Logging & Diagnostics, работает мгновенно
 - Layout: кнопка Test Configuration — зелёный фон (#2E7D32) вместо outlined стиля

2026-04-10 14:42 - Косметика: иконка приложения на экранах Auth и Settings
 - activity_auth: иконка ic_launcher_round (96dp) по центру над надписью Authentication
 - activity_settings: мини-иконка (32dp) слева + текст "FindMyKid — Settings" вместо "Application config"

2026-04-10 14:51 - Иконка на Password Setup + исправление бага с Notification Permission
 - activity_password_setup: иконка ic_launcher_round (96dp) по центру над надписью Welcome!
 - BUG FIX: POST_NOTIFICATIONS запрашивался только вместе с location, если location уже был — notification никогда не запрашивался
 - requestPermissions(): notification теперь запрашивается ОТДЕЛЬНО (requestCode 102) независимо от location
 - requestPermissions(): Usage Stats и Battery Optimization settings открываются ТОЛЬКО если ещё не выданы

2026-04-10 14:58 - КОРНЕВАЯ ПРИЧИНА бага Notification Permission
 - BUG FIX: POST_NOTIFICATIONS отсутствовал в AndroidManifest.xml — checkSelfPermission() ВСЕГДА возвращал DENIED
 - AndroidManifest.xml: добавлен &lt;uses-permission android:name="android.permission.POST_NOTIFICATIONS" /&gt;

2026-04-10 15:04 - Последовательная цепочка permissions + цвет переключателя сервиса
 - BUG FIX: permissions запрашивались параллельно — Android показывает только один диалог, остальные терялись
 - requestPermissions(): полная переработка в последовательную цепочку: location → background → notification → system settings
 - Каждый шаг запускает следующий через onRequestPermissionsResult (requestCode 100 → 101 → 102)
 - Добавлены requestNotificationPermissionOrContinue() и openSystemSettingsIfNeeded()
 - Переключатель Background Tracking: зелёный (#10B981) когда ON, красный (#EF4444) когда OFF
 - Добавлены color state list ресурсы: switch_service_thumb.xml, switch_service_track.xml

2026-04-10 20:22 - Обновление targetSdk до 35 для публикации в Google Play
 - app/build.gradle: compileSdk 34→35, targetSdk 34→35
 - AndroidManifest.xml: tools:targetApi 34→35
