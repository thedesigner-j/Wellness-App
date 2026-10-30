# Garmin Connect personal API

Garmin released personal API keys on Oct 21, 2026. Any Garmin Connect user can make a key that reads their own data. There is no developer application and no approval step. The app uses it as an optional second source of run and ride history. Health Connect stays the default, and it stays the fallback.

## Creating a key (the user does this, never Claude)

1. Sign in at connect.garmin.com and open **Personal API Key**: <https://connect.garmin.com/app/settings/personalApiToken>.
2. Select **Create Key**, accept the terms, choose an expiry (30 days, 90 days, 180 days or 1 year), then select **Create**.
3. Copy the key. Garmin shows it **only once**.
4. In the app, go to **Settings › Data sources › Garmin personal API**, paste the key and select **Save key & sync**.

Garmin emails a reminder about a week before a key expires. Deleting a key on Garmin Connect cancels it and every access token made from it.

## Auth flow

- Base URL: `https://personal.connectapi.garmin.com`
- Exchange the key for an access token:
  `POST /personalapitoken-service/personal-api/personal-api-key/token` with header `Authorization: ApiKey <key>`.
  The response body is the access token itself, a JWT. Its `exp` claim gives the expiry.
- A token lasts up to **24 hours**, or until the key expires if that is sooner. There are no refresh tokens, so the app asks for a new one when needed. Garmin's sample client reuses a cached token while it has more than 1 hour left. The app does the same and caches the token encrypted next to the key.
- Data calls send `Authorization: Bearer <token>`.

## Endpoints used

| Purpose | Request |
|---|---|
| Activity list | `GET /activitylist-service/activities/search/activities?start=<offset>&limit=<n>&startDate=YYYY-MM-DD&endDate=YYYY-MM-DD[&activityType=running]` |
| One activity | `GET /activity-service/activity/{activityId}` |
| Original file (FIT, inside a ZIP) | `GET /download-service/files/activity/{activityId}` |

The public pages don't document the activity summary fields. The app reads the fields that Garmin Connect's activity list has always used: `activityId`, `activityName`, `activityType.typeKey`, `beginTimestamp` (epoch ms), `startTimeGMT` and `startTimeLocal`, `duration` (seconds of timer time), `elapsedDuration`, `distance` (meters), `averageHR` and `maxHR`. Parsing is lenient. A missing optional field becomes null, and an entry with no id, start time or duration is skipped. See `calc/.../GarminApi.kt` and `GarminApiTest`.

The API also offers health data (sleep, HRV, steps, stress and more). The app doesn't use that yet.

## Limits

- **Pull-only and read-only.** Nothing is pushed to the app, and the API can't send workouts or courses to the watch. Those features are business-tier only, so planned sessions can't go to the watch this way.
- **Rate limits are not published.** The app is careful anyway. Background syncs hit Garmin at most every 30 minutes. A sync makes one token call per day or less, plus one list call per 100 activities. An HTTP 429 response is recorded and retried on a later sync.
- **History depth isn't stated.** Users report full history. The app asks for 90 days on the first sync, then from 3 days before the newest stored activity.
- Garmin says the API is for your own data, not for commercial apps that serve many users.

## How the app uses it

- Rows go into `workouts` with `source = "garmin-api"` and `id = "garmin-api-<activityId>"`. The Garmin type key maps to the same Health Connect exercise-type constants, so `runFitness`, `rideFitness`, `Completion.matches` and weekly volume treat these rows the same as Health Connect rows.
- **De-duplication** (`WorkoutDedup.preferApi`, applied when workouts are read): a Health Connect or CSV row is hidden when an API row has the same sport and starts within ±2 minutes. The API row wins.
- With no key set, the Garmin sync returns immediately and nothing changes.

## Not done yet

- Laps and splits from the FIT file. The download endpoint returns a ZIP that holds the original FIT file. Using it would need a FIT decoder and a laps table.

## Sources

- Garmin, Personal API: <https://developer.garmin.com/gc-developer-program/personal/personal-api>
- Garmin, Personal API FAQ (token lifetime, key expiry, no refresh tokens): <https://developer.garmin.com/gc-developer-program/personal/personal-faq/>
- Garmin, Personal API Swagger docs: <https://developer.garmin.com/gc-developer-program/personal/swagger-docs/>
- Garmin's sample client, `garmin/connect-personal-api-python` (base URL, token path, endpoint paths, query parameters, ZIP download): <https://github.com/garmin/connect-personal-api-python>
- the5krunner, "Garmin Personal API Key 2026" (pull-only, no workout push, FIT contents): <https://the5krunner.com/2026/10/22/garmin-personal-api/>
- Tredict, personal API reads activities but does not send workouts: <https://www.tredict.com/blog/garmin_personal_api_chatgpt_and_claude/>
