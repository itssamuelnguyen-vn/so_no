# Sổ Nợ & Hụi

Personal Android app (sideloaded APK, never on the Play Store), Vietnamese UI. Everything stays on the phone.

**Sổ nợ**
- Lent or borrowed, per person, with phone (picked from contacts), note and a photo of the IOU.
- Pay back once (with or without a due date) or in installments: any number, monthly on a fixed day, weekly,
  daily or every N days.
- No interest or a fixed rate (%/month or %/year): by the day for one-time debts; flat, declining balance or
  equal payments for installments. Full schedule preview before saving.
- Tick each installment as paid in full (or record part of it); the rest is deducted automatically. Payment
  history, settle ("tất toán"), reopen.
- Reminders X days before each due date, on the day and when late, for both lender and borrower.
- "Nhắc trả nợ" message with a VietQR code for the exact amount (your account is set in Settings).

**Hụi** (Southern rules: hụi sống / hụi chết, bỏ thăm, tiền thảo)
- As organizer (chủ hụi) or member (hụi viên); shares by name, several per person, "mine" marked.
- Rules chosen before creating, with a preview of every round's money: organizer collects round 1 or not,
  bidding or no-interest, fee none / fixed / % of face value, fee in the last round or not.
- Daily, weekly, half-month, solar-monthly, lunar-monthly or every N days; reminders before each round.
- Holding a round: pick the winner, type the bid, see what live and dead shares pay and what the winner gets,
  the effective interest rate and a warning above 20%/year.
- Organizer: who has paid, per-share QR for the exact due, "handed over" mark, round announcement for the group
  chat. Member: what I pay each round, what I got, profit or cost of my share.

**Chung**: tabs Lịch (due dates and rounds by month, with lunar days) and Thống kê (6-month cash flow, totals per
year, interest received / paid), home-screen widget, app lock, light/dark, backup/restore `.zip`, weekly automatic
backup into a folder, CSV export, several bank accounts, "giấy xác nhận nợ" PDF signed on screen, optional Google
Drive sync between phones, and a link to the Chi Tiêu app (payments open its add screen pre-filled).

## Google Drive sync (one-time setup, optional)

Google only lets a registered app use Drive. If you already did this for Ghi Chú, reuse the same project:

1. <https://console.cloud.google.com/> → the project used for Ghi Chú (or a new one with **Google Drive API**
   enabled and an **External** OAuth consent screen with your account as a test user).
2. **APIs & Services → Credentials → Create credentials → OAuth client ID → Android**:
   package name `com.samuelcruz.sono`, SHA-1 of the debug key (same as Ghi Chú's, see its README).
3. In the app: Cài đặt → Đồng bộ & kết nối → **Kết nối Google Drive**.

The app only gets its own hidden Drive folder (`drive.appdata`), not your files.

## Build

In VS Code press **Ctrl+Shift+B**. It runs `tool/build_apk.ps1` → `build\apk\sono-<version>-<yyyyMMdd-HHmm>.apk`.
Copy that APK to the phone and open it to install. Android Studio and the emulator are never used.

See `CLAUDE.md` for the code layout and the rules.
