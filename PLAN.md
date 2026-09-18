# FuelRoute - תכנית פרויקט

אפליקציית אנדרואיד עם שני חלקים שווי-ערך:

1. **למידת הרכב (OBD-II):** התחברות לדונגל ELM327 ב-Bluetooth, רישום רציף של מהירות וצריכת דלק בזמן נסיעה (ללא קשר לניווט),
   בניית **עקומת צריכה אישית לפי מהירות** שמתחדדת עם כל נסיעה, וסטטיסטיקות מדויקות (לנסיעה, לתקופה, לפי מהירות).
2. **בחירת מסלול:** מבין המסלולים החלופיים ליעד (Google Routes API), בחירת המסלול **הזול ביותר בדלק** (+ כבישי אגרה),
   על בסיס המהירות הצפויה בכל מקטע והעקומה שנלמדה ב-(1) - או עקומת ברירת מחדל עד שנאספו די נתונים.

---

## 1. החלטות טכנולוגיות

| נושא | בחירה | הסבר |
|---|---|---|
| שפה | **Kotlin 2.x** | השפה הרשמית לאנדרואיד. עם רקע Java + Python: `data class` = dataclass, `?.`/`?:` = null-safety, lambdas, `when`, coroutines = async/await |
| UI | **Jetpack Compose + Material 3** | UI דקלרטיבי (דומה ל-React/Flutter), תמיכה מובנית ב-RTL/עברית |
| ארכיטקטורה | **MVVM + שכבות** (`ui` / `domain` / `data`) | לוגיקת הדלק ב-`domain` היא Kotlin טהור - ניתנת לבדיקות יחידה מהירות ב-JVM בלי מכשיר |
| DI | **Hilt** | הסטנדרט באנדרואיד; נכנס כבר בשלב 1 כי ה-Service, ה-transport וה-Fake דורשים החלפה נוחה |
| רשת | **Retrofit + OkHttp + kotlinx.serialization** | קריאות REST ל-Routes API |
| מפה | **Google Maps SDK + `maps-compose`** | הצגת המסלולים על מפה בתוך Compose |
| חיפוש כתובות | **Places SDK (Autocomplete)** | השלמה אוטומטית של מוצא/יעד |
| מיקום | **FusedLocationProvider** (play-services-location) | "מהמיקום הנוכחי" |
| אחסון | **DataStore** (הגדרות/פרופיל רכב) + **Room** (נסיעות, דגימות OBD, סטטיסטיקות לפי מהירות) | |
| OBD-II | **Bluetooth Classic (SPP)** ל-ELM327 + תמיכה ב-**BLE** בשלב מאוחר | רוב הדונגלים הזולים הם Classic; ראה סעיף 5 |
| רישום ברקע | **Foreground Service** עם התראה קבועה | הרישום ממשיך כשהמסך כבוי / האפליקציה ברקע |
| גרפים | **Vico** (ספריית גרפים ל-Compose) | עקומת צריכה, היסטוגרמות, מגמות |
| נתוני מסלול | **Google Routes API v2** (`computeRoutes`) | ראה סעיף 3 |
| ניווט בפועל | **Deep-link ל-Google Maps** עם waypoints | ראה סעיף 7 |
| בדיקות | JUnit5 + MockK (יחידה), Compose UI tests (מינימלי), **FakeObdTransport** לפיתוח בלי רכב | |
| Min SDK | 26 (Android 8) | מכסה ~97% מהמכשירים, מפשט הרשאות מיקום |

### למה לא Waze?
ל-Waze אין API ציבורי לחישוב מסלולים או לנתוני מהירות. ה-Waze Transport SDK הוגבל/הופסק ומאפשר רק להפעיל ניווט ליעד.
בקישור עמוק ל-Waze אפשר להעביר **רק יעד**, לא נקודות ביניים - כך שאי אפשר "לכפות" עליו מסלול נבחר.
לכן: נתונים מ-Google Routes API, וניווט דרך Google Maps (עם waypoints), עם אופציה לפתוח Waze ליעד בלבד.

### למה לא Flutter / React Native?
האפליקציה לאנדרואיד בלבד, ונשענת חזק על Google Maps/Places SDK - האינטגרציה הנייטיבית היא הפשוטה והיציבה ביותר.

---

## 2. סביבת העבודה עם OpenCode

**מה הותקן והוגדר (בוצע):**
- Android Studio (`C:\Program Files\Android\Android Studio`) - כולל JDK 21 (JBR) ב-`...\jbr`
- Android SDK ב-`%LOCALAPPDATA%\Android\Sdk`: `platform-tools` (adb), `platforms;android-35`, `build-tools;35.0.0`, `cmdline-tools`
- משתני סביבה ברמת המשתמש: `ANDROID_HOME`, `JAVA_HOME` (מצביע ל-JBR), ו-PATH כולל `adb` ו-`sdkmanager`
  - **יש לפתוח טרמינל / OpenCode מחדש** כדי שהמשתנים ייטענו

