This is a Kotlin Multiplatform project targeting Android, iOS.

* [/iosApp](./iosApp/iosApp) contains an iOS application. Even if you’re sharing your UI with Compose Multiplatform,
  you need this entry point for your iOS app. This is also where you should add SwiftUI code for your project.

* [/shared](./shared/src) is for code that will be shared across your Compose Multiplatform applications.
  It contains several subfolders:
  - [commonMain](./shared/src/commonMain/kotlin) is for code that’s common for all targets.
  - Other folders are for Kotlin code that will be compiled for only the platform indicated in the folder name.
    For example, if you want to use Apple’s CoreCrypto for the iOS part of your Kotlin app,
    the [iosMain](./shared/src/iosMain/kotlin) folder would be the right place for such calls.
    Similarly, if you want to edit the Desktop (JVM) specific part, the [jvmMain](./shared/src/jvmMain/kotlin)
    folder is the appropriate location.

### Running the apps

Use the run configurations provided by the run widget in your IDE's toolbar. You can also use these commands and options:

- Android app: `./gradlew :androidApp:assembleDebug`
- iOS app: open the [/iosApp](./iosApp) directory in Xcode and run it from there.

### Running tests

Use the run button in your IDE's editor gutter, or run tests using Gradle tasks:

- Android tests: `./gradlew :shared:testAndroidHostTest`
- iOS tests: `./gradlew :shared:iosSimulatorArm64Test`

### Debug visitor scenario

Android debug uses Ktor MockEngine, with paid sales already active for event 101
(the concert). Release uses the real API. The `visitorCheckoutReady` mock option
only prepares this fixture; newly created events still require activation.

Built-in accounts: `visitor@biletflow.kz`, `empty@biletflow.kz` and
`history-offline@biletflow.kz`, password `password123`. The last account simulates
a history network error. The second QR of an order fails once, then succeeds on
Retry. Event 104 also fails its first ticket-list request, then returns an empty list.

Registered mock accounts now own their orders, including after token rotation.
The mock backend itself remains in memory: registration, newly created orders
and rotated-token aliases reset when the process is terminated. For the session
restoration check across process launches use the built-in visitor account with
its stable initial tokens and seeded history. This is a debug-server limitation,
not persistent offline order storage.

---

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html)…

### Сетевые окружения и афиша — шаг 52A

Выбор источника больше не привязан к BuildConfig.DEBUG / #if DEBUG. Значение по умолчанию: debug — mock, release — staging; явная настройка имеет приоритет. Окружение меняется перед запуском приложения (Android — пересборка, iOS — переменные scheme), без переключения внутри живой сессии.

Android (из корня проекта):

```powershell
.\gradlew.bat :androidApp:assembleDebug '-Pbiletflow.environment=mock'
.\gradlew.bat :androidApp:assembleDebug '-Pbiletflow.environment=local'
# Для физического телефона укажите доступный LAN URL компьютера:
.\gradlew.bat :androidApp:assembleDebug '-Pbiletflow.environment=local' '-Pbiletflow.localUrl=http://192.168.1.10:8000'
# Staging: дополнительно передайте -Pbiletflow.stagingUrl=<подтверждённый HTTPS base URL>
```

local по умолчанию: Android Emulator http://10.0.2.2:8000; iOS Simulator http://127.0.0.1:8000. iOS: в Xcode Scheme → Run → Environment Variables задать BILETFLOW_ENV=mock|local|staging; опционально BILETFLOW_LOCAL_URL и обязательно для staging BILETFLOW_STAGING_URL. Release по умолчанию ждёт staging URL. Подтверждённый staging URL в проекте отсутствует: без него показывается ошибка конфигурации, а не мок или подставной сервер. Публичный адрес из deploy.md не признан подтверждённым, поскольку его /health и /events вернули 404.

