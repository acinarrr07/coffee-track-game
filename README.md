# Coffee Track (GMS 4039 Mock Midterm)

A café-service game for Android (Kotlin, no external libraries).
Guests sit at tables and order meals. Tap a meal in the tray, then tap the guest who ordered it.

## Scoring
- Correct meal: +10 | Speed bonus: up to +10 | Combo streak: +2 per streak (max +10)
- VIP guest (crown): double points
- Wrong meal: -5 points | Guest leaves angry: -1 life
- Every 100 points: level up (+20 bonus, +1 life, new meals unlocked, faster guests)

## Build
GitHub Actions builds the APK on every push (Actions tab > Android Build).
Locally: open the folder in Android Studio and press Run.
