# Personal Health Dashboard — Build Spec

## How to use this file
Put this file in an empty project folder, open Claude Code there, and say:
"Read SPEC.md. Set up the project, then build Phase 1. Ask me before anything that isn't covered here."

---

## 1. What this app is
A personal Android app (single user: me) on a Samsung Galaxy S26 Ultra.

**Primary goal:** bring my A1c from the prediabetic range back to normal (< 5.7%) through steady, low glucose.
**Secondary goal:** support my endurance training — a marathon, then an 80-mile bike race, then a 200-mile relay.

It connects my CGM, Garmin watch, smart scale, and photo food log in one dashboard, shows trends over time against targets, and includes a training plan builder for each race.

Not for public release. Installed directly on my phone (sideloaded APK). No Play Store, no backend server, no accounts.

**About me (context for design decisions)**
- Prediabetic A1c despite a lot of endurance training, so steady everyday glucose matters more than workout spikes.
- I run a lot and fuel with carbs during long sessions; carbs around training are expected, not a problem to flag.
- Devices: Samsung Galaxy S26 Ultra, Garmin watch, CGM (brand TBD), budget Bluetooth smart scale (Renpho or Fitindex).
- I want AI to stick strictly to facts and be as consistent as possible (see Section 2).

---

## 2. Core rule: facts come from code, not AI
This is the most important rule in the project. Follow it everywhere.

1. **All numbers are computed by deterministic code.** Averages, scores, estimates, rankings, forecasts, plans, fueling targets — all plain Kotlin logic with unit tests.
2. **AI is used for only two things:**
   - **Food photo recognition:** naming the foods and estimating portions. Nothing else.
   - **Optional rewording:** turning facts that code already computed into natural sentences. Off by default.
3. **AI never produces a number shown on the dashboard.** Nutrition values (carbs, protein, fat, fiber, calories) come from the USDA FoodData Central database, looked up by the food name the AI returned.
4. **The user confirms every AI food result** (food names and portions) before it is saved. Editable at any time.
5. **AI gets only verified inputs.** Rewording calls receive a JSON object of computed facts and must use nothing else. If the facts don't support a statement, the output must say "not enough data."
6. **Validate AI output in code.** Every AI response must match a strict JSON schema. For rewording, extract every number from the text and confirm it exactly matches a value in the input facts. On any mismatch, discard the AI text and show the template version instead.
7. **Make results repeatable.** Temperature 0. Structured JSON output. Save each AI result once with its input hash; re-viewing shows the saved result, never a new call.
8. **Every insight is traceable.** Each statement on screen links to the data behind it (the meals, days, or chart).
9. **Label all AI-derived content** with a small "AI" tag.
10. **Template first.** Every summary and explanation has a code-generated template version that works with AI fully disabled. The app must be fully functional with no AI except food recognition.

---

## 3. Tech stack
- Kotlin, Jetpack Compose, Material 3
- Android Health Connect API for reading health data
- Room database for local storage
- WorkManager for background sync and scheduled jobs
- Vico (or similar Compose charting library) for charts
- Claude API (vision) for food photo recognition; model name stored in config, not hardcoded
- USDA FoodData Central API for nutrition values (free API key)
- API keys stored with Android Keystore / EncryptedSharedPreferences; never in source code
- All data stays on the phone. Optional encrypted export/backup file.
- Minimum SDK: whatever current Health Connect requires; target latest stable SDK

Architecture: MVVM, a repository layer per data source, pure-Kotlin calculation modules with unit tests (no Android dependencies in the math).

---

## 4. Data sources (all via Health Connect)
Read-only. Build a Settings screen showing each source, last sync time, and which data types are arriving.