Android Keystore/зашифрованный файл и iOS Keychain переиспользуются. Namespace теперь кодирует окружение и полный base URL; local на разных портах и разные staging-серверы не разделяют токены. Старые неоднозначные записи namespace api и прежние mock-записи автоматически не переносятся — потребуется новый вход. Режим выбирается только при запуске; это исключает перенос ViewModel между серверами. local/staging всегда используют настоящий Ktor, без fallback при сетевой ошибке. Request/socket timeout — 75 секунд, connect — 10 секунд для холодного старта Render.

Афиша читает GET /events → {items,total}, limit=20 / offset; поиск q с debounce 300 мс и категория идут на сервер. Чипы загружаются из GET /events/categories. Изменение фильтра начинает offset=0; устаревшие ответы игнорируются. Следующая страница при ошибке сохраняет карточки и повторяет тот же offset; ID дедуплицируются. Фильтры и ScrollState сохраняются в App при уходе в детали и переключении вкладки. Сброс прокрутки выполняется только при изменении фильтров. Новые поля Event (image_url, lifecycle, suspended_at, suspension_reason) принимаются; загрузка изображений не добавляется этим шагом, остаётся нейтральная обложка.

MockEngine возвращает контрактный JSON страниц, фильтрует весь набор по q (название/описание/площадка/адрес) и category, выдаёт категории и считает total до pagination. В приложении mock-набор включает 29 событий: «Дальний спектакль» расположен за первой страницей и находится серверным поиском. В остальных mock-сценариях/тестах дополнительные события включаются явно через catalogPageFixtures. Мок использует фиксированный момент 2026-11-25T00:00:00Z для проверки прошедших событий, не системное время.

Настоящее подключение пока не подтверждено: HTTPS https://biletflow-api.onrender.com/health и /events?limit=1&offset=0 вернули HTTP 404 {"detail":"Not Found"}; http://127.0.0.1:8000/health — соединение отклонено. Без доступного сервера UI-проверка настоящего каталога невозможна. /auth/me и остальные несовместимые блоки будут мигрировать отдельно; старые предложения API не используются для новой афиши.

Проверки 52A: все 69 host-тестов прошли, Android assembleDebug/assembleRelease успешны. EventsCatalogTest содержит 5 целевых тестов: контракт HTTP/несколько страниц/total/поиск за первой страницей/категории/пустая выдача; ошибки первой и следующей страниц с тем же offset и сохранением карточек; debounce/запоздалый ответ/дедупликация; отдельные namespace серверов; поздние категории не затирают страницу. Найденная гонка исправлена: категории сначала ожидаются в локальной переменной, затем сливаются с текущим состоянием. Дополнительно успешна сборка assembleDebug с '-Pbiletflow.environment=local'. iOS-код обновлён, но сборка iOS на Windows недоступна. Ручная UI-проверка афиши не выполнялась; реальная интеграция и UI настоящего каталога не подтверждены из-за ответов сервера выше.

Изменённые файлы этого шага: README.md; androidApp/build.gradle.kts; androidApp/src/main/kotlin/com/example/biletflow/MainActivity.kt; iosApp/iosApp/ContentView.swift; shared/src/commonMain/kotlin/com/example/biletflow/App.kt; core/network/ApiConfig.kt и MockEventsHttpClient.kt; data/repositories/EventsRepositoryImpl.kt; domain/models/Event.kt; domain/repositories/EventsRepository.kt; presentation/events/EventsViewModel.kt и EventCatalogUi.kt (пути последних файлов относительно shared/src/commonMain/kotlin/com/example/biletflow); shared/src/commonMain/composeResources/values/strings.xml и values-ru/strings.xml; shared/src/commonTest/kotlin/com/example/biletflow/EventsCatalogTest.kt; shared/src/androidMain/kotlin/com/example/biletflow/core/network/ApiConfig.android.kt и HttpClientFactory.android.kt; shared/src/iosMain/kotlin/com/example/biletflow/MainViewController.kt; shared/src/iosMain/kotlin/com/example/biletflow/core/network/ApiConfig.ios.kt и HttpClientFactory.ios.kt. Существующие настройки Gradle сохранены; shared/build.gradle.kts не менялся.

