package com.rtsp.camera.ui

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.rtsp.camera.R
import com.rtsp.camera.model.QualityPreset
import com.rtsp.camera.util.PreferenceHelper

/**
 * 设置界面Fragment
 */
class SettingsFragment : PreferenceFragmentCompat() {
    
    // 自定义画质相关的 Preference
    private var customWidthPref: EditTextPreference? = null
    private var customHeightPref: EditTextPreference? = null
    private var customBitratePref: EditTextPreference? = null
    private var customFrameratePref: EditTextPreference? = null
    
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)
        
        // 获取自定义画质相关的 Preference 引用
        customWidthPref = findPreference("custom_width")
        customHeightPref = findPreference("custom_height")
        customBitratePref = findPreference("custom_bitrate")
        customFrameratePref = findPreference("custom_framerate")
        
        // 设置自定义参数的 summary provider
        customWidthPref?.summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
        customHeightPref?.summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
        customBitratePref?.summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
        customFrameratePref?.summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
        
        // 设置画质预设列表
        findPreference<ListPreference>("quality_preset")?.apply {
            entries = QualityPreset.entries.map { preset ->
                if (preset == QualityPreset.CUSTOM) {
                    getString(R.string.quality_custom)
                } else {
                    "${preset.displayName} (${preset.width}×${preset.height})"
                }
            }.toTypedArray()
            entryValues = QualityPreset.entries.map { it.ordinal.toString() }.toTypedArray()
            
            // 初始化时根据当前值设置自定义字段可见性
            updateCustomQualityVisibility(value)
            
            // 监听画质预设变化
            setOnPreferenceChangeListener { _, newValue ->
                updateCustomQualityVisibility(newValue as? String)
                true
            }
        }
        
        // RTSP端口验证
        findPreference<EditTextPreference>("rtsp_port")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                val port = (newValue as? String)?.toIntOrNull()
                port != null && port in 1024..65535
            }
            summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
        }
        
        // 自动闪光灯阈值
        findPreference<EditTextPreference>("auto_flash_threshold")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                val threshold = (newValue as? String)?.toFloatOrNull()
                threshold != null && threshold > 0
            }
            summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
        }
    }
    
    /**
     * 根据画质预设更新自定义参数字段的可见性
     * @param presetValue 预设值 (ordinal 字符串)
     */
    private fun updateCustomQualityVisibility(presetValue: String?) {
        val ordinal = presetValue?.toIntOrNull() ?: QualityPreset.MEDIUM.ordinal
        val isCustom = ordinal == QualityPreset.CUSTOM.ordinal
        
        customWidthPref?.isVisible = isCustom
        customHeightPref?.isVisible = isCustom
        customBitratePref?.isVisible = isCustom
        customFrameratePref?.isVisible = isCustom
    }
}
