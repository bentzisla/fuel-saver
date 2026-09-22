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
