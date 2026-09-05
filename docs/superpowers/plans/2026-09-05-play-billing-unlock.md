# Plan: Premium Unlock via Google Play Billing

Spec: `docs/superpowers/specs/2026-09-05-play-billing-unlock.md`. Branch `feat/ux-overhaul`.

Already done (2026-09-05): `app/build.gradle.kts` has `com.android.billingclient:billing:9.1.0` and
`FAKE_BILLING` build config fields (debug `true`, release `false`); `compileDebugKotlin` passes.

## Tasks

1. **Entitlement state**
   - `feature/settings/AppSettings.kt`: `premiumUnlocked: Boolean = false` in `AppSettings`; key `premium_unlocked` in the store.
   - `testing/HandTypeDebugHooks.kt`: `@Volatile var premiumUnlocked: Boolean? = null`; `clear()` resets it.
   - `billing/PremiumEntitlement.kt`: `data class PurchaseInfo(products: List<String>, purchased: Boolean, pending: Boolean, acknowledged: Boolean, token: String)`, `data class Evaluation(unlocked: Boolean, pending: Boolean, tokensToAcknowledge: List<String>)`, `object PremiumEntitlement { const val PRODUCT_ID = "handtype_premium"; fun evaluate(purchases: List<PurchaseInfo>): Evaluation }`. Unlocked = at least one purchased purchase for `PRODUCT_ID`; tokensToAcknowledge = purchased+unacknowledged for `PRODUCT_ID`; pending = a pending purchase for `PRODUCT_ID` and not unlocked.
   - Unit test `app/src/test/java/com/baer/handtype/billing/PremiumEntitlementTest.kt` (JUnit4, no Android): empty, purchased+acked, purchased+unacked, pending only, wrong product, mixed.

2. **Billing client**
   - `billing/PremiumBilling.kt`: `sealed interface PremiumState { Locked, Unlocked, Pending, Unavailable }`, `sealed interface PurchaseResult { Success, Pending, Cancelled, Error(message) }`.
   - `interface PremiumBilling { val state: StateFlow<PremiumState>; suspend fun refresh(); suspend fun formattedPrice(): String?; suspend fun purchase(): PurchaseResult; fun close() }` plus `companion fun create(activity: Activity, store: AppSettingsStore): PremiumBilling` choosing `FakePremiumBilling` when `BuildConfig.FAKE_BILLING`, else `PlayPremiumBilling`.
   - `PlayPremiumBilling`: `BillingClient` with `enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())`, a `PurchasesUpdatedListener` that completes the in-flight purchase deferred; `suspendCancellableCoroutine` wrappers for `startConnection`, `queryPurchasesAsync(INAPP)`, `queryProductDetailsAsync`, `acknowledgePurchase`. `refresh()`: connect, query, map `Purchase` to `PurchaseInfo` (`purchaseState == PURCHASED`/`PENDING`, `isAcknowledged`, `products`), evaluate, acknowledge tokens, then write `premiumUnlocked` to the store only when the query succeeded; on `BILLING_UNAVAILABLE`/connection failure set state `Unavailable` and keep the cached value. `purchase()`: connect, product details, `launchBillingFlow`, await listener; `USER_CANCELED` -> Cancelled; `ITEM_ALREADY_OWNED` -> run `refresh()` and return Success if unlocked. Initial `state` comes from the store.
   - `FakePremiumBilling`: initial state from `HandTypeDebugHooks.premiumUnlocked ?: store.read().premiumUnlocked`; `purchase()` delays 300 ms, writes `premiumUnlocked = true`, returns Success; `formattedPrice()` returns `"€ 2,99"`; `refresh()` re-reads the hook/store.

