# Sổ Nợ & Hụi

Android app for the owner only (sideloaded APK, never on the Play Store): a debt book (lent / borrowed, one-time or
installments, interest, reminders) and a hụi (rotating savings group, Southern Vietnamese rules) manager, as
organizer or member. **UI is Vietnamese only** (strings written inline, no I18n layer); code, names, comments and
docs are English. The owner is a Rails developer new to Android: keep code simple, explain Android concepts by
comparison with Rails (Room ~ ActiveRecord, NavHost routes ~ routes.rb, WorkManager ~ cron/ActiveJob). Build rules
(no Android Studio, no emulator) are in `~/.claude/CLAUDE.md`.

## Build, test, install

```powershell
$env:JAVA_HOME='D:\dev\android-sdk\jbr'
.\gradlew.bat testDebugUnitTest                                   # JVM tests: hụi, debts, installments, money, VietQR, lunar
powershell -ExecutionPolicy Bypass -File tool\build_apk.ps1        # = Ctrl+Shift+B → build\apk\sono-<ver>-<stamp>.apk
```

From WSL redirect Gradle's output to a file and feed stdin from /dev/null, otherwise the call can hang:
`powershell.exe -NoProfile -Command "cd D:\dev\customized_apps\so_no; .\gradlew.bat assembleRelease *> build\gradle.log" < /dev/null`
(the log is UTF-16: `iconv -f utf-16le -t utf-8 build/gradle.log`).

Release is R8-shrunk and signed with the local debug key. Bump `versionCode` / `versionName` in
`app/build.gradle.kts` for each APK given to the phone.

Stack (same versions as `chi_tieu` / `ghi_chu`, see `gradle/libs.versions.toml`): Kotlin, Compose + Material 3
(+ icons extended), Navigation Compose (string routes), Room (KSP, schemas in `app/schemas`), WorkManager,
kotlinx.serialization, Glance widget, ZXing core (QR), DocumentFile, Play services auth (Drive sync, `drive.appdata`
scope only; Drive REST is plain HttpURLConnection copied from ghi_chu). compile/target SDK 37, minSdk 29. No DI:
`SoNoApp` holds `db`, `prefs`, `repo`. No ViewModels: screens read Room Flows with `collectAsState` and write
through `app.repo` (form state in `rememberSaveable`).

## Layout

```
SoNoApp       wiring: db, prefs, repo (onChange → changedAt, widget refresh, sync in 20 s), schedules, syncSoon()
MainActivity  app lock, notification permission (asked once), AppNav; syncs on start and when going to background
Reminders     daily WorkManager job at Prefs.reminderHour → one notification (debts due / late, hụi rounds)
core/   Hui (rules, round maths, IRR, legal-cap check) + HuiSchedule (solar / lunar / every N days),
        Installments (schedules: none / flat / declining / annuity; applying payments), DebtMath (one-time debts:
        simple interest by the day, payments pay interest first), Money + Dates + plain(), LunarCalendar (copied
        from bang_tin_nha with its test), VnNumber (amounts in words for the IOU), PenaltyMode (late fees),
        HuiBids (every bid of a round), AppLock (+ Prefs, BankAccount list)
qr/     VietQr, Banks, BankStyle, QrCard: copied from qr_chuyen_khoan (keep VietQrTest passing)
data/   Entities (Person, Debt, Payment, HuiGroup, HuiSlot, HuiRound, HuiPaid), AppDatabase (+ DAOs), DebtState
        (a debt worked out for today), Repo (all writes), Photos (IOU pictures, signatures), Backup (+ AutoBackup,
        CSV), SnapshotFiles (safety copies before sync replaces data), IouPdf (giấy xác nhận nợ, PdfDocument)
sync/   Drive + GoogleAuth (copied from ghi_chu), SnapshotSync (whole-book `sono-data.zip` in appDataFolder) + SyncWorker
ui/     Theme, Components, AppNav, DebtsScreen (tab), PersonScreen, DebtScreen, DebtEditScreen, HuiListScreen (tab,
        + HuiView), HuiEditScreen (rules + preview), HuiScreen (+ SlotDialog), RoundScreen (new / edit / held),
        CalendarScreen (tab), StatsScreen (tab: cash flow, year), IouScreen (signature pads), SettingsScreen
        (+ SyncSection), QrShare, BankPicker, ChiTieu (hands a transaction to the Chi Tiêu app)
widget/ SummaryWidget (to collect / to pay / next due)
```

## Hụi rules (Southern style, see `core/Hui.kt`)

- N shares (chân), N rounds; each round one live share collects (hốt). Slots are numbered 1..N; with
  `ownerFirst` slot 1 is the organizer's and collects round 1 with no bid and no fee.
