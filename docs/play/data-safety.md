# Play Console - Data safety answers

"Collected" in Play terms means the data leaves the device. The developer has no server, so the only recipient is Google (Routes/Places APIs), acting as a service provider.

| Question | Answer |
|---|---|
| Does the app collect or share user data? | Yes |
| Is all data encrypted in transit? | Yes (HTTPS) |
| Can users request data deletion? | The developer holds no data; on-device data is removed by clearing storage or uninstalling (say so) |
| Data types sent to Google | **Precise location** (route origin, only when the user asks for a route from "my location"); **Search queries / user-generated text** (place text for autocomplete) |
| Purposes | App functionality only. No analytics, advertising or personalization |
| Personal identifiers, contacts, photos, financial info, health | Not collected |
| VIN, OBD readings, trips, vehicle profile | Stored on the device only, never transmitted, so not "collected" |
| Ads | None |
| Account creation | None |
| Location collected in the background | No (read only when the user taps) |

Content rating: Everyone; utility. Category: Maps & Navigation (or Auto & Vehicles). Contains ads: No.
