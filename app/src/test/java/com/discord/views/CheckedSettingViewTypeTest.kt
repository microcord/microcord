package com.discord.views

import com.discord.R
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `cs_view_type` is declared as an enum attribute with `check=0`, `radio=1` and
 * `switchview=2`. The index the attribute yields must select the matching layout
 * and accessibility role, otherwise every settings row renders as a checkbox.
 */
class CheckedSettingViewTypeTest {
    @Test
    fun `enum indices match the attribute declaration order`() {
        assertEquals(CheckedSetting.ViewType.CHECK, CheckedSetting.viewTypeOf(0))
        assertEquals(CheckedSetting.ViewType.RADIO, CheckedSetting.viewTypeOf(1))
        assertEquals(CheckedSetting.ViewType.SWITCH, CheckedSetting.viewTypeOf(2))
    }

    @Test
    fun `an out-of-range index falls back to a checkbox`() {
        assertEquals(CheckedSetting.ViewType.CHECK, CheckedSetting.viewTypeOf(-1))
        assertEquals(CheckedSetting.ViewType.CHECK, CheckedSetting.viewTypeOf(3))
        assertEquals(
            CheckedSetting.ViewType.CHECK,
            CheckedSetting.viewTypeOf(CheckedSetting.DEFAULT_VIEW_TYPE),
        )
    }

    @Test
    fun `each view type inflates its own layout`() {
        assertEquals(R.layout.view_setting_check, CheckedSetting.layoutFor(CheckedSetting.ViewType.CHECK))
        assertEquals(R.layout.view_setting_radio, CheckedSetting.layoutFor(CheckedSetting.ViewType.RADIO))
        assertEquals(R.layout.view_setting_switch, CheckedSetting.layoutFor(CheckedSetting.ViewType.SWITCH))
    }

    @Test
    fun `each view type reports its own accessibility role`() {
        assertEquals(
            R.string.a11y_role_checkbox,
            CheckedSetting.accessibilityRoleFor(CheckedSetting.ViewType.CHECK),
        )
        assertEquals(
            R.string.a11y_role_radio_button,
            CheckedSetting.accessibilityRoleFor(CheckedSetting.ViewType.RADIO),
        )
        assertEquals(
            R.string.a11y_role_switch,
            CheckedSetting.accessibilityRoleFor(CheckedSetting.ViewType.SWITCH),
        )
    }
}