- Bidding (`bidding = true`, hụi bỏ thăm): live shares other than the winner pay `amount − bid`, dead shares pay
  `amount`. Winner gets the sum minus the fee (thảo). The last round has no bid.
- Fee: none / fixed đồng / tenths of a percent of the face value; optionally not charged in the last round.
- `bidding = false` (hụi không lãi): nobody bids, everyone always pays in full.
- `fixedBid > 0` (hụi lãi cố định): no bidding; every winner gives up `fixedBid` per live share, as if bid.
- `HuiRound.bids` keeps every bid ("slot=amount;…") when the user typed them all; the highest wins, ties are
  drawn by lot in the form. Held rounds can be edited (winner must not win another round).
- Bỏ ngang: `HuiSlot.droppedFrom` = from that round the organizer pays the share's dues (`HuiPaid.covered`);
  an unpaid due can also become a "they owe me" debt (`Repo.dueToDebt`, `coveredToDebt`).
- Rules are locked once a round is held (the editor disables them) so old rounds keep their amounts. Round money is
  recomputed from rules + (winner, bid) of each round, never stored.
- Only the last held round can be deleted; any held round can be edited. Organizer: own shares (and dropped
  shares, as covered) are marked paid automatically.
- Rates: IRR of a share's cash flow, per month, negative = cost. Above 20%/year the UI warns (Civil Code cap
  that Decree 19/2019 applies to hụi có lãi).

## Debt rules

- `Debt.direction`: LENT (they owe me) / BORROWED (I owe). `plan`: ONCE (optional `dueDay`) or INSTALLMENTS
  (`term` installments from `dueDay`, monthly on that day of the month or every `everyDays` days).
- Interest: rate as typed (`ratePercent` per month or per year). ONCE uses SIMPLE (by the day, 30 days = a
  month, payments pay interest first). INSTALLMENTS: FLAT, DECLINING (equal principal) or ANNUITY (equal totals).
  Schedules are computed, never stored; rounding leftovers go to the last installment.
- Payments: `installment` = which installment it pays (ticking "đã đóng đủ" adds the missing part); untagged
  payments fill installments in order. A debt whose total is paid is settled automatically (on its last payment
  day); removing a payment reopens it. "Tất toán" can also close a debt with money left (forgiven).
- `remindDays` (−1 = off) notifies before each due date; due days and late days (1, 3, then every 7) always notify.
- Top-ups ("vay thêm", one-time debts only) are `Payment.kind = TOP_UP`: they add principal from their day.
- `Debt.dueOverrides` ("n=epochDay;…") moves single installments ("dời hạn"); amounts never change.
- Late fees (`penaltyMode`: per day / % per day / once) are computed per late installment from its due day to the
  day it became fully paid (`InstallmentStatus.fullDay`) or today, and added to what is left.
- Schema changes: bump the `@Database` version and add a migration (AutoMigration when possible). Never destructive
  migrations: these are real records.

## Sync (Google Drive, optional)

- One file `sono-data.zip` (the backup format) in the app's hidden `appDataFolder`. `Prefs.changedAt` (every
  repo write) vs `lastSyncAt` says this phone has news; Drive's `version` vs `syncVersion` says Drive has news.
  Only one side changed → upload or download (replace). Both → `Result.Conflict`, the user picks in Settings;
  the losing side is kept in `SnapshotFiles` (Settings → "Bản lưu an toàn"), never just thrown away.
- If the OAuth client lives in the same Google Cloud project as ghi_chu, both apps share one appDataFolder;
  ghi_chu only touches its `n_` / `l_` / `a_` files and this app only `sono-data.zip`.

## Chi Tiêu link

`ui/ChiTieu` starts `com.samuelcruz.chitieu/.MainActivity` with action `com.samuelcruz.chitieu.ADD` and extras
`kind` (0 expense, 1 income), `amount` (Long), `note` (chi_tieu ≥ 0.5.1). Lending = expense, borrowing = income,
payment received = income, payment made / hụi due = expense, hốt = income. Manifest `<queries>` names the package.

## Roadmap

Done 0.1.0 (2026-11-14): debt book, hụi, reminders, QR, widget, backup.
Done 0.2.0 (2026-11-14, DB v2 AutoMigration): edit payments / held rounds, top-ups, extend / postpone, late fees,
unpaid share → debt, calendar tab, stats tab (6-month cash flow, year), all bids + drawing lots, bỏ ngang,
hụi lãi cố định, Drive sync, Chi Tiêu link, IOU PDF with signatures, several bank accounts, live "today",
warning when an installment schedule with payments changes.
Ideas not built (ask first): end-to-end encryption of the Drive copy, row-by-row merge sync.