| Data | Source | Notes |
|---|---|---|
| Blood glucose | CGM app (brand TBD) → Health Connect | Source-agnostic. Official apps (e.g. Dexcom G7) may sync with a delay of about 3 hours; handle gaps and late-arriving data, and recompute meal responses when data arrives. Unofficial apps like xDrip+ or Juggluco can write near-live data; support them the same way. Allow choosing which app's glucose to trust if several write it. |
| Steps | Garmin Connect → Health Connect | |
| Heart rate, resting HR, HRV | Garmin Connect → Health Connect | |
| Sleep (stages) | Garmin Connect → Health Connect | |
| Workouts (run, ride, etc.) | Garmin Connect → Health Connect | Type, start/end, distance, HR |
| VO2 max | Garmin Connect → Health Connect if available | If not present, provide quick manual entry |
| Weight, body fat %, BMI | Renpho or Fitindex app → Samsung Health (or direct) → Health Connect | Budget 4-electrode Bluetooth scale. Expect only weight, body fat %, and BMI to sync reliably. |
| Lean mass / muscle | Scale app if it syncs; otherwise derived | If lean or muscle mass isn't in Health Connect, compute lean mass = weight × (1 − body fat %) and label it "derived." Trends matter more than absolute values. |
| Lab results (A1c, fasting glucose) | Manual entry | Date + value |
| Training readiness, Body Battery, training load | Not available via Health Connect | Optional manual entry; do not block on these |

---

## 5. Calculations (deterministic code, unit tested)
All targets live in an editable settings table, not hardcoded.

**Glucose**
- Mean glucose, GMI (estimated A1c) = 3.31 + 0.02392 × mean glucose (mg/dL), over 14, 30, and 90 days
- Time in tight range (70–140 mg/dL), time below 70, time above 140 and 180
- Variability: standard deviation and coefficient of variation (CV%)
- Fasting glucose: average of a configurable early-morning window before first meal
- Split every glucose metric three ways:
  - **Training:** during workouts and up to 60 min after (configurable)
  - **Overnight:** sleep window from Garmin, or a configurable default
  - **Everyday:** everything else
- Data-sufficiency check: show "not enough data" if coverage is below 70% for the period

**Meals**
- Meal response, per logged meal: baseline (mean of 15 min before), peak rise, time to peak, time back to baseline, area under the curve for 2 hours
- Walk effect: did steps exceed a threshold (e.g. 1,000 steps or a workout) within 0–45 min after the meal
- Meal response score 0–100 from those metrics (document the formula in code)
- Meal ranking: group by food, rank by average response; require at least 2 instances
- Walk vs. no-walk comparison for the same food
- Pre-meal forecast: average response for that food from my history, split by walk/no-walk and time of day. Show sample count. No forecast if fewer than 2 past instances.
- Unlogged meal detection: glucose rise above a threshold with no meal logged within 30 min → prompt to log

**Body**
- 7-day rolling averages for weight, body fat, muscle/lean mass
- Under-fueling flag (rule-based): weight down more than a set % in 2 weeks AND HRV trending down AND more lows than usual. Show as a gentle notice, not an alarm.

**Context**
- Link each day's glucose metrics to previous night's sleep, HRV, and workouts so the user can filter and compare.

---

## 6. Screens
Clean, dense, data-rich. Dark mode supported. Every chart shows the target band shaded.

1. **Today**
   - Top cards: current glucose + trend, today's time in range, steps, latest weight/body fat
   - 24-hour glucose chart with meal photos and workouts pinned on the timeline
   - Today's planned workout and its fueling note
2. **A1c Progress**
   - 90-day GMI line moving toward the 5.7% target, with lab A1c results as markers
   - Comparison of latest lab A1c vs. GMI over the same period, with a plain note if they differ by more than 0.5
   - Fasting glucose trend
3. **Meals**
   - Log with photo, food names, portions, nutrition, and its glucose curve
   - Best-to-worst food ranking
   - Walk vs. no-walk overlay charts
4. **Training**
   - Calendar of planned and completed workouts
   - Glucose overlaid on each workout, with fueling marks
   - VO2 max trend, training volume by week
5. **Body**
   - Weight, body fat, muscle/lean mass trends (7-day averages)
   - Muscle vs. glucose variability chart over months
6. **Trends**
   - Every metric in 7 / 30 / 90-day views against its target
   - Filters: training vs. everyday vs. overnight; good-sleep vs. poor-sleep days
7. **Weekly Summary**
   - Code-generated template; optional AI rewording (with validation)
8. **Settings**
   - Data sources and sync status, targets, API keys, AI toggles, export/backup

---