### Текущий пользователь и блокировка — шаг 52B

Контракт сверён с `C:\Users\Bekzat\Downloads\BiletFlow-main\docs\api-reference.md`, `auth-and-roles.md`, `backend/schemas/user.py` и реализацией backend. Защищённый GET /auth/me возвращает id, email, email_verified, is_platform_admin, organizer (id, user_id, status либо null), is_event_staff. Роли независимы: покупка доступна организатору, проверка входа — назначенному сотруднику или approved-организатору. Владение сверяется с organizer.id, а не user.id. Доступ к конкретному событию остаётся за API и /staff/events.

CurrentUserViewModel загружает профиль после login, восстановления токенов, ON_RESUME и открытия профиля. После успешной заявки выполняется новый /auth/me, отменяющий прежнюю загрузку. Проверка организатора и payout-account сохраняют собственные endpoints. Восстановление токенов, загрузка профиля, полученный пользователь и ошибка загрузки — отдельные состояния. При ошибке профиля токены сохраняются, появляется сообщение с повтором, публичная афиша остаётся доступной. Email берётся из серверного профиля; после чтения он также обновляется в существующей записи токенов.

GET /auth/me использует общий single-flight refresh и один повтор после явного 401. AuthApiException сохраняет error; поддерживаются error/message и прежний detail. Общий валидатор HTTP сохраняет UserSuspendedError даже для старых экранов, которые отбрасывают тело ошибки. SessionOrdersRepository завершает только сессию, получившую 403 UserSuspendedError: очищает память, постоянную запись и защищённые экраны; интерфейс показывает сообщение о блокировке и гостевую афишу. Другие 403 не завершают сессию. Проверка поколения до обработки ошибки и перед применением результата исключает влияние старого ответа на новый аккаунт. Обычная ротация не сбрасывает пользователя или экран. Пароль и токены не логируются. Разделение постоянных записей по окружению/base URL из 52A сохранено; Gradle на этом шаге не изменялся.

Debug MockEngine:

- visitor@biletflow.kz / password123: обычный пользователь, id=100, email_verified=true; после заявки /auth/me показывает pending, третий GET профиля организатора переводит в approved.
- Назначение пользователя на опубликованное мероприятие отражается в is_event_staff, без требования роли организатора. Собственные мероприятия сами по себе не устанавливают staff-флаг; доступ владельца учитывается отдельно.
- Зарегистрировать me-offline@biletflow.kz: первый /auth/me имитирует обрыв сети, ручной повтор успешен.
- Зарегистрировать suspend-on-me@biletflow.kz: /auth/me возвращает 403 UserSuspendedError.
- Зарегистрировать suspend-on-orders@biletflow.kz: профиль доступен, GET /orders/me блокирует аккаунт. Последующие защищённые запросы тоже отклоняются; повторный login с верным паролем получает 403.
- suspended@biletflow.kz / password123: блокировка непосредственно на login; неверный пароль — 401.
- Зарегистрированный admin@biletflow.kz получает is_platform_admin=true. Это флаг профиля, административные экраны в этот шаг не входят.

Mock-пользователи, назначения и заявки по-прежнему живут в памяти процесса. Проверка полного перезапуска выполнена со стандартным visitor, известным новому экземпляру MockEngine. В production профиль каждый раз читается с сервера, а не из локального снимка ролей.

