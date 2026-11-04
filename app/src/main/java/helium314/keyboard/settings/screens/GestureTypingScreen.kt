// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.JniUtils
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.preferences.SliderPreference
import helium314.keyboard.settings.preferences.SwitchPreference
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.settings.initPreview
import helium314.keyboard.latin.utils.previewDark
import androidx.core.content.edit

@Composable
fun GestureTypingScreen(
    onClickBack: () -> Unit,
) {
    val prefs = LocalContext.current.prefs()
    val b = (LocalContext.current.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    if ((b?.value ?: 0) < 0)
        Log.v("irrelevant", "stupid way to trigger recomposition on preference change")
    val gestureFloatingPreviewEnabled = prefs.getBoolean(Settings.PREF_GESTURE_FLOATING_PREVIEW_TEXT, Defaults.PREF_GESTURE_FLOATING_PREVIEW_TEXT)
    val gestureEnabled = prefs.getBoolean(Settings.PREF_GESTURE_INPUT, Defaults.PREF_GESTURE_INPUT)
    val items = listOf(
        Settings.PREF_GESTURE_INPUT,
        if (gestureEnabled) ru.klaviatura.glide.GlideEngine.PREF_OWN_GLIDE else null,
        if (gestureEnabled) "kl_learn_export" else null,
        if (gestureEnabled) "kl_learn_import" else null,
        ru.klaviatura.voice.VoiceInput.PREF_ENGINE,
        ru.klaviatura.voice.VoiceInput.PREF_MIXED,
        "kl_voice_import",
        ru.klaviatura.voice.VoiceInput.PREF_COMMANDS,
        ru.klaviatura.voice.VoiceInput.PREF_ENGINE_VOSK,
        if (gestureEnabled)
            Settings.PREF_GESTURE_PREVIEW_TRAIL else null,
        if (gestureEnabled)
            Settings.PREF_GESTURE_FLOATING_PREVIEW_TEXT else null,
        if (gestureEnabled && gestureFloatingPreviewEnabled)
            Settings.PREF_GESTURE_FLOATING_PREVIEW_DYNAMIC else null,
        if (gestureEnabled)
            Settings.PREF_GESTURE_SPACE_AWARE else null,
        if (gestureEnabled)
            Settings.PREF_GESTURE_FAST_TYPING_COOLDOWN else null,
        if (gestureEnabled &&
            (prefs.getBoolean(Settings.PREF_GESTURE_PREVIEW_TRAIL, Defaults.PREF_GESTURE_PREVIEW_TRAIL) || gestureFloatingPreviewEnabled))
            Settings.PREF_GESTURE_TRAIL_FADEOUT_DURATION else null
        )
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.settings_screen_gesture),
        settings = items
    )
}

