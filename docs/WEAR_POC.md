# Wear OS HR PoC — runbook

A lean, GMS-free Wear app showing live HR + connection + battery + last-sync, fed from the phone
noop app over a pluggable transport. The PoC validates over TCP (no Bluetooth, no Google Play
Services). Spec: `docs/superpowers/specs/2026-06-18-wear-os-hr-poc-design.md`.

## Build
    cd android
    ./gradlew :wearlink:test               # pure-JVM logic (codec + TCP transport)
    ./gradlew :app:assembleFullDebug       # phone APK (publisher)
    ./gradlew :wear:assembleDebug          # watch APK (subscriber)

The phone APK is the `full` debug variant at
`android/app/build/outputs/apk/full/debug/app-full-debug.apk`; the watch APK is at
`android/wear/build/outputs/apk/debug/wear-debug.apk`. The PoC publisher is gated on
`BuildConfig.DEBUG` (debug builds only).

## Set up a Wear OS emulator (AVD)
No watch hardware needed — a Wear AVD is enough to exercise the full data flow. The watch APK
targets API 34 (minSdk 30), so any Wear OS 4/5/6 image works; pick `x86_64` on an x86 host for
native speed. One-time setup with the SDK command-line tools (`sdkmanager`/`avdmanager` under
`$ANDROID_HOME/cmdline-tools/latest/bin`):

    # 1. fetch a Wear OS system image (Wear OS 5 / API 34, x86_64)
    sdkmanager "system-images;android-34;android-wear;x86_64"
    # 2. create the AVD (round small watch profile)
    avdmanager create avd -n wear_poc -d wearos_small_round \
        -k "system-images;android-34;android-wear;x86_64"
    # 3. boot it (headless-friendly flags; drop them to get a window)
    emulator -avd wear_poc -no-snapshot -no-audio -no-boot-anim -gpu swiftshader_indirect &
    adb -s emulator-5554 wait-for-device
    # wait until it reports booted:
    until [ "$(adb -s emulator-5554 shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do sleep 2; done

The booted watch shows up in `adb devices` as `emulator-5554` (`ro.build.characteristics=watch`).
A Wear AVD reaches the dev host at `10.0.2.2`, so no `adb reverse` is needed on the watch side —
it just works against the relay below.

## Run (adb-bridged TCP — real phone preferred, Wear AVD or real watch)
1. Start the host relay:
       python3 tools/wear-poc/relay.py
2. Phone (real, preferred) — expose the host relay on the device and install:
       adb -s <PHONE_SERIAL> reverse tcp:8787 tcp:8787
       adb -s <PHONE_SERIAL> install -r android/app/build/outputs/apk/full/debug/app-full-debug.apk
   The phone publisher connects to 127.0.0.1:8787 (WearPoc.HOST in :app). Open the noop app and
   connect the strap so LiveState produces HR.
3. Watch:
   - Wear AVD: it reaches the host at 10.0.2.2 automatically (WearPoc.HOST in :wear). Just install:
         adb -s <AVD_SERIAL> install -r android/wear/build/outputs/apk/debug/wear-debug.apk
   - Real watch: set :wear WearPoc.HOST to "127.0.0.1", rebuild :wear, then:
         adb -s <WATCH_SERIAL> reverse tcp:8787 tcp:8787
         adb -s <WATCH_SERIAL> install -r android/wear/build/outputs/apk/debug/wear-debug.apk
4. Launch "Noop Watch" on the watch. It shows "Waiting for phone…" until the first snapshot, then
   the live HR + connection + battery + last-sync, updating as the phone publishes.

Tip: list devices with `adb devices -l`. For a real watch over Wi-Fi debugging, pair first with
`adb pair <watch-ip>:<port>` then `adb connect <watch-ip>:5555`.

Verify the link without looking at the watch — the relay caches the last snapshot and replays it to
any new client, so a one-shot reader prints the current `WatchSnapshot` JSON the phone is
publishing:

    python3 -c 'import socket; s=socket.create_connection(("127.0.0.1",8787),3); print(s.recv(4096).decode())'

A line like `{"hr":72,"connected":true,"bonded":true,"batteryPct":87.5,"worn":true,...}` confirms
the phone publisher → relay path; the watch renders the same fields. `hr` is `null` until the strap
is actually streaming live HR (connection/battery/last-sync flow regardless).

## What this proves / does not prove
- Proves: the watch UI, the WatchSnapshot contract + codec, and the phone->watch data flow — all
  GMS-free.
- TCP-over-adb is dev scaffolding (emulator path). The **real** phone↔watch transport is Bluetooth
  RFCOMM — implemented and validated on hardware (see "Bluetooth transport" below).

## Bluetooth transport (implemented — VALIDATED ON HARDWARE)
The real phone↔watch link is Bluetooth RFCOMM, behind the same `WatchLink` seam. **Validated on a
Pixel 9 + Galaxy Watch 7 (Wear OS 5):** live HR flowed phone→watch over Bluetooth with no Wi-Fi,
relay, or adb in the data path. Select it with `WearPoc.TRANSPORT = BLUETOOTH` (the default); `TCP`
remains the emulator/dev fallback.

- **Phone = RFCOMM server** — `BluetoothPublisherLink` (`com.noop.wearbridge`):
  `listenUsingInsecureRfcommWithServiceRecord(name, UUID)`, accepts the watch, writes the same
  line-delimited JSON snapshots. Starts listening eagerly when the link is created.
- **Watch = RFCOMM client** — `BluetoothSubscriberLink` (`com.noop.wear`): iterates `bondedDevices`,
  `createInsecureRfcommSocketToServiceRecord(UUID)` + `connect()`, reads lines, reconnects on drop.
- Shared service UUID on both sides; `BLUETOOTH_CONNECT` declared in both manifests (the watch's is
  granted via `adb shell pm grant` for the PoC — no permission UI).
- The watch shows a **phone-link indicator** (green = fresh snapshots arriving, grey = stale/waiting).

**Gotchas that mattered (hard-won on hardware):**
- **Use the INSECURE RFCOMM variant.** A companion-app (Galaxy Wearable) bond does not expose a
  secure link key to third-party apps, so the *secure* `listen`/`connect` pair fails with
  `read failed, socket might closed`. Insecure works over the same bond. This was the fix that made
  it connect.
- **Do not call `cancelDiscovery()` before connecting** — it needs the extra `BLUETOOTH_SCAN`
  permission and throws before `connect()`. We connect to *bonded* peers only, so it isn't needed.
- The publisher only emits once the phone's `WhoopConnectionService` is running (i.e. the strap is
  connected on the phone); eager-listen lets the watch connect and show link state before HR flows.
