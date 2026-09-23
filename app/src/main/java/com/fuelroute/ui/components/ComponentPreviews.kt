package com.fuelroute.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.fuelroute.ui.theme.FuelRouteTheme

/*
 * Design-system previews (Hebrew / RTL, light + dark). Open in Android Studio to review the
 * shared components without a device.
 */

@Composable
private fun Showcase() {
    Column(modifier = Modifier.padding(Dimens.l), verticalArrangement = Arrangement.spacedBy(Dimens.m)) {
        SectionCard(title = "סיכום", subtitle = "כותרת משנה שקטה") {
            HeroValue(value = "₪23.40", unit = "42 דק׳", label = "עלות הנסיעה")
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.xs)) {
                StatusPill("מומלץ", tone = PillTone.Positive)
                StatusPill("המהיר ביותר", tone = PillTone.Accent)
                StatusPill("הדגמה", tone = PillTone.Caution)
                StatusPill("ממתין", tone = PillTone.Neutral)
            }
            PrimaryButton(text = "נווט", onClick = {})
            SecondaryButton(text = "פרטים", onClick = {})
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
            StatTile(label = "צריכה רגעית", value = "6.4", unit = "ל׳/100 ק״מ", large = true, modifier = Modifier.weight(1f))
            StatTile(label = "עלות הנסיעה", value = "₪8.10", unit = "1.15 ל׳ דלק", large = true, modifier = Modifier.weight(1f))
        }
        SectionCard(contentPadding = Dimens.s) {
            ListRow(title = "רישום תדלוק", subtitle = "רישום תדלוק וכיול מול המשאבה", onClick = {})
            SwitchRow(title = "חיבור אוטומטי לדונגל", subtitle = "דונגל: OBDII", checked = true, onCheckedChange = {})
            KeyValueRow(label = "סה״כ", value = "₪23.40", emphasize = true)
        }
        ExpandableSection(title = "אבחון ופעולות מתקדמות", subtitle = "מוסתר כברירת מחדל") {
            Text("תוכן")
        }
        ErrorCard(message = "אין חיבור לאינטרנט", onRetry = {})
        EmptyState(title = "אין נסיעות מתועדות עדיין", body = "חברו דונגל כדי להתחיל")
    }
}

@Preview(name = "Components – light", locale = "iw", showBackground = true, heightDp = 1100)
@Composable
private fun ComponentsLightPreview() {
    FuelRouteTheme(darkTheme = false) { Showcase() }
}

@Preview(
    name = "Components – dark",
    locale = "iw",
    showBackground = true,
    heightDp = 1100,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ComponentsDarkPreview() {
    FuelRouteTheme(darkTheme = true) { Showcase() }
}