fun createGestureTypingSettings(context: Context) = listOf(
    Setting(context, ru.klaviatura.glide.GlideEngine.PREF_OWN_GLIDE, R.string.kl_own_glide, R.string.kl_own_glide_summary) {
        SwitchPreference(it, true)
    },
    Setting(context, "kl_learn_export", R.string.kl_learn_export, R.string.kl_learn_summary) { s ->
        val ctx = LocalContext.current
        helium314.keyboard.settings.preferences.Preference(name = s.title, description = s.description, onClick = {
            ctx.startActivity(android.content.Intent(ctx, ru.klaviatura.glide.LearnTransferActivity::class.java).putExtra("export", true))
        })
    },
    Setting(context, "kl_learn_import", R.string.kl_learn_import) { s ->
        val ctx = LocalContext.current
        helium314.keyboard.settings.preferences.Preference(name = s.title, onClick = {
            ctx.startActivity(android.content.Intent(ctx, ru.klaviatura.glide.LearnTransferActivity::class.java).putExtra("export", false))
        })
    },
    Setting(context, ru.klaviatura.voice.VoiceInput.PREF_ENGINE, R.string.kl_voice_builtin, R.string.kl_voice_builtin_summary) {
        SwitchPreference(it, true) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, ru.klaviatura.voice.VoiceInput.PREF_MIXED, R.string.kl_voice_mixed, R.string.kl_voice_mixed_summary) {
        SwitchPreference(it, false)
    },
    Setting(context, "kl_voice_import", R.string.kl_voice_import, R.string.kl_voice_import_summary) { s ->
        val ctx = LocalContext.current
        helium314.keyboard.settings.preferences.Preference(name = s.title, description = s.description, onClick = {
            ctx.startActivity(android.content.Intent(ctx, ru.klaviatura.voice.ModelImportActivity::class.java))
        })
    },
    Setting(context, ru.klaviatura.voice.VoiceInput.PREF_ENGINE_VOSK, R.string.kl_voice_engine_vosk, R.string.kl_voice_engine_vosk_summary) {
        SwitchPreference(it, false)
    },
    Setting(context, ru.klaviatura.voice.VoiceInput.PREF_COMMANDS, R.string.kl_voice_commands, R.string.kl_voice_commands_summary) {
        SwitchPreference(it, true)
    },
    Setting(context, Settings.PREF_GESTURE_INPUT, R.string.gesture_input, R.string.gesture_input_summary) {
        SwitchPreference(it, Defaults.PREF_GESTURE_INPUT)
    },
    Setting(context, Settings.PREF_GESTURE_PREVIEW_TRAIL, R.string.gesture_preview_trail) {
        SwitchPreference(it, Defaults.PREF_GESTURE_PREVIEW_TRAIL)
    },
    Setting(context, Settings.PREF_GESTURE_FLOATING_PREVIEW_TEXT,
        R.string.gesture_floating_preview_static, R.string.gesture_floating_preview_static_summary)
    {
        SwitchPreference(it, Defaults.PREF_GESTURE_FLOATING_PREVIEW_TEXT)
    },
    Setting(context, Settings.PREF_GESTURE_FLOATING_PREVIEW_DYNAMIC,
        R.string.gesture_floating_preview_text, R.string.gesture_floating_preview_dynamic_summary)
    { def ->
        val ctx = LocalContext.current
        SwitchPreference(def, Defaults.PREF_GESTURE_FLOATING_PREVIEW_DYNAMIC) {
            // is this complexity and 2 pref keys for one setting really needed?
            // default value is based on system reduced motion
            val default = Settings.readGestureDynamicPreviewDefault(ctx)
            val followingSystem = it == default
            // allow the default to be overridden
            ctx.prefs().edit { putBoolean(Settings.PREF_GESTURE_DYNAMIC_PREVIEW_FOLLOW_SYSTEM, followingSystem) }
            KeyboardSwitcher.getInstance().setThemeNeedsReload()
        }
    },
    Setting(context, Settings.PREF_GESTURE_SPACE_AWARE, R.string.gesture_space_aware, R.string.gesture_space_aware_summary) {
        SwitchPreference(it, Defaults.PREF_GESTURE_SPACE_AWARE)
    },
    Setting(context, Settings.PREF_GESTURE_FAST_TYPING_COOLDOWN, R.string.gesture_fast_typing_cooldown) { def ->
        SliderPreference(
            name = def.title,
            key = def.key,
            default = Defaults.PREF_GESTURE_FAST_TYPING_COOLDOWN,
            range = 0f..500f,
            description = {
                if (it <= 0) stringResource(R.string.gesture_fast_typing_cooldown_instant)
                else stringResource(R.string.abbreviation_unit_milliseconds, it.toString())
            }
        )
    },
    Setting(context, Settings.PREF_GESTURE_TRAIL_FADEOUT_DURATION, R.string.gesture_trail_fadeout_duration) { def ->
        SliderPreference(
            name = def.title,
            key = def.key,
            default = Defaults.PREF_GESTURE_TRAIL_FADEOUT_DURATION,
            range = 100f..1900f,
            description = { stringResource(R.string.abbreviation_unit_milliseconds, (it + 100).toString()) },
            stepSize = 10,
        ) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
)

@Preview
@Composable
private fun Preview() {
    JniUtils.sHaveGestureLib = true
    initPreview(LocalContext.current)
    Theme(previewDark) {
        Surface {
            GestureTypingScreen { }
        }
    }
}
