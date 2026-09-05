# HandType Premium Unlock (Google Play Billing)

**Date:** 2026-09-05
**Scope:** One-time, non-consumable in-app purchase that unlocks handwriting capture and the five premium paper presets.
**Approach:** Google Play Billing Library (plain `billing` artifact, coroutine wrappers in-app), client-side entitlement cache in the existing settings store, no server.

---

## Product

| Item | Value |
|---|---|
| Play product id | `handtype_premium` (cannot be renamed after creation in Play Console) |
| Product type | In-app product (`ProductType.INAPP`), non-consumable, never consumed |
| Price | Set in Play Console by the owner; the app shows `ProductDetails.oneTimePurchaseOfferDetails.formattedPrice` and never hardcodes a price |
| Library | `com.android.billingclient:billing:9.1.0` (latest on Google Maven as of 2026-09-05). Not `billing-ktx`: every ktx version from 8.3.0 on depends on `kotlin-stdlib` 2.2.10, which the project's Kotlin 1.9.22 compiler rejects (measured: `compileDebugKotlin` fails with metadata 2.2.0 errors). The plain artifact has no Kotlin dependency and compiles. |

## What the unlock gates

1. **Capture flow.** Every entry to `RootRoute.PremiumCapture` (chooser premium card, chooser "Premium" button, editor "Premium" button). All three already funnel through `onPremiumSelected` in `MainActivity.kt`; the gate lives there, once.
2. **Premium paper presets.** `parchment_sheet`, `recycled_sheet`, `kraft_sheet`, `ruled_sheet`, `graph_sheet` (`premium = true` in `NoteBackgrounds.kt`). Locked presets stay visible in the picker with the existing badge, and a tap opens the paywall instead of selecting them.
3. **Nothing else.** Existing user templates captured earlier stay usable and renderable even if the entitlement is later lost (refund). Rendering, export, history, and the free presets never check the entitlement.

Fallbacks when locked:
- The default-paper store (`note_background_prefs`) may hold a premium id. `NoteBackgrounds` resolution returns `warm_cream` when the stored id is premium and the entitlement is false.
- A render config built with a premium preset while locked is not possible through the UI; no renderer-level check.

## Entitlement model

- Single boolean `premiumUnlocked`, added to `AppSettings` and persisted by `AppSettingsStore` under key `premium_unlocked` in `handtype_app_settings`. The cache is what the UI reads; it makes the gate work offline and avoids a Play round trip on every screen.
- Source of truth is Play: `queryPurchasesAsync(INAPP)` at every app start (and after `onResume` following a paywall) rewrites the cache: `true` when a purchase for `handtype_premium` is in state `PURCHASED`, `false` when the query succeeds and finds none. A failed query (no Play, offline) leaves the cache untouched.
- Every `PURCHASED` purchase that is not yet acknowledged is acknowledged immediately (Play refunds unacknowledged purchases after 3 days). The cache flips to `true` only after acknowledgement succeeds or the purchase was already acknowledged.
- `PENDING` purchases (slow payment methods) do not unlock. The paywall shows a "pending" message; the next start re-queries.
- `enablePendingPurchases(PendingPurchasesParams...)` is set on the client (mandatory in Billing 5+).
- `allowBackup="false"` means the cache does not survive reinstall or device transfer. The start-up query restores it as soon as Play is reachable.

### New file: `billing/PremiumBilling.kt`

One class owning the `BillingClient` lifecycle, bound to the `Activity` (`BillingClient.newBuilder(activity)` needs an Activity for `launchBillingFlow`). Public surface, all `suspend` via small `suspendCancellableCoroutine` wrappers around the callback API (`startConnection`, `queryPurchasesAsync`, `queryProductDetailsAsync`, `acknowledgePurchase`):

```
class PremiumBilling(activity: Activity, store: AppSettingsStore) {
    val state: StateFlow<PremiumState>      // Locked, Unlocked, Pending, Unavailable
    suspend fun refresh()                    // connect + queryPurchases + acknowledge, updates cache
    suspend fun productDetails(): ProductDetails?   // for price display
    suspend fun purchase(): PurchaseResult   // launchBillingFlow + await PurchasesUpdatedListener
    suspend fun restore(): PremiumState      // same as refresh, user-triggered, surfaces "nothing found"
    fun close()
}
```

Pure decision logic (no Play types beyond `Purchase`) goes into a separate object so it is unit-testable:

```
object PremiumEntitlement {
    fun evaluate(purchases: List<Purchase>): Evaluation   // unlocked?, toAcknowledge: List<String>, pending?
}
```

Wiring: `HandTypeRoot()` creates `PremiumBilling` with `remember`, calls `refresh()` in a `LaunchedEffect(Unit)`, closes it in `DisposableEffect`. The unlocked flag flows down as a plain `Boolean` parameter, same style as the theme and language settings today.