Автоматические проверки: CurrentUserTest — 6 целевых тестов (login/восстановление/refresh с сохранением поколения; сетевой сбой и повтор; suspension из /auth/me и /orders/me с очисткой постоянной записи; старый ответ и прочие 403; совмещение ролей; обновление после заявки и обычный сотрудник). Существующие SessionRefreshTest и SessionStorageTest дополнительно проверяют single-flight, один повтор, сетевой сбой refresh, старый refresh и гонку записи с выходом. Выполнены :shared:testAndroidHostTest (75 тестов), :androidApp:assembleDebug, :androidApp:assembleRelease. Целевой прогон --tests '*CurrentUserTest' также успешен.

UI-проверки через ADB/UIAutomator: Resizable_Experimental, emulator-5554, Android 16, mock debug. Выполнены вход visitor → серверные email/ID/email_verified; заявка → pending → обновление → approved с действиями организатора и проверки входа; выход; регистрация suspend-on-orders → вход → профиль → история → сообщение о блокировке; полный force-stop/запуск → гостевая афиша и гостевой профиль; новый вход visitor → force-stop/запуск → восстановленный профиль с серверными полями. Для обычного восстановления organizer снова отсутствует, поскольку mock-хранилище заявок сбрасывается с процессом. Первый запуск ADB в sandbox не удался; запуск с расширенными разрешениями успешен, автоматического отказа разрешений не было. Кратковременные null root node от UIAutomator после запуска устранены повторным чтением, сценарий завершён. iOS не собирался и не проверялся на Windows.

Реальный API: 25.11.2026 безопасные GET https://biletflow-api.onrender.com/health и https://biletflow-api.onrender.com/events?limit=1&offset=0 вернули HTTP 404 с {"detail":"Not Found"}; http://127.0.0.1:8000/health — соединение отклонено. Проверка вне sandbox дала эти результаты; ограничения sandbox первоначально мешали DNS/сокету. Подтверждённый staging URL остаётся открытым пунктом. Реальный авторизованный /auth/me и suspension не проверены; проверенная интеграция — MockEngine. Старые блоки API за пределами /auth/me на этом шаге не мигрировались.

Файлы шага 52B (остальные существующие изменения рабочей копии сохранены):

- README.md.
- shared/src/commonMain/kotlin/com/example/biletflow/App.kt.
- shared/src/commonMain/kotlin/com/example/biletflow/domain/models/CurrentUser.kt (новый).
- shared/src/commonMain/kotlin/com/example/biletflow/domain/session/SessionStore.kt.
- shared/src/commonMain/kotlin/com/example/biletflow/presentation/auth/CurrentUserViewModel.kt (новый), AuthViewModel.kt, AuthUi.kt.
- shared/src/commonMain/kotlin/com/example/biletflow/data/repositories/AuthRepositoryImpl.kt, SessionOrdersRepository.kt.
- shared/src/commonMain/kotlin/com/example/biletflow/core/network/SessionErrorValidation.kt (новый), MockEventsHttpClient.kt.
- shared/src/commonMain/kotlin/com/example/biletflow/presentation/organizer/OrganizerViewModel.kt, MyEventsViewModel.kt.
- shared/src/androidMain/kotlin/com/example/biletflow/core/network/HttpClientFactory.android.kt.
- shared/src/iosMain/kotlin/com/example/biletflow/core/network/HttpClientFactory.ios.kt.
- shared/src/commonMain/composeResources/values/strings.xml и values-ru/strings.xml.
- shared/src/commonTest/kotlin/com/example/biletflow/CurrentUserTest.kt (новый).

### Полный список типов билетов организатора — шаг 52C

Источник контракта: актуальный `BiletFlow-main/docs/api-reference.md`, `backend/routers/ticket_types.py`, `backend/services/ticket_type_service.py`, `backend/repositories/ticket_type_repository.py`. Реализованный backend предоставляет GET /events/{event_id}/ticket-types/manage → List<TicketType>, без пагинации, по возрастанию ID, включая скрытые типы для draft/published/cancelled. Права проверяет backend через approved organizer profile и принадлежность event.organizer_id. Прежние предложенные GET /organizer/events/{id}/ticket-types и GET /organizer/events/{id}/ticket-types/{ticketId} удалены из этого клиентского блока и MockEngine. TicketTypePage/next_cursor/limit/cursor здесь больше не используются. Для проверки одного типа репозиторий перечитывает /manage и ищет точный ID; отсутствие ID обрабатывается как тип, отсутствующий в списке выбранного мероприятия. Все элементы ответа проверяются на event_id.

