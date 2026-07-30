package com.ebbinghaus.review

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

class TestApplication : Application()

class TestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        cl: ClassLoader,
        className: String,
        context: Context
    ): Application = super.newApplication(cl, TestApplication::class.java.name, context)
}
