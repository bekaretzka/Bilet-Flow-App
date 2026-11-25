# BiletFlow Mobile: slide material (CSCI 361 final presentation)

Mobile client of BiletFlow: Kotlin Multiplatform (Android + iOS), Compose Multiplatform UI.
Repository: https://github.com/bekaretzka/Bilet-Flow-App, demo tag `v1.0-demo`.

---

## Slide: Mobile architecture (≈1 min)

```
Compose screen  →  ViewModel  →  SessionOrdersRepository  →  Ktor HttpClient  →  FastAPI  →  PostgreSQL
(QrScannerUi,      (StateFlow,     (Bearer token, one         (JSON, kotlinx      (routers →   (tickets,
 CheckInOverview)   generation)     refresh on 401)            serialization)      services)    check_in_records)
```

Traced action — check-in of a ticket:

1. Staff taps **Check in** (or the camera decodes the QR).
2. `CheckInOverviewViewModel` sends `POST /events/{event_id}/check-in {code}` with the staff access token.
3. The backend checks the staff assignment, then marks the ticket checked in with one atomic conditional update
   ("act first, explain afterwards"), writes a check-in record and an audit-log entry. If the update changed nothing,
   it only looks up why (already used, refunded, other event) and changes nothing.
4. The answer `{result, ticket}` is shown as a full-screen verdict:
   `checked_in` → green «Let them in», `already_checked_in` → yellow «Already checked in», others → red «Do not let in».
5. Stats are re-read from `GET /events/{event_id}/check-in/stats`.

## Slide: design decision and trade-off

**Kotlin Multiplatform instead of React Native.**
One Kotlin codebase shares the API client, models, session handling and Compose UI between Android and iOS,
while the app stays native: the scanner uses the platform camera directly (CameraX on Android, a native UIKit camera view on iOS), with no JavaScript bridge.
Trade-off: smaller ecosystem and longer builds than React Native; iOS needs a Mac to build.

**No automatic retry of a check-in with an unknown result.**
If the network drops after the request was sent, the server may already have admitted the ticket.
Retrying could not create a second admission (the server rejects it), but the staff member would see a false
"Already checked in". So the app shows «result unknown» and checks the real ticket state through
`GET /check-in/search` instead. Alternative rejected: silent retry — simpler, but misleading at the door.

## Slide: verification result

| What was checked | Version | Expected | Actual |
| --- | --- | --- | --- |
| 89 shared host tests (`:shared:testAndroidHostTest`): API contract parsing, session refresh, check-in, campaigns, support | `v1.0-demo` | all pass | 89 / 89 pass |
| Android app against the real backend (repo `BiletFlow-main`, local, Postgres 16, `seed_demo.py`) | `v1.0-demo` | catalogue, staff login, check-in list and live stats come from the API | confirmed; requests visible in the API log |
| Repeated check-in of the same ticket | `v1.0-demo` | second attempt rejected, stats do not change | `already_checked_in`, stats stay 5 / 2 (`docs/presentation/v1.0-demo_2026-11-26_repeated-checkin-rejected.txt`) |

**Not verified:** iOS build (needs a Mac); the deployed Render API (it is a different API version: `/api/v1`, UUID ids);
camera scanning on the emulator (no webcam on the demo laptop — the demo uses manual check-in, the same API call).

## Demo setup (say this before the demo)

- Commit / tag: `v1.0-demo`
- Backend: repository `BiletFlow-main`, run locally; data from `scripts/seed_demo.py`
- Accounts: `staff@demo.example.com` (check-in staff), `organizer@demo.example.com` (organizer) — password from the seed script, not on slides
- Starting data: «Almaty Tech Meetup» is live; 7 tickets, 4 already checked in
- Simulations: payments are simulated (no real money); no real email delivery

## Demo journey (mobile part)

1. Log in as staff → **Check-in** → «Almaty Tech Meetup» (tag «Entry open»).
2. Stats: 7 total, 4 checked in, 3 left.
3. Find «Dana Omarova» → **Check in** → result «checked in»; stats become 5 / 2.
4. **Failure case:** present the same ticket again → the server answers `already_checked_in`; the scanner shows the
   yellow «Already checked in» with the time and the staff email; stats stay 5 / 2 — no second admission is created.
   After a manual check-in the app offers only «Undo check-in» for that ticket (as in Figma), so the repeat is shown
   with a camera scan (a phone) or with the recorded API check (backup file below).

## Backup evidence (docs/presentation/)

- `v1.0-demo_2026-11-26_checkin-journey.mp4` — staff finds the attendee, marks entry, stats 4 → 5 (real local backend)
- `v1.0-demo_2026-11-26_0*.png` — catalogue in Astana time, attendee valid, confirm dialog, checked in with stats 5 / 7
- `v1.0-demo_2026-11-26_repeated-checkin-rejected.txt` — repeated check-in rejected, counters unchanged
- `c9db413_2026-11-26_0*.png` — staff event list and scanner camera, taken on the previous build (times there are still UTC)

## Known limitations

- iOS part is not compiled in CI.
- Mobile follows `docs/api-reference.md`; the Render deployment exposes another API version.
- Email links (verify / reset password) do not open the app automatically yet: the website host for App Links is not configured.

## My contribution (individual part)

- Built the mobile client: catalogue, ordering and payment (simulated), tickets with QR and PDF, organizer tools,
  check-in scanner, support, platform admin.
- Audited the client against the backend API reference and connected the missing endpoints (step 57).
- Implemented the Figma design system (tokens, typography, components, icons) and the scanner / ticket screens.
- Checked the result with the shared host tests and a run against the real local backend.
