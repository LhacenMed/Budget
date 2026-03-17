package com.lhacenmed.budget

import android.app.Application
import com.lhacenmed.budget.util.PreferenceUtil
import dagger.hilt.android.HiltAndroidApp
import com.google.android.material.color.DynamicColors

@HiltAndroidApp
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        PreferenceUtil.init(this)
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