## 7. Food logging flow
1. Take or pick a photo (timestamp from photo or now; editable).
2. Send to Claude vision. Prompt returns strict JSON only: list of `{food_name, portion_estimate, portion_unit, confidence}`. No nutrition numbers. Temperature 0.
3. Validate JSON against schema; on failure, retry once, then fall back to manual entry.
4. Look up each food in USDA FoodData Central; show the top matches for the user to pick.
5. User confirms or edits foods and portions → save. Nutrition computed by code from the database values.
6. Also support: saved "usual meals," manual text entry, and tags (alcohol, caffeine, ate veggies/protein first, late meal, sick, stressed).
7. Store photos locally, compressed.

---

## 8. Training plan builder (deterministic, rule-based)
One plan per race; plans chain in sequence with recovery weeks between.

**Inputs:** race type, date, goal (finish / time), current weekly volume (auto-filled from Garmin workouts), days available per week, long-workout day, strength days.

**Race types**
- Marathon (run)
- 80-mile bike race (ride)
- 200-mile relay (repeated run legs with short recovery; include back-to-back and double-day sessions)

**Rules (all values editable in settings)**
- Phases: base → build → peak → taper → race → recovery
- Weekly volume increase capped at ~10%; every 3rd or 4th week is a lighter week
- Long-session progression capped per week
- Taper length by race type
- Hard days separated by easy days
- 1–2 strength sessions per week included by default
- Workouts have type, duration/distance, and target pace/HR/power zones derived from recent data

**Adjustments (rules, no AI)**
- Missed workout → reschedule or drop per fixed rules
- Poor sleep or HRV below my baseline → swap that day's hard session for easy

**Fueling notes per workout (rules)**
- Carbs-per-hour targets by duration and intensity from an editable table
- After each long workout, compare fueling taken vs. glucose curve

Export workouts as a simple list I can enter into Garmin manually (Garmin workout push requires a developer API, out of scope).

---

## 9. Notifications
- Post-meal walk nudge ~10 min after logging (configurable, can be turned off)
- Unlogged meal prompt
- Weekly summary ready
- Lab test reminder every 3 months

Keep notifications few; all are toggleable.

---

## 10. Doctor report
One-tap PDF: 90-day GMI vs. lab A1c, time in range, fasting trend, variability, best/worst foods, training volume. Template-generated only, no AI text.

---

## 11. Build phases
Build in order. Each phase must run on my phone before moving on.

**Phase 1: Core loop**
- Project setup, Health Connect permissions, glucose + steps + workouts reading
- Food photo logging flow (Section 7)
- Today screen, Meals screen, meal response and walk effect calculations
- Unit tests for all calculations

**Phase 2: Glucose depth**
- A1c Progress screen, glucose split (training/everyday/overnight), lab entry, Trends screen
- Sleep and HRV context, post-meal nudge

**Phase 3: Training**
- Training screen, plan builder, adjustments, fueling notes

**Phase 4: Body and reports**
- Body screen, under-fueling flag, weekly summary, doctor PDF, export/backup

---

## 12. Quality rules
- Unit tests for every calculation, with hand-checked example data
- A fake data generator so screens can be built and tested before real data arrives
- Handle missing data gracefully everywhere; never show a number computed from insufficient data
- No analytics, no tracking, no network calls except Claude (food photos) and USDA (nutrition lookup)
- This is a personal tool, not medical advice; show a one-line note on the A1c screen to discuss results with my doctor

---

## 13. Phone setup checklist (for me, before Phase 1 testing)
- Garmin Connect: Settings → Connected Partners → Health Connect → allow steps, heart rate, sleep, workouts (and VO2 max if offered).
- CGM app: turn on Health Connect sharing for blood glucose.
- Scale app (Renpho or Fitindex): turn on Athlete Mode; connect to Samsung Health or Health Connect. After the first weigh-in, check Health Connect for which metrics arrived.
- Get a Claude API key and a free USDA FoodData Central API key; enter both in the app's Settings screen.
- Enable Developer Options and USB or wireless debugging on the S26 Ultra so Claude Code / Android Studio can install the app.
- Weigh in each morning before eating or drinking for consistent trends.
