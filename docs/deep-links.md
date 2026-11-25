# Ссылки на мероприятие, которые открывают приложение

Шаг 54. Что сайт должен разместить, чтобы ссылка на страницу мероприятия (и ссылка из Campaign QR) открывала мобильное приложение.

## Формат ссылки

Backend строит ссылку кампании в `backend/services/campaign_service.py`:

```
{FRONTEND_URL}/events/{event_id}?campaign={qr_token}      # qr_token = C-<32 hex>
```

`FRONTEND_URL` — адрес веб-приложения (`backend/.env.example`: `http://localhost:5173`; `render.yaml`: заполняется вручную на Render, `sync: false`). В `docs/deploy.md` и backend опубликованный адрес сайта пока не указан, поэтому хост в приложении не задан по умолчанию.

Приложение принимает только `https://<хост сайта>/events/{id}` (необязательно `/` в конце) с необязательным `?campaign=`. Другой хост, схема или путь игнорируются.

- без `campaign` — открывается мероприятие;
- с `campaign` — один вызов `GET /campaign-links/{token}`, открывается `event_id` из ответа, `promo_code` (если не null) подставляется на экране заказа; 404 — «Ссылка недействительна».

## Настройка приложения, когда адрес сайта появится

Android: свойство Gradle `biletflow.webHost` (только хост, без `https://`), например в `gradle.properties` или `-Pbiletflow.webHost=biletflow.example.kz`. Затем добавить в `androidApp/src/main/AndroidManifest.xml` внутрь `<activity android:name=".MainActivity">`:

```xml
<intent-filter android:autoVerify="true">
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="https" android:host="${webHost}" android:pathPrefix="/events/" />
</intent-filter>
```

и в `androidApp/build.gradle.kts` → `defaultConfig`: `manifestPlaceholders["webHost"] = <то же значение>`.

iOS: переменная `BILETFLOW_WEB_HOST` (как `BILETFLOW_STAGING_URL`) и capability Associated Domains с `applinks:<хост сайта>` в entitlements — после настройки подписи (`TEAM_ID` в `iosApp/Configuration/Config.xcconfig` сейчас пуст).

## Android: /.well-known/assetlinks.json

Сайт отдаёт `https://<хост сайта>/.well-known/assetlinks.json` с `Content-Type: application/json`, без редиректов, по HTTPS:

```json
[{
  "relation": ["delegate_permission/common.handle_all_urls"],
  "target": {
    "namespace": "android_app",
    "package_name": "com.example.biletflow",
    "sha256_cert_fingerprints": [
      "<SHA-256 debug-сертификата>",
      "<SHA-256 release-сертификата>"
    ]
  }
}]
```

`package_name` — `applicationId` из `androidApp/build.gradle.kts`.

Как получить отпечатки:

- debug (у каждого разработчика свой ключ `~/.android/debug.keystore`):
  `keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android` → строка `SHA256:`. Для ключа на машине, где собирался шаг 54: `AE:5F:43:3F:A8:74:0D:2D:2E:A4:4C:24:62:EB:A4:69:4C:05:53:84:F4:48:1D:BF:9C:71:57:CD:55:F7:6A:C8`.
- release: `keytool -list -v -keystore <release.jks> -alias <alias>`; или `.\gradlew.bat :androidApp:signingReport`. Если приложение публикуется в Google Play с Play App Signing — отпечаток из Play Console → Setup → App integrity → App signing key certificate. В проекте сейчас нет release signingConfig.

Проверка: `adb shell pm get-app-links com.example.biletflow` (домен в состоянии `verified`).

## iOS: /.well-known/apple-app-site-association

Сайт отдаёт `https://<хост сайта>/.well-known/apple-app-site-association` (без расширения, `application/json`, без редиректов):

```json
{
  "applinks": {
    "details": [{
      "appIDs": ["<TEAM_ID>.<bundle id>"],
      "components": [{ "/": "/events/*", "comment": "страница мероприятия, в том числе ?campaign=" }]
    }]
  }
}
```

Bundle id — `PRODUCT_BUNDLE_IDENTIFIER` из `iosApp/Configuration/Config.xcconfig`: `com.example.biletflow.BiletFlow$(TEAM_ID)`. Team ID — из Apple Developer аккаунта (Membership); сейчас не задан.

## Без этих файлов

Пока файлы не размещены (или хост не настроен в приложении), ссылка открывается на сайте в браузере. Это ожидаемое поведение, а не ошибка приложения: страница мероприятия на сайте сама вызывает `GET /campaign-links/{token}`.