**איך OpenCode עובד עם הפרויקט:**
- OpenCode ערוך קוד ומריץ הכל דרך הטרמינל - לא צריך את Android Studio פתוח:
  - `.\gradlew.bat assembleDebug` - בנייה
  - `.\gradlew.bat installDebug` - התקנה למכשיר המחובר
  - `.\gradlew.bat testDebugUnitTest` - בדיקות יחידה (מודל הדלק)
  - `adb logcat -s FuelRoute:*` - לוגים
  - `adb shell am start -n com.fuelroute/.MainActivity` - הפעלה
- Android Studio שימושי ל: Layout Inspector, Profiler, ניהול SDK, ואשף יצירת פרויקט. אופציונלי.
- קובץ `AGENTS.md` בשורש הפרויקט מספר ל-OpenCode את הפקודות, המבנה והמוסכמות.

**המכשיר הפיזי (במקום אמולטור - הווירטואליזציה כבויה ב-BIOS):**
1. הגדרות > אודות הטלפון > לחץ 7 פעמים על "מספר Build" → "אפשרויות מפתחים"
2. הפעל "ניפוי באגים ב-USB" (ולחלופין "ניפוי באגים אלחוטי")
3. חבר USB, אשר "Allow USB debugging" בטלפון
4. `adb devices` צריך להציג את המכשיר עם `device` (לא `unauthorized`)

**Google Cloud (צריך לעשות ידנית, פעם אחת):**
1. https://console.cloud.google.com → פרויקט חדש `fuelroute`
2. Billing → חבר כרטיס אשראי (יש מכסה חינמית חודשית לכל SKU; לשימוש אישי לא תגיע לחיוב. **הגדר Budget Alert** של 5$ לביטחון)
3. APIs & Services → Enable: **Routes API**, **Maps SDK for Android**, **Places API (New)**
4. Credentials → API key → Restrict:
   - Application restrictions: Android apps → package `com.fuelroute` + SHA-1 של debug keystore (`.\gradlew.bat signingReport`)
   - API restrictions: רק שלושת ה-APIs לעיל
5. המפתח נשמר ב-`local.properties` (מחוץ ל-git) ונכנס לקוד דרך `secrets-gradle-plugin`

---

## 3. מקור הנתונים: Google Routes API

**Endpoint:** `POST https://routes.googleapis.com/directions/v2:computeRoutes`

**Headers:**
```
X-Goog-Api-Key: <KEY>
X-Goog-FieldMask: routes.routeLabels,routes.description,routes.distanceMeters,routes.duration,routes.staticDuration,routes.polyline.encodedPolyline,routes.legs.distanceMeters,routes.legs.duration,routes.legs.staticDuration,routes.legs.steps.distanceMeters,routes.legs.steps.staticDuration,routes.legs.steps.polyline.encodedPolyline,routes.legs.steps.navigationInstruction,routes.travelAdvisory.speedReadingIntervals,routes.travelAdvisory.tollInfo,routes.legs.travelAdvisory.speedReadingIntervals
```

**Body:**
```json
{
  "origin":      { "location": { "latLng": { "latitude": 32.08, "longitude": 34.78 } } },
  "destination": { "placeId": "ChIJ..." },
  "travelMode": "DRIVE",
  "routingPreference": "TRAFFIC_AWARE_OPTIMAL",
  "computeAlternativeRoutes": true,
  "departureTime": "2026-09-18T16:30:00Z",
  "extraComputations": ["TRAFFIC_ON_POLYLINE", "TOLLS"],
  "routeModifiers": { "vehicleInfo": { "emissionType": "GASOLINE" }, "tollPasses": [] },
  "languageCode": "he",
  "units": "METRIC"
}
```

**מה מקבלים:**
- עד **3 מסלולים חלופיים** (`routes[]`), כל אחד עם `legs[].steps[]`
- לכל step: `distanceMeters`, `staticDuration` (ללא תנועה), פוליליין
- לכל route/leg: `duration` (עם תנועה) ו-`staticDuration` → **פקטור עומס** = `duration / staticDuration`
- `speedReadingIntervals`: קטעים על הפוליליין עם `speed` = `NORMAL` / `SLOW` / `TRAFFIC_JAM` (אינדקסים של נקודות בפוליליין)
- `tollInfo.estimatedPrice` - עלות כביש 6 / מנהרות הכרמל וכו' (כאשר זמין)

