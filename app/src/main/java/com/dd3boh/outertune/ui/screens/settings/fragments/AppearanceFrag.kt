/*
 * Copyright (C) 2025 O‌ute‌rTu‌ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.ui.screens.settings.fragments

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.DEFAULT_SLIDER_STYLE
import com.dd3boh.outertune.constants.SliderStyle
import com.dd3boh.outertune.constants.SliderStyleKey
import com.dd3boh.outertune.constants.SlimNavBarKey
import com.dd3boh.outertune.ui.component.EnumListPreference
import com.dd3boh.outertune.ui.component.SwitchPreference
import com.dd3boh.outertune.utils.rememberEnumPreference
import com.dd3boh.outertune.utils.rememberPreference

@Composable
fun ColumnScope.AppearanceMiscFrag() {
    val (slimNav, onSlimNavChange) = rememberPreference(SlimNavBarKey, defaultValue = false)
    val (sliderStyle, onSliderStyleChange) = rememberEnumPreference(SliderStyleKey, defaultValue = DEFAULT_SLIDER_STYLE)

    SwitchPreference(
        title = { Text(stringResource(R.string.slim_navbar_title)) },
        description = stringResource(R.string.slim_navbar_description),
        icon = { Icon(Icons.Rounded.MoreHoriz, null) },
        checked = slimNav,
        onCheckedChange = onSlimNavChange
    )

    EnumListPreference(
        title = { Text(stringResource(R.string.slider_style_title)) },
        icon = { Icon(Icons.Rounded.GraphicEq, null) },
        selectedValue = sliderStyle,
        onValueSelected = onSliderStyleChange,
        valueText = {
            when (it) {
                SliderStyle.SQUIGGLY -> stringResource(R.string.slider_style_squiggly)
                SliderStyle.DEFAULT -> stringResource(R.string.slider_style_default)
                SliderStyle.SLIM -> stringResource(R.string.slider_style_slim)
            }
        }
    )
}

@Preview(showBackground = true)
@Composable
private fun AppearanceMiscFragPreview() {
    Column {
        AppearanceMiscFrag()
    }
}