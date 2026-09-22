package com.fuelroute

/**
 * In-app "מה חדש" (what's new) changelog. One [Entry] per released version; the newest entry is
 * shown first. The orchestrator appends an entry and bumps `versionCode`/`versionName` in
 * `app/build.gradle.kts` for every merged feature/bug-fix wave (see docs/IMPLEMENTATION-PLAN.md).
 */
object Changelog {

    data class Entry(
        val versionName: String,
        val title: String,
        val changes: List<String>,
    )

    val entries: List<Entry> = listOf(
        Entry(
            versionName = "0.4.0",
            title = "חיבור OBD אמין וניווט לפי המסלול הנבחר",
            changes = listOf(
                "תוקן ניתוק OBD מוקדם שנגרם מקריאת מתח סוללה שגויה",
                "חיבור מחדש אוטומטי אחרי ניתוק (כל עוד הדונגל מחובר)",
                "Google Maps עוקב כעת אחרי המסלול הנבחר (נקודות מעבר, ללא עצירות ביניים)",
                "הבהרה: Waze מקבל את היעד בלבד ובוחר מסלול בעצמו",
            ),
        ),
        Entry(
            versionName = "0.3.1",
            title = "יציבות ואיכות",
            changes = listOf(
                "שיפור אמינות חיבור OBD והתקדמות החיבור",
                "סימון נסיעות הדגמה, תיקוני היסטוריה ומחיקה",
                "היקף נסיעות לפי רכב, אגרות ו־Android Auto",
            ),
        ),
        Entry(
            versionName = "0.3.0",
            title = "גרף עקומת רכב",
            changes = listOf(
                "גרף אינטראקטיבי לעקומת הצריכה",
                "גרסאות ועמוד אודות",
                "תיקוני תצוגת מפה ומקלדת",
            ),
        ),
    )
}