Существующие POST /events/{id}/ticket-type, PATCH /ticket-types/{id}, PATCH /ticket_types/{id}/hide и /unhide сохранены. После успеха перечитывается полный список. После неизвестного результата PATCH/hide/unhide выполняется /manage с поиском точного ID; повтор изменения после транспортного сбоя не автоматический. После неизвестного результата создания список обновляется, но предупреждение и блокировка повторной отправки сохраняются: совпадение имени ничего не доказывает. Публичный GET /events/{id}/ticket-types остаётся отдельным и исключает скрытые типы; черновики через него не читаются. Новых строк UI не понадобилось: существующие загрузка, пустое состояние, retry и сообщения неизвестного результата уже подходят актуальному контракту.

Сохранены общий single-flight refresh, UserSuspendedError и защита поколением сессии/event sequence. Mock /manage использует существующее хранилище, проверяет владельца, возвращает массив и скрытые типы. Для дополнительной проверки блокировки можно зарегистрировать suspend-on-manage@biletflow.kz: его /manage вернёт 403 UserSuspendedError с очисткой текущей сессии и постоянной записи.

Проверки: `:shared:testAndroidHostTest :androidApp:assembleDebug :androidApp:assembleRelease` — BUILD SUCCESSFUL. Всего 76 host-тестов, 0 ошибок. Три целевых OrganizerTicketTypesTest проверяют полный массив без query-параметров и старых маршрутов, draft/published/cancelled, свежий ViewModel без ответов создания, редактирование, hide/unhide, публичное исключение скрытого типа, потерянный ответ PATCH/hide/unhide/POST с проверкой по ID и без второго изменения, refresh, чужого организатора, смену события/аккаунта, поздние ответы, ошибку списка с повтором и suspension с удалением постоянной записи. `git diff --check` прошёл.