**הערות:**
- ה-API לא מחזיר מהירות מספרית ישירה למקטע, אלא `distance/staticDuration` (מהירות "רגילה") + קטגוריות עומס. ראה סעיף 4 איך משלבים.
- ל-Google יש גם `requestedReferenceRoutes: ["FUEL_EFFICIENT"]` (eco-routing מובנה). הכיסוי הגיאוגרפי מוגבל וייתכן שלא זמין בישראל - נבדוק בשלב 1 ונשתמש בו כ-baseline להשוואה אם זמין.
- **תמחור (יש לוודא בדף התמחור העדכני):** Compute Routes Advanced (עם תנועה/חלופות/אגרה) הוא SKU בדרגת "Pro" עם מכסה חינמית של כמה אלפי קריאות בחודש. שימוש אישי = 0$.
- שגיאות שחייבים לטפל בהן: 429 (quota), 403 (key restriction), ללא רשת, ללא מסלול.

---

## 4. מודל הדלק (הלב של האפליקציה) - `domain/fuel`

### 4.1 עקומת צריכה לפי מהירות
צריכת דלק (ליטר/100 ק"מ) כפונקציה של מהירות היא U-shaped: גבוהה בעומס (מנוע לא יעיל, עצירות), מינימום ב-60-80 קמ"ש, ועולה עם התנגדות האוויר (~v²) מעל 90.

**עקומת ברירת מחדל (רכב בנזין פרטי טיפוסי, יחסית לצריכה המשולבת המוצהרת = 1.0):**

| קמ"ש | 10 | 20 | 30 | 40 | 50 | 60 | 70 | 80 | 90 | 100 | 110 | 120 | 130 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| פקטור | 2.2 | 1.65 | 1.35 | 1.15 | 1.02 | 0.94 | 0.91 | 0.93 | 0.98 | 1.06 | 1.16 | 1.28 | 1.42 |

`consumption(v) = ratedCombined_L100 * factor(v)` עם אינטרפולציה ליניארית בין הנקודות.

**פרופיל רכב:**
```kotlin
data class VehicleProfile(
    val id: String,                          // VIN אם נקרא מה-OBD, אחרת UUID
    val name: String,
    val fuelType: FuelType,                  // GASOLINE / DIESEL / HYBRID / ELECTRIC(kWh)
    val ratedCombinedL100: Double,           // צריכה משולבת מוצהרת, למשל 6.5 - בסיס לעקומת ברירת המחדל
    val engineDisplacementL: Double? = null, // נדרש רק לחישוב speed-density כשאין MAF
    val manualCurve: List<SpeedPoint>? = null, // אופציונלי: דריסה ידנית
)
```

**שלוש שכבות של עקומה, לפי סדר עדיפות:**
1. **נלמדת (OBD)** - `LearnedCurve` שנבנתה מנסיעות אמיתיות (סעיף 5). לכל פח מהירות יש רמת ביטחון לפי ק"מ שנצברו בו.
2. **ידנית** - המשתמש עורך נקודות בטבלה (למשל לפי מד הצריכה של הרכב).
3. **ברירת מחדל** - הפקטורים לעיל × הצריכה המשולבת.

**מיזוג (`CurveBlender`):** לכל מהירות `v`:
```
w(v)      = km_in_bin(v) / (km_in_bin(v) + K)       # K = 20 ק"מ: אחרי 20 ק"מ בפח - משקל 0.5, אחרי 80 - 0.8
factor(v) = w(v) * learned(v) + (1 - w(v)) * fallback(v)   # fallback = ידנית אם קיימת, אחרת ברירת מחדל
```
כך העקומה משתפרת בהדרגה: כבר מהנסיעה הראשונה המסלולים מדורגים לפי נתונים אמיתיים בטווחי המהירות שנסעת בהם, וטווחים שטרם נמדדו נשענים על ברירת המחדל.
גם צריכת הסרק (`idleLitersPerHour`) נלמדת (דגימות במהירות 0, מנוע פועל).

- **היברידי:** עקומת ברירת מחדל שטוחה יותר בעומס (פקטור 1.1-1.3 ב-10-30 קמ"ש). ב-OBD המנוע נכבה בזחילה - הדגימות נותנות צריכה 0 באותם רגעים, וזה נכון.
- **חשמלי:** אותו מודל עם kWh/100km ומחיר לקוט"ש; OBD ברכב חשמלי הוא PIDs יצרניים - מחוץ לתחום כרגע.

### 4.2 חישוב עלות למסלול
```
לכל step במסלול:
  d_km        = distanceMeters / 1000
  v_free      = d_km / (staticDuration_h)                  # מהירות ללא עומס
  congestion  = לפי speedReadingIntervals החופפים ל-step:
                  NORMAL → 1.0, SLOW → 0.55, TRAFFIC_JAM → 0.25   (ממושקל לפי אורך)
  v_eff       = v_free * congestion
  # כיול: מנרמלים v_eff כך שסכום הזמנים במקטעים = route.duration (הזמן האמיתי מגוגל)
  base_L      = d_km * consumption(v_eff) / 100
  stop_go_L   = idleLitersPerHour * (t_eff_h - t_free_h) * STOP_GO_WEIGHT(0.5)   # קנס לזחילה/עצירות מעבר לצריכה הממוצעת
  fuel_L     += base_L + stop_go_L

fuelCost  = fuel_L * pricePerLiter
tollCost  = route.travelAdvisory.tollInfo.estimatedPrice (אם קיים)
totalCost = fuelCost + tollCost
```
פלט לכל מסלול: `RouteCost(fuelLiters, fuelCost, tollCost, totalCost, durationMin, distanceKm, avgSpeed, segments[])`.

**דירוג:** ברירת מחדל לפי `totalCost`. אפשרות: "שווי זמן" - המשתמש מגדיר כמה שקלים שווה לו דקה, והדירוג לפי `totalCost + minutes * valuePerMinute`.
כך מסלול שחוסך 2 ש"ח אך לוקח 25 דקות יותר לא ייבחר אוטומטית.

### 4.3 בדיקות יחידה (JVM, בלי מכשיר)
- אינטרפולציה: `consumption(65)` בין 60 ל-70
- קצוות: v < 10, v > 130 (clamp)
- מסלול עירוני קצר vs. כביש מהיר ארוך - ודא שהתוצאות סבירות (סדר גודל 5-10 ל/100)
- פקטור עומס: אותו מרחק, TRAFFIC_JAM צריך לעלות יותר מ-NORMAL
- JSON אמיתי מ-Routes API (fixture) → פרסור → חישוב → מספר סופי

### 4.4 שיפורים עתידיים למודל
- **טופוגרפיה:** Elevation API על נקודות הפוליליין → תוספת/הנחה לפי שיפוע (ירושלים↔ת"א זה משנה). עם OBD+GPS אפשר גם ללמוד את השפעת השיפוע ישירות.
- **מזג אוויר / מזגן:** תוספת קבועה לצריכה בסרק בקיץ (ניתן ללמוד מ-OBD לפי חודש).
- **פחי מהירות דו-ממדיים:** (מהירות × תאוצה) או (מהירות × עומס מנוע) - מבדיל בין 30 קמ"ש בזחילה ל-30 קמ"ש קבוע.

---

## 5. למידת הרכב מ-OBD-II - `data/obd` + `domain/learning`

### 5.1 חומרה ותקשורת
- **דונגל:** ELM327 תואם (או OBDLink / Vgate vLinker) עם **Bluetooth Classic**. אזהרה: קלונים זולים שמסומנים "v2.1" ידועים בפקודות שבורות ובקצב איטי; לחפש "v1.5" או צ'יפ מקורי (STN/OBDLink). ~50-150 ₪.
- **תקשורת:** `BluetoothSocket` על SPP UUID `00001101-0000-1000-8000-00805F9B34FB`. ה-transport מופשט מאחורי interface כדי להוסיף BLE (GATT, שירות `FFF0`/`FFE0` - תלוי דונגל) בהמשך.
- **הרשאות:** Android 12+: `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`; מתחת: `BLUETOOTH`, `BLUETOOTH_ADMIN`, `ACCESS_FINE_LOCATION`. ל-Foreground Service: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `POST_NOTIFICATIONS`.
- **חיבור:** המשתמש מצמיד את הדונגל בהגדרות Bluetooth של אנדרואיד פעם אחת; האפליקציה מציגה את המכשירים המוצמדים ושומרת את הבחירה. אפשרות "התחבר אוטומטית" כשהדונגל נראה.

### 5.2 פרוטוקול (שכבה `ElmProtocol`)
```
אתחול:  ATZ → ATE0 (no echo) → ATL0 → ATS0 → ATH0 → ATSP0 (auto protocol) → 0100 (בדיקת PIDs נתמכים)
לולאה (2-4 Hz, מוגבל בקצב הדונגל):
  010D  מהירות [km/h]                 ← תמיד
  010C  סל"ד                           ← זיהוי מנוע פועל/כבוי (היברידי!)
  0110  MAF [g/s]                      ← דרך א' לצריכה
  015E  Engine fuel rate [L/h]         ← דרך ב' (ישיר, לא בכל רכב)
  010B  MAP, 010F  IAT, 0104 load      ← דרך ג' (speed-density) כשאין MAF
  0105  טמפ' מנוע                     ← סינון מנוע קר
  012F  מפלס דלק [%]                   ← כיול גס לאורך זמן
  0902  VIN                            ← פעם אחת, לזיהוי הרכב
```
**חישוב קצב צריכה (`FuelRateCalculator`, בסדר עדיפות):**
1. אם `015E` נתמך → L/h ישירות.
2. אחרת MAF: `fuel_g_s = MAF / AFR` (בנזין 14.7, דיזל ~14.5 אך לא סטויכיומטרי - להשתמש ב-lambda `0144` אם זמין), `L_h = fuel_g_s * 3600 / density` (בנזין 745 g/L, דיזל 832).
3. אחרת speed-density: `airflow = MAP * RPM * displacement * VE / (R * IAT)` → כמו 2. דורש נפח מנוע; מסומן "דיוק נמוך".
4. **כיול מול תדלוק:** המשתמש מזין ליטרים בתדלוק מלא → `k = ליטרים_בפועל / ליטרים_מחושבים_מאז_התדלוק_הקודם` → מכפיל תיקון לרכב (MAF נוטה לסטייה של 5-15%). `k` נשמר בפרופיל ומוצג למשתמש.

### 5.3 אגרגציה ולמידה (`domain/learning`, Kotlin טהור)
לכל דגימה `(t, speed, fuelRate_L_h)` (עם dt מהדגימה הקודמת, קצוב ל-2 שניות):
```
distance_km = speed * dt_h
fuel_L      = fuelRate * dt_h
bin         = speed קמ"ש בפחים של 5 (0-5, 5-10, ..., 135-140); bin 0 = סרק
SpeedBinStats[vehicle, bin] += (distance_km, fuel_L, seconds, samples)
L100(bin)   = fuel_L / distance_km * 100          # לפחים > 0
idle_L_h    = fuel_L / hours                       # לפח 0, רק כש-RPM > 0
```
- **סינון:** מנוע קר (< 60°C) נרשם אך מסומן ולא נכנס לעקומה כברירת מחדל; דגימות עם dt > 2s (ניתוק) נזרקות; ערכים לא הגיוניים (MAF < 0, speed > 250) נזרקים.
- **LearnedCurve** = לכל פח: `L100`, `km` (=ביטחון), `sd` (סטיית תקן בין נסיעות - לתצוגת רצועת אי-ודאות).
- הסטטיסטיקות מתעדכנות באופן אינקרמנטלי בזמן אמת (סכומים בלבד, ללא צורך לעבור על כל הדגימות).
- **דגימות גולמיות** נשמרות ב-Room עם retention (ברירת מחדל 90 יום) - לחישוב מחדש אם המודל ישתנה, ולניתוחים עתידיים.

### 5.4 מסכי OBD וסטטיסטיקה
- **חיבור:** רשימת מכשירים מוצמדים, סטטוס (מנותק / מתחבר / מחובר / שגיאה), PIDs נתמכים ברכב, VIN, כפתור "התחל/עצור רישום", מתג "חיבור אוטומטי".
- **לוח מחוונים חי (בזמן נסיעה):** מהירות, צריכה רגעית (L/100 + L/h), ממוצע לנסיעה, ליטרים ו-₪ מתחילת הנסיעה, ק"מ, טמפ' מנוע. טקסט גדול, מותאם לחזקה ברכב.
- **עקומת הרכב שלי:** גרף L/100 מול מהירות - העקומה הנלמדת (עם רצועת אי-ודאות) על רקע ברירת המחדל/הידנית; פס ביטחון לכל פח (ק"מ); כפתור "אמץ לעקומה" שממיר את הנלמדת לידנית (snapshot) ו-"אפס למידה".
- **סטטיסטיקות:** נסיעות (תאריך, ק"מ, ליטרים, ₪, ממוצע L/100, מהירות ממוצעת), סיכומים לשבוע/חודש, התפלגות זמן/דלק לפי מהירות ("42% מהדלק נשרף מתחת ל-30 קמ"ש"), מגמה של L/100 לאורך זמן, "המהירות היעילה ביותר שלך: 72 קמ"ש".
- **תדלוק:** רישום תדלוק (ליטרים, ₪, מלא/חלקי) → מקדם כיול + מחיר ליטר מתעדכן אוטומטית.

### 5.5 Foreground Service - `ObdLoggingService`
- מופעל ידנית או אוטומטית בחיבור לדונגל; התראה קבועה עם צריכה נוכחית ומרחק.
- זרימה: `ObdTransport` → `ElmProtocol` → `Flow<ObdSample>` → `FuelRateCalculator` → `TripRecorder` (Room) + `SpeedBinAggregator` → `StateFlow<LiveState>` ל-UI.
- **זיהוי נסיעה:** מתחילה בחיבור/RPM > 0; מסתיימת אחרי 3 דקות של RPM = 0 או ניתוק. GPS אופציונלי לשמירת מסלול הנסיעה (מרחק מ-GPS משמש גם cross-check למהירות OBD).
- התנתקות: ניסיון חיבור מחדש עם backoff; הנסיעה ממשיכה אם חוזר תוך 3 דקות.

### 5.6 פיתוח ובדיקות בלי רכב
- `FakeObdTransport`: משחק תסריט (קובץ CSV/JSON של תגובות ELM) בקצב אמיתי או מואץ. מאפשר ל-OpenCode לבנות ולבדוק את כל הזרימה על המכשיר בבית.
- **בדיקות יחידה:** פרסור תגובות ELM (`41 0D 3C` → 60 קמ"ש, multi-frame VIN, `NO DATA`, `SEARCHING...`, אקו), `FuelRateCalculator` בכל שלוש הדרכים, `SpeedBinAggregator` (נסיעה סינתטית: 10 ק"מ ב-90 קמ"ש עם 6 L/h → 6.67 L/100 בפח 90), `CurveBlender` (w=0 → ברירת מחדל; אחרי הרבה ק"מ → נלמדת).
- **בשטח:** נסיעת כיול ראשונה של ~30 דקות עם מגוון מהירויות; השוואת ליטרים מחושבים מול מד הצריכה של הרכב.

---

## 6. ארכיטקטורה ומבנה הפרויקט

```
fuel/
├── AGENTS.md                     # הנחיות ל-OpenCode
├── PLAN.md                       # המסמך הזה
├── settings.gradle.kts
├── build.gradle.kts
├── gradle/libs.versions.toml     # ניהול גרסאות (version catalog)
├── local.properties              # MAPS_API_KEY=... (לא ב-git!)
└── app/
    ├── build.gradle.kts
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   └── java/com/fuelroute/
        │       ├── FuelRouteApp.kt            # @HiltAndroidApp
        │       ├── MainActivity.kt            # Compose host + NavHost + Bottom bar (מסלול | רכב | סטטיסטיקה)
        │       ├── ui/
        │       │   ├── theme/
        │       │   ├── home/       HomeScreen.kt, HomeViewModel.kt        # מוצא/יעד, זמן יציאה, כפתור חישוב
        │       │   ├── results/    ResultsScreen.kt, ResultsViewModel.kt  # מפה + כרטיסי מסלולים מדורגים
        │       │   ├── obd/        ObdConnectScreen.kt, LiveDashboardScreen.kt, ObdViewModel.kt
        │       │   ├── curve/      CurveScreen.kt, CurveViewModel.kt      # עקומה נלמדת/ידנית/ברירת מחדל, גרף
        │       │   ├── stats/      StatsScreen.kt, TripDetailScreen.kt, StatsViewModel.kt
        │       │   ├── refuel/     RefuelScreen.kt                        # רישום תדלוק + כיול
        │       │   ├── vehicle/    VehicleScreen.kt, VehicleViewModel.kt  # פרופיל רכב(ים)
        │       │   ├── settings/   SettingsScreen.kt                      # מחיר דלק, שווי זמן, אפליקציית ניווט, retention
        │       │   └── history/    HistoryScreen.kt                       # חיפושי מסלול קודמים + "חסכת X ₪"
        │       ├── domain/
        │       │   ├── model/      Route.kt, Segment.kt, RouteCost.kt, VehicleProfile.kt, ObdSample.kt, Trip.kt, SpeedBinStats.kt
        │       │   ├── fuel/       DefaultCurve.kt, ConsumptionCurve.kt, CurveBlender.kt, FuelModel.kt, CongestionModel.kt
        │       │   ├── learning/   FuelRateCalculator.kt, SpeedBinAggregator.kt, LearnedCurve.kt, TripDetector.kt, RefuelCalibrator.kt
        │       │   ├── obd/        ElmProtocol.kt, PidParser.kt, Pid.kt     # Kotlin טהור - פרסור בלבד, ללא Bluetooth
        │       │   ├── ranking/    RouteRanker.kt
        │       │   └── usecase/    ComputeCheapestRouteUseCase.kt, GetEffectiveCurveUseCase.kt
        │       ├── data/
        │       │   ├── obd/        ObdTransport.kt (interface), BluetoothClassicTransport.kt, FakeObdTransport.kt, ObdSession.kt
        │       │   ├── routes/     RoutesApi.kt (Retrofit), RoutesDto.kt, RoutesMapper.kt, RoutesRepository.kt
        │       │   ├── places/     PlacesRepository.kt
        │       │   ├── location/   LocationRepository.kt
        │       │   ├── vehicle/    VehicleRepository.kt (Room)
        │       │   ├── price/      FuelPriceRepository.kt (ידני / מתדלוקים; אופציה: מחיר 95 מפוקח מ-data.gov.il)
        │       │   └── db/         AppDatabase.kt, TripDao.kt, ObdSampleDao.kt, SpeedBinDao.kt, RefuelDao.kt, RouteSearchDao.kt
        │       ├── service/        ObdLoggingService.kt    # Foreground Service
        │       ├── nav/            NavigationLauncher.kt   # deep-links ל-Google Maps / Waze
        │       └── di/             AppModule.kt, NetworkModule.kt, ObdModule.kt (Fake vs Real לפי build flavor/flag)
        └── test/java/com/fuelroute/domain/   # בדיקות יחידה + fixtures (JSON של Routes, לוגי ELM)
```

**זרימת נתונים - למידה (רצה תמיד כשמחוברים):**
`ObdLoggingService` → `ObdTransport.readLine()` → `ElmProtocol` (Kotlin טהור) → `Flow<ObdSample>`
→ `FuelRateCalculator` → `TripDetector` + `SpeedBinAggregator` → Room (`obd_sample`, `speed_bin_stats`, `trip`)
→ `StateFlow<LiveState>` → `LiveDashboardScreen` / התראה.

**זרימת נתונים - מסלול:**
`HomeScreen` → `HomeViewModel.compute()` → `ComputeCheapestRouteUseCase`
→ `RoutesRepository.getAlternatives()` (Retrofit → DTO → `Route` domain)
→ `GetEffectiveCurveUseCase` (`CurveBlender`: נלמדת + ידנית/ברירת מחדל)
→ `FuelModel.cost(route, curve, price)` לכל מסלול → `RouteRanker.rank()`
→ `StateFlow<ResultsUiState>` → `ResultsScreen` (מפה עם 3 פוליליינים בצבעים, הזול מודגש, תווית "מבוסס על X ק"מ של נתוני רכב אמיתיים").

---

## 7. מסירה לניווט (Hand-off)

**Google Maps עם נקודות ביניים** (הדרך היחידה "לכפות" מסלול):
```
https://www.google.com/maps/dir/?api=1
  &origin=32.08,34.78
  &destination=31.77,35.21
  &waypoints=32.01,34.85|31.90,35.00|31.82,35.10
  &travelmode=driving
```
- בוחרים 3-6 נקודות מהפוליליין של המסלול הנבחר, **בנקודות שבהן המסלול נפרד מהחלופות** (אלגוריתם: מוצאים נקודות במסלול הנבחר שרחוקות >300 מ' מכל מסלול אחר, ובוחרים את המרכזיות שבהן).
- Google Maps יעצור בכל waypoint ("הגעת לנקודת ביניים") - זה חסרון UX ידוע; לפחות המסלול נשמר. נבדוק בשלב 5 האם `dir/` עם waypoints מתייחסת אליהן כ-via.

**Waze:** `waze://?ll=31.77,35.21&navigate=yes` - יעד בלבד. מוצג כאופציה משנית עם אזהרה "Waze יבחר מסלול בעצמו".

---

## 8. שלבי פיתוח (Milestones)

שני מסלולי עבודה מקבילים - **A: למידת רכב (OBD)** ו-**B: בחירת מסלול** - שנפגשים בשלב 5. מסלול A קודם, כי ככל שמתחילים לאסוף נתונים מוקדם יותר, העקומה מדויקת יותר כשמסלול B מוכן.

### שלב 0 - הכנות (רובו בוצע)
- [x] Android Studio + SDK + adb + JAVA_HOME
- [ ] פרויקט Google Cloud + API key מוגבל
- [ ] מכשיר עם USB debugging מחובר (`adb devices`)
- [ ] רכישת דונגל ELM327 Bluetooth Classic (v1.5 / OBDLink)

### שלב 1 - שלד הפרויקט (יום)
- פרויקט Compose, Kotlin DSL, version catalog, Hilt, Navigation Compose עם Bottom bar (מסלול | רכב | סטטיסטיקה)
- Room + DataStore מוגדרים, `VehicleProfile` בסיסי (שם, סוג דלק, צריכה משולבת)
- `secrets-gradle-plugin` + `local.properties`
- **Milestone:** האפליקציה עולה על המכשיר עם 3 טאבים ריקים ופרופיל רכב נשמר

### שלב 2A - ליבת ה-OBD (2-3 ימים)
- `domain/obd`: `ElmProtocol`, `PidParser` + בדיקות יחידה על תגובות אמיתיות/מוקלטות
- `domain/learning`: `FuelRateCalculator` (3 הדרכים), `SpeedBinAggregator`, `TripDetector` + בדיקות
- `data/obd`: `ObdTransport` interface, `FakeObdTransport` (משחק תסריט), `BluetoothClassicTransport`
- `ObdLoggingService` (Foreground) + הרשאות Bluetooth/התראות
- מסך חיבור + לוח מחוונים חי
- **Milestone:** בבית - Fake מזרים נתונים ומראה מהירות/צריכה; ברכב - חיבור אמיתי, VIN נקרא, מהירות נכונה, צריכה בסדר גודל של מד הרכב

### שלב 3A - עקומה נלמדת וסטטיסטיקות (2-3 ימים)
- `LearnedCurve`, `CurveBlender`, `RefuelCalibrator` + בדיקות
- מסך "עקומת הרכב שלי" עם גרף (Vico): נלמדת + רצועת אי-ודאות + ברירת מחדל/ידנית, ביטחון לפח, "אמץ", "אפס"
- מסך סטטיסטיקות: נסיעות, סיכומים, התפלגות לפי מהירות, מגמה
- מסך תדלוק + מקדם כיול
- **Milestone:** אחרי נסיעה אחת - העקומה מתעדכנת ורואים "המהירות היעילה ביותר שלך"

### שלב 2B - Routes API + מודל דלק למסלול (יומיים)
- Retrofit + DTOs ל-Routes API, מוצא/יעד קשיחים (ת"א → ירושלים), שמירת JSON כ-fixture
- `FuelModel`, `CongestionModel`, `RouteRanker` על `ConsumptionCurve` (מקבל כל עקומה - ברירת מחדל או ממוזגת)
- כרטיסי מסלול: ליטרים, ₪ דלק, ₪ אגרה, זמן, הזול מודגש
- **Milestone:** 2-3 מסלולים אמיתיים מדורגים לפי עלות, בדיקות יחידה ירוקות

### שלב 3B - מפה, חיפוש, מיקום (2-3 ימים)
- Maps Compose עם 3 פוליליינים (הזול בולט)
- Places Autocomplete, "מיקום נוכחי", בורר זמן יציאה
- **Milestone:** זרימה מלאה מחיפוש ועד תוצאות על מפה

### שלב 4 - חיבור A+B (יום)
- `GetEffectiveCurveUseCase`: דירוג המסלולים משתמש בעקומה הממוזגת של הרכב הפעיל
- בתוצאות: "מבוסס על X ק"מ של נתוני רכב אמיתיים" / "עקומת ברירת מחדל - חבר OBD לדיוק"
- **Milestone:** נסיעה משנה את הדירוג של המסלולים

### שלב 5 - מסירה לניווט + הגדרות (1-2 ימים)
- `NavigationLauncher`: waypoints → Google Maps; Waze ליעד
- הגדרות: מחיר ליטר (ידני / מתדלוק אחרון), שווי זמן ₪/דקה, אפליקציית ניווט, retention דגימות, ריבוי רכבים (לפי VIN)
- **Milestone:** לחיצה על "נווט" פותחת את המסלול הנכון

### שלב 6 - ליטוש (2-3 ימים)
- היסטוריית חיפושים: "חסכת X ₪ לעומת המסלול המהיר"; אחרי נסיעה עם OBD - השוואת חיזוי מול צריכה אמיתית (זה גם מדד לדיוק המודל)
- טיפול בשגיאות: אין רשת, quota, אין מסלול, הרשאות נדחו, דונגל לא מגיב, PIDs לא נתמכים
- עברית/RTL, ערכת נושא כהה, אייקון, splash, ייצוא CSV של נסיעות
- APK חתום (release) להתקנה אישית

### שלב 7 - אופציונלי
- BLE transport (דונגלים חדשים)
- Elevation API ושיפועים; למידת שיפוע מ-GPS+OBD
- פחים דו-ממדיים (מהירות × תאוצה)
- Widget / Quick Settings tile "הביתה בזול"
- השוואה ל-eco-route של גוגל (`FUEL_EFFICIENT`) אם זמין בישראל

---

## 9. סיכונים ופתרונות

| סיכון | פתרון |
|---|---|
| דונגל ELM327 קלון - פקודות שבורות / איטי / מתנתק | לרכוש v1.5 או OBDLink; `ElmProtocol` סובלני ל-`SEARCHING...`, `NO DATA`, `?`; reconnect עם backoff |
| הרכב לא מדווח MAF ולא fuel rate | speed-density עם נפח מנוע; כיול מול תדלוק מתקן את השגיאה השיטתית |
| סטייה שיטתית בחישוב הדלק (5-15%) | `RefuelCalibrator` - מקדם תיקון אחרי כל תדלוק מלא; מוצג למשתמש |
| Android הורג את ה-Service ברקע | Foreground Service עם התראה; בקשת פטור מאופטימיזציית סוללה; בדיקה על המכשיר הספציפי |
| מעט נתונים בטווחי מהירות מסוימים (130 קמ"ש) | `CurveBlender` נופל לברירת מחדל לפי ביטחון; UI מציג איפה חסרים נתונים |
| חיוב לא צפוי ב-Google Cloud | Budget alert, API restrictions, cache תוצאות ל-10 דקות לאותו מוצא/יעד |
| Google Maps לא שומר על המסלול עם waypoints | להציג את המסלול במפה בתוך האפליקציה כגיבוי; לשקול הצגת הוראות step-by-step מה-API |
| מפתח API בתוך ה-APK | הגבלת package+SHA-1; זו אפליקציה אישית, לא לפרסום בחנות |
| Java 24 של Oracle ב-PATH המערכתי | Gradle משתמש ב-`JAVA_HOME` (JBR 21) - מוגדר. אם יש בעיה: `org.gradle.java.home` ב-`gradle.properties` |
