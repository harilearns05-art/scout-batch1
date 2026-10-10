# Highway Scout (Android) - Batch 1

Hands-free pothole + speed-breaker detector. Runs as a foreground service, so it keeps
detecting and speaking warnings with the screen off.

## 1. Build the app (laptop)
1. Install **Android Studio** (it includes the JDK 17 you need).
2. File > Open > select this `HighwayScout` folder. Wait for "Gradle sync" to finish (needs internet).
3. Phone: Settings > About phone > tap *Build number* 7 times > Developer options > turn on **USB debugging**.
4. Plug the phone in, press the green **Run** button.
   To give the app to the second phone: Build > Build Bundle(s)/APK(s) > **Build APK(s)**, send
   `app/build/outputs/apk/debug/app-debug.apk` by WhatsApp/USB and install it.

## 2. Share data between the two phones (Supabase, free)
1. supabase.com > New project.
2. SQL Editor > paste `supabase.sql` > Run.
3. Project Settings > API: copy the **Project URL** and the **anon public** key.
4. Paste them into `SUPABASE_URL` and `SUPABASE_KEY` at the top of
   `app/src/main/java/com/highwayscout/Store.kt`, rebuild, and install on BOTH phones.
Leave them blank to test on one phone only (data stays on that phone).

## 3. Phone settings (important)
- Allow **Location** (precise) and Notifications when asked.
- Tap "Allow" on the battery prompt (or set the app to **Unrestricted** battery).
- Turn the media volume up. Mount the phone firmly, screen up or facing you, not loose in a pocket.

## 4. Test plan
1. Walk test (outside): turn on Solo test + Walk test, sensitivity High. START, jerk the phone down once.
   The log shows "New pothole". Walk 200 m away, turn round, walk back: you get beep + voice ~150 m before it.
2. Two phones: turn Solo test OFF. Hit the same spot from 3 different vehicle trips (2 for speed breakers).
3. Real drive: Solo and Walk test OFF, sensitivity Medium. After the drive, the log lines show strength
   and pattern of each hit. Use them to tune the numbers in `onSensorChanged` / `classify`.

## Batch 1 features
- Learns slow-down zones, hard-braking spots and sharp turns by itself (3 different vehicles confirm each one).
- "Mark this spot" buttons: Gate, Junction, Water, Slippery (shared at once).
- After a warning, buttons "Yes, it was there" / "Not there" clean up false alarms.
- Sleepy check: asks "are you awake?" every 2 h (30 min at night, 1 h early morning). Tap I'M AWAKE
  (screen or notification). No answer = loud alarm. It is a reminder to rest, not a medical detector.
- IMPORTANT: if you already ran the old supabase.sql, run the new one again (it adds a column).