### Debug builds

`com.baer.handtype.debug` has a different applicationId than the Play listing, so Play returns `BILLING_UNAVAILABLE` or `ITEM_UNAVAILABLE` for it. The debug build type gets `buildConfigField("boolean", "FAKE_BILLING", "true")` (release: `false`). With `FAKE_BILLING`, `PremiumBilling` uses an in-memory fake: `purchase()` succeeds after a short delay, `restore()` returns the cached value, and `HandTypeDebugHooks.premiumUnlocked` can force either state for instrumented tests. The existing `HandwritingImportE2eTest` sets it to `true` before entering capture.

`buildFeatures.buildConfig = true` is already set in `app/build.gradle.kts`.

## UI

### Paywall

One `ModalBottomSheet` (`PremiumPaywallSheet`, in `feature/billing/PremiumPaywallSheet.kt`) opened from any gate site:
- Title, two benefit lines (capture your own handwriting; five extra papers), the formatted price, primary button "Unlock for {price}", text button "Restore purchase".
- States: loading price, ready, purchasing (buttons disabled), pending, error with retry, Play unavailable (message plus Restore stays enabled). User cancel closes silently.
- On success the sheet closes, the pending action runs: the capture route opens or the tapped preset is selected.
- Test tags in `HandTypeTestTags`: `PREMIUM_PAYWALL_SHEET`, `PREMIUM_BUY_BUTTON`, `PREMIUM_RESTORE_BUTTON`.

### Settings

A "Premium" row in `SettingsScreen`: shows "Unlocked" or an "Unlock" button (opens the paywall) and a "Restore purchase" action with a toast result. This is the required discoverable restore path for reinstalls.

### Strings

New keys (all 10 locales, keeping parity; count verified with an XML parser after the change):
`premium_paywall_title`, `premium_paywall_benefit_capture`, `premium_paywall_benefit_papers`, `premium_paywall_buy` (with `%1$s` price), `premium_paywall_restore`, `premium_paywall_pending`, `premium_paywall_unavailable`, `premium_paywall_error`, `premium_restore_success`, `premium_restore_none`, `settings_premium_title`, `settings_premium_unlocked`.

## Play Console setup (owner side)

1. Upload a release-signed build to the Internal testing track. Billing does not work for an applicationId that Play has never seen, even for license testers.
2. Monetize → Products → In-app products → create `handtype_premium`, set price, activate.
3. Settings → License testing → add the tester Google accounts. License testers can buy with test payment methods and are not charged.
4. The tester account must be the primary account on the test device and must have installed the app from the internal testing link at least once. Test purchases can be refunded/cancelled in Play Console → Order management.

## Testing

- Unit (JUnit4, no Play services): `PremiumEntitlementTest` covers empty list, purchased+acknowledged, purchased+unacknowledged (acknowledge list), pending, wrong product id, mixed lists. `AppSettingsStore` round trip of the new key follows the existing test pattern if one exists; otherwise covered by the entitlement test's fake store.
- Instrumented (debug, fake billing): paywall opens from the chooser premium card and from a locked preset; buying through the fake unlocks capture and the preset; `HandwritingImportE2eTest` keeps passing with the hook set.
- Manual (release build, internal testing track, license tester): buy, restore after uninstall/reinstall, refund in Play Console then relaunch shows locked, premium presets locked before purchase and selectable after.

## Concerns surfaced

1. **Kotlin 1.9.22 vs `billing-ktx`.** Resolved by measurement: ktx 8.3.0+ needs Kotlin stdlib 2.2.10 and fails to compile here; the plain `billing:9.1.0` artifact compiles. Upgrading the project to Kotlin 2.x (and the Compose compiler Gradle plugin) is a separate task and not required for billing.
2. **Client-only entitlement is bypassable.** A patched APK can set `premium_unlocked = true`. No server exists and none is planned; acceptable for a small one-time unlock. Do not add signature verification code on the client; it protects nothing a patcher cannot also remove.
3. **Behaviour change for premium presets.** They are free today. Any existing tester install loses them until purchase; there are no production users, so no migration.
4. **Debug builds cannot reach Play.** Handled by the fake, but the fake means instrumented tests never exercise the real Billing flow. Real flow is manual only.
5. **Release signing is a prerequisite for any real purchase test.** The keystore repair from the previous session is still on the owner.
6. **Play Billing version deadline.** Play requires a recent Billing Library for new submissions; 9.1.0 is the current release and satisfies it. The exact cutoff date was not looked up (unverified).

## Out of scope

Subscriptions, promo codes, server-side receipt validation, multiple products, ProGuard rules (release keeps `isMinifyEnabled = false`), any paywall on first launch.