UI через ADB/UIAutomator, Resizable_Experimental / emulator-5554 / Android 16 / mock debug: восстановлен visitor, подана заявка и получен approved; в «Моих мероприятиях» отработал Retry после предусмотренной mock-ошибки; открыт черновик 101; создан Migration52C (#3000) с price=0, quantity=12, датами продаж 25–30.11.2026 10:00 UTC+05:00; выполнен возврат к списку и повторное открытие управления, тип загрузился через /manage; изменена цена на 1500 ₸ без изменения дат; выполнены скрытие с подтверждением, возврат системной кнопкой и повторное открытие (тип остался Hidden), затем Restore (снова доступно Hide). Регрессий в этом UI-сценарии не обнаружено. Свежий ViewModel и транспортные сбои проверены автоматически; потеря ответа/refresh/suspension не воспроизводились вручную в этом UI-прогоне. iOS не собирался и не проверялся на Windows.

Техническая остановка UI перед продолжением: команда PowerShell `. .\build\step50-ui.ps1` + `TapText 'Add ticket type'` + `ShowUi` с require_escalated не была исполнена: automatic approval review сообщил "You've hit your usage limit" и не смог завершить проверку (это не решение о небезопасности действия). После сообщения пользователя «продолжай» повторные разрешённые команды исполнились, UI-сценарий завершён. Иногда UIAutomator возвращал null root node; повторное чтение восстановило управление.

Реальный backend: GET https://biletflow-api.onrender.com/health снова вернул HTTP 404 {"detail":"Not Found"}. Подтверждённого staging URL нет. Авторизованный /manage на настоящем сервере не проверен; функциональная интеграция проверена через MockEngine по актуальному документированному контракту и исходникам, а не историческому предложению. В local/staging используется настоящий клиент без fallback; фактическая ошибка маршрута отображается существующим UI.

Изменённые файлы шага 52C:

- shared/src/commonMain/kotlin/com/example/biletflow/data/repositories/OrganizerTicketTypesRepository.kt.
- shared/src/commonMain/kotlin/com/example/biletflow/core/network/MockEventsHttpClient.kt.
- shared/src/commonTest/kotlin/com/example/biletflow/OrganizerTicketTypesTest.kt.
- docs/backend-api-gaps.md — историческое предложение и явное обновление актуального контракта.
- README.md — этот отчёт.

Существующие правки Gradle и остальные изменения рабочей копии сохранены. Следующие блоки миграции не начинались.

### Заказы организатора и refund — шаг 52D

Источники: актуальные `BiletFlow-main/docs/api-reference.md`, `backend/schemas/order.py` (EventOrderRow/EventOrderRefund/EventOrderPage), `backend/services/order_service.py`, `backend/repositories/order_repository.py`. Список мигрирован на защищённый GET /events/{event_id}/orders с status, q, limit (1–200, клиент 50), offset → {items,total}. Сортировка backend — ID по убыванию. q ищет только buyer_name/buyer_email, без учёта регистра, с trim; специальные символы используются как буквальный текст. Старые /organizer/events/.../orders, отдельный GET заказа и next_cursor больше не используются этим блоком.

OrganizerOrder теперь отдельный DTO строки: id, status, buyer_name, buyer_email, created_at, nullable paid_at/cancelled_at/refunded_at, total_amount, discount_amount, nullable promo_code, processing_fee, tickets (количество выданных билетов), nullable payment_reference, nullable refund {amount,reason,provider_reference,created_at}, is_simulated. Денежные поля Long. event_id не требуется: контекст хранится в маршруте и OrganizerOrdersState. Детали показывают эти серверные поля; список остаётся компактным. Добавлены RU/EN-подписи и отдельное отсутствие совпадений поиска/статуса.

Поиск и чипы применяются на сервере. Изменение фильтров отменяет загрузку и сбрасывает offset; последовательность запроса защищает от поздних результатов. Следующая страница объединяется по ID. Её ошибка сохраняет карточки и offset, повторяет ту же страницу. Для актуальных деталей и проверки неизвестного refund репозиторий обходит полный нефильтрованный список текущего события с offset до точного ID (q/status не передаются). Отсутствие на первой странице не считается отсутствием заказа. Отдельный endpoint чтения заказа не выдумывается. Событие проверяется серверным маршрутом; принадлежность event_id из строки проверить нельзя, поскольку такого поля в контракте нет. Ответ POST refund содержит event_id и проверяется на точное совпадение с контекстом.

POST /orders/{id}/refund с reason 3–500 → Order сохранён. Подтверждение использует актуально прочитанные детали и серверную сумму. Только paid confirmed с total_amount > 0 допускает действие. Повторное нажатие блокируется. После успеха применяется ответ POST, обновляются список и актуальная строка, включая вложенный refund. После потери ответа повторный refund блокируется до успешного чтения точного ID через список события; ошибка проверки оставляет блокировку. Автоматически POST после транспортного сбоя не повторяется. Конфликт статуса вызывает чтение актуального состояния. Сохраняются single-flight refresh, один повтор после явного 401, UserSuspendedError, очистка и защита от ответов другой сессии/события. Старый документ backend-api-gaps.md явно отмечен историческим для заказов; текущая реализация опирается на новый backend.

MockEngine: настоящий путь /events/{id}/orders с offset/limit/status/q и массивом items без event_id; total считается после фильтров. Поля платежа/возврата и счётчик билетов берутся из текущих mock-заказов. Комиссия при отсутствии сохранённого поля соответствует backend: (total_amount * 3 + 50) / 100 целочисленно. Refund сохраняет amount/reason/SIM-R reference/created_at, обновляет заказ и билеты. Новый зарегистрированный suspend-on-event-orders@biletflow.kz получает UserSuspendedError на списке события. Старые предложенные mock-маршруты заказов удалены.

Автоматически: все 79 host-тестов прошли, 0 ошибок; :androidApp:assembleDebug и :androidApp:assembleRelease успешны. OrganizerOrdersTest содержит 6 сценариев: поиск точного ID на второй странице из 52 заказов без фильтров; DTO скидки, промокода, комиссии, количества, payment_reference, nullable refund и is_simulated; сброс offset и старый поиск; первая/следующая страница с ошибкой и повтором; серверные фильтры/trim/регистр/спецсимволы и отсутствие поиска по ID через q; ограничения refund, двойное нажатие, конфликт, обычный возврат и потеря ответа → неудачная проверка → успешный GET без второго POST; обновление покупательского заказа/истории/билетов, участников/продаж и отказ check-in возвращённому билету; права другого организатора, refresh, смена события/аккаунта и старые ответы; suspension с очисткой постоянной записи. Существующие host-тесты остальных блоков также выполнены. git diff --check прошёл.

UI, MockEngine: Resizable_Experimental / emulator-5554 / Android 16. Восстановлен visitor, подана заявка и получен approved. Через «Мои заказы» оплачен pending #7101 на 5000 ₸; через профиль → «Мои мероприятия» → Retry предусмотренной mock-ошибки → событие 101 → «Заказы» открыт новый список. Детали #7101 показали 1 выданный билет, скидку 0, комиссию 150 ₸, SIM-7101 и дату оплаты. Подтверждён полный возврат с причиной «Migration 52D full refund». Получены refunded, сумма возврата 5000 ₸, SIM-R-7101, причина и дата; повторное действие исчезло. Возврат к списку показал refunded. Проверены отсутствие совпадений поиска и сочетание имени покупателя с confirmed. В UI обнаружена повторная подпись учебной симуляции перед действием refund: лишняя удалена, подпись остаётся в деталях и подтверждении; после этой правки повторно прошли все тесты и обе сборки (отдельно её визуально на переустановленном APK не проверяли). UI-потеря ответа, suspension и многостраничная выдача проверены только автоматически. iOS не собирался/не проверялся на Windows. UIAutomator иногда возвращал null root node после запуска; повторное чтение восстановило управление. Отказов автоматической проверки разрешений в 52D не было.

Настоящий API: безопасный GET https://biletflow-api.onrender.com/health вернул HTTP 404 {"detail":"Not Found"}. Подтверждённый staging URL остаётся открытым пунктом. Авторизованные /events/{id}/orders и refund настоящего сервера не проверены. Интеграция проверена через MockEngine по актуальному контракту и исходникам backend. local/staging используют настоящий клиент, без подстановки mock при ошибке.

Изменённые файлы 52D:

- shared/src/commonMain/kotlin/com/example/biletflow/data/repositories/OrganizerOrdersRepository.kt.
- shared/src/commonMain/kotlin/com/example/biletflow/presentation/organizer/OrganizerOrdersViewModel.kt.
- shared/src/commonMain/kotlin/com/example/biletflow/presentation/organizer/OrganizerOrdersUi.kt.
- shared/src/commonMain/kotlin/com/example/biletflow/core/network/MockEventsHttpClient.kt.
- shared/src/commonTest/kotlin/com/example/biletflow/OrganizerOrdersTest.kt.
- shared/src/commonMain/composeResources/values/strings.xml и values-ru/strings.xml.
- docs/backend-api-gaps.md.
- README.md.

Пользовательские настройки Gradle и предыдущие изменения сохранены. Следующие блоки миграции не начинались.

### Создание и списки поддержки — шаг 52E (проверки продолжаются)

Текущий контракт взят из BiletFlow-main/docs/api-reference.md, backend/schemas/support.py и backend/services/support_service.py. App переключён на SupportCasesRepository/SupportCasesViewModel. POST /support/cases отправляет kind=attendee, документированную категорию, subject 1–200 и message 1–5000 после trim, ровно один контекст: ticket_id, иначе order_id, иначе event_id. Client_request_id не отправляется. Серверный SupportCase содержит requester_user_id, nullable связи и assigned_user_id, timestamps, messages с author_user_id/body. Новая форма сохраняет цель гостевого входа и данные, блокирует двойное нажатие, обрабатывает поля и общие refresh/suspension. После потери ответа текст сохраняется, повторная отправка блокируется и предлагается открыть список. Совпадение темы не доказывает создание.

GET /support/cases/me и GET /support/cases/inbox возвращают массивы без cursor-пагинации. Inbox доступен approved-организатору или platform admin; сервер определяет доступный список, клиент фильтрует его по event_id для конкретного мероприятия. Новые ID открывают read-only просмотр через GET /support/cases/{id}; можно прочитать серверные сведения и вложенные сообщения. Отправка, статус, after_id/cursor и polling прежней переписки из App не вызываются: это оставшаяся миграция. Старые SupportRepository/SupportHistoryViewModel и mock-маршруты оставлены изолированно для исторических тестов; их успешность не подтверждает интеграцию новой переписки. Документ support-api-proposal.md помечает прежнее предложение историческим и описывает различия, включая повторное открытие resolved ответом заявителя в новом API.

MockEngine хранит новые case отдельно, проверяет категории/поля/один контекст, владельца заказа/билета, доступ inbox и case, возвращает массивы и единственное первое сообщение. Мок не реализует идемпотентность создания. Subject support-case-unknown симулирует потерю ответа после сохранения. Зарегистрированный cases-offline@biletflow.kz получает ошибку первого чтения списка; suspend-on-cases@biletflow.kz — UserSuspendedError.

Проверки: первый запуск :shared:testAndroidHostTest :androidApp:assembleDebug :androidApp:assembleRelease завершился BUILD SUCCESSFUL. Финальный повторный прогон после добавления suspension-теста также завершился BUILD SUCCESSFUL: 82 host-теста, 0 ошибок, Android debug/release успешны. SupportTest обновлён под текущий контракт: создание с события/заказа/билета и ровно одним контекстом; категории/длины/чужие объекты; refresh; неизвестный результат без второго POST и сохранение текста; гостевой вход и старый ответ после смены аккаунта; массивы/inbox/event_id/read-only просмотр; retry и старый список после выхода; suspension. Тесты старой переписки остаются историческими. git diff --check прошёл. iOS на Windows не проверялся; настоящий авторизованный API не проверен, подтверждённого staging URL по-прежнему нет.

UI: mock debug установлен на Resizable_Experimental / emulator-5554. Создание и списки через UI пока НЕ проверены: UIAutomator после запуска вернул null root node. Это техническая ошибка чтения UI, не отказ разрешений. Следующее действие: повторить чтение UI, создать case со страницы события, проверить «Мои обращения» и inbox организатора в одном процессе. При остатке лимита ниже 20% работу остановить по просьбе пользователя. Последний замер: остаток пятитчасового лимита 21%, недельного 37%.

Файлы 52E: App.kt; data/repositories/SupportCasesRepository.kt (новый) и SupportRepository.kt (историческая пометка); presentation/support/SupportUi.kt и SupportCasesUi.kt (новый); core/network/MockEventsHttpClient.kt; commonTest/SupportTest.kt; RU/EN strings.xml; docs/support-api-proposal.md; README.md. Kotlin-пути относительно shared/src/commonMain/kotlin/com/example/biletflow, тесты относительно shared/src/commonTest/kotlin/com/example/biletflow. Предыдущие правки и Gradle сохранены.
