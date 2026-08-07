package com.murong.agent.ui.providerimport

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.murong.agent.core.config.ConfigRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProviderImportActivityInstrumentedTest {
    @Test
    fun deepLinkOpensConfirmationAndImportPersistsEncryptedRelay() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val link = Uri.Builder()
            .scheme("ccswitch")
            .authority("v1")
            .path("import")
            .appendQueryParameter("resource", "provider")
            .appendQueryParameter("app", "codex")
            .appendQueryParameter("name", "Instrumented import")
            .appendQueryParameter("endpoint", "https://instrumented.example/v1")
            .appendQueryParameter("apiKey", "sk-instrumented-secret")
            .appendQueryParameter("model", "gpt-instrumented")
            .build()
        val intent = Intent(Intent.ACTION_VIEW, link).setClass(context, ProviderImportActivity::class.java)

        ActivityScenario.launch<ProviderImportActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(link, activity.intent.data)
            }
        }

        val repository = ConfigRepository(context)
        val viewModel = ProviderImportViewModel(repository)
        viewModel.load(link.toString())
        assertNotNull(viewModel.state.value.payload)
        viewModel.confirm(activate = false, enableUsage = false)
        runBlocking {
            withTimeout(5_000) {
                while (!viewModel.state.value.imported) delay(25)
            }
        }
        val config = runBlocking { repository.getConfig() }
        val relay = config.getRelayConfigs("openai-compatible").firstOrNull { it.name == "Instrumented import" }
        assertNotNull(relay)
        assertEquals("sk-instrumented-secret", relay?.apiKey)
    }
}
