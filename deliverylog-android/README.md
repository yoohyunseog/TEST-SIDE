# DeliveryLog Android 2.0.5 GPS test build

This is a **separately installable native test app**, not the full original WebView v2.0.5 source archive.
The package ID is **com.chamsosik.deliverylog.testbuild**: it will NOT upgrade or delete the older app's data.
No user-specific ChatGPT project link, secret, or historic delivery data is included.

Features in this test build:
- Pickup starts a session immediately, whether or not GPS has a fix.
- GPS+network location updates, last-minute callback counts and intervals.
- Per-delivery location route, note/incident, and manual delivery fees.
- Unsettled earnings persist across days until confirmed settlement.
- Optional transparent floating shortcut (requires Android overlay permission).
- Locally registered camera coordinates can trigger standard notifications (no fullscreen alert).
- ChatGPT project URL is entered on device, then opened externally.
- Optional user-initiated image/video attachment URI for an incident.

Limitations: no supplied national camera database; no verified real-device testing; no automatic integration with delivery platforms. Android permissions and power settings influence background location tracking. No APK keys from earlier builds are available: GitHub Actions builds a fresh DEBUG signed APK. Do not uninstall your existing delivery app to install this test build.