3. **Paywall UI**
   - `feature/billing/PremiumPaywallSheet.kt`: `@Composable fun PremiumPaywallSheet(billing: PremiumBilling, onDismiss, onUnlocked)`; `ModalBottomSheet` with title, two benefit lines, price (loading placeholder until `formattedPrice()` returns), primary button `premium_paywall_buy` with price, text button `premium_paywall_restore` calling `refresh()` and toasting `premium_restore_success`/`premium_restore_none`; states purchasing (buttons disabled), pending, error (`premium_paywall_error`, retry), unavailable (`premium_paywall_unavailable`). Test tags `PREMIUM_PAYWALL_SHEET`, `PREMIUM_BUY_BUTTON`, `PREMIUM_RESTORE_BUTTON` added to `HandTypeTestTags`.

4. **Gates**
   - `MainActivity.kt` `HandTypeRoot`: `val billing = remember(activity) { PremiumBilling.create(activity, settingsStore) }` (pass the store/activity down from `HandTypeApp`), `LaunchedEffect(Unit) { billing.refresh() }`, `DisposableEffect { onDispose { billing.close() } }`, `val premiumState by billing.state.collectAsState()`, `val unlocked = premiumState == PremiumState.Unlocked`. `var paywallAction by remember { mutableStateOf<(() -> Unit)?>(null) }`; `requirePremium(action)` runs it when unlocked, otherwise stores it and shows the sheet; on `onUnlocked` run the stored action. Both `onPremiumSelected` lambdas go through `requirePremium { route = RootRoute.PremiumCapture.name }`. Render the sheet at root level (outside `AnimatedContent`) so it survives route changes.
   - `TemplateScreens.kt`: `HandwritingRenderScreen` gets `premiumUnlocked: Boolean` and `onLockedPresetTapped: (String) -> Unit`; `BackgroundPicker`/`BackgroundPresetCard` route a tap on a `premium` preset to `onLockedPresetTapped` when locked; the initial and default background fall back to `NoteBackgroundCatalog.defaultPreset().id` when the stored default is premium and locked; the "set as default" action is unaffected. `MainActivity` implements `onLockedPresetTapped = { id -> requirePremium { pendingPresetSelection = id } }` with `HandwritingRenderScreen` observing `pendingPresetSelection` (simplest: pass `externallySelectedBackgroundId: String?` + `onExternalSelectionConsumed`).
   - `SettingsScreen.kt`: third `PreferenceCard` (`settings_premium_title`), body shows `settings_premium_unlocked` or a button `premium_paywall_buy`-style "Unlock" that calls `onUnlockPremium`, plus a "Restore purchase" row calling `onRestorePremium`. Add parameters `premiumUnlocked: Boolean`, `onUnlockPremium: () -> Unit`, `onRestorePremium: () -> Unit`.

5. **Strings**: add the 12 keys from the spec to all 10 `values*/strings.xml` (translate; keep `%1$s` placeholders); verify parity with an XML parser (expect 211 keys each).

6. **Tests**
   - `HandwritingImportE2eTest.setUpClass`: `HandTypeDebugHooks.premiumUnlocked = true`; `tearDownClass` leaves `clear()` handling it.
   - New `app/src/androidTest/java/com/baer/handtype/PremiumPaywallTest.kt`: hook `premiumUnlocked = false`; tapping `template_chooser_premium_button` shows `PREMIUM_PAYWALL_SHEET`; tapping `PREMIUM_BUY_BUTTON` closes the sheet and shows `CAMERA_SCAN_BUTTON`. Restore SharedPreferences `premium_unlocked` to its previous value after the test (the fake writes it).

## Verification (executor: focused; controller: once at the end)

- `./gradlew test --no-daemon -q` -> 0 failures (XML-counted; expect 26 + new entitlement tests).
- `./gradlew assembleDebug assembleDebugAndroidTest lintDebug --no-daemon -q` -> exit 0.
- Device `10.10.70.101:5555`: `adb install -r` debug + test APKs, run `PremiumPaywallTest` and `HandwritingImportE2eTest` via `am instrument`; wake the device first.
- Manual screenshot of the paywall sheet on device (`artifacts/production-audit/screen.py`).
- Real Play purchase: owner-side after keystore repair and internal-track upload; not part of this plan.
