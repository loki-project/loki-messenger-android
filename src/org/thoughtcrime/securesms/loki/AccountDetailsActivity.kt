package org.thoughtcrime.securesms.loki

import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import kotlinx.android.synthetic.main.activity_account_details.*
import network.loki.messenger.R
import org.thoughtcrime.securesms.ApplicationContext
import org.thoughtcrime.securesms.BaseActionBarActivity
import org.thoughtcrime.securesms.ConversationListActivity
import org.thoughtcrime.securesms.util.TextSecurePreferences
import org.whispersystems.signalservice.api.crypto.ProfileCipher

class AccountDetailsActivity : BaseActionBarActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_account_details)
        nextButton.setOnClickListener { continueIfPossible() }
    }

    private fun continueIfPossible() {
        val uncheckedName = nameEditText.text.toString()
        val name = if (uncheckedName.isNotEmpty()) { uncheckedName.trim() } else { null }
        if (name != null) {
            if (name.toByteArray().size > ProfileCipher.NAME_PADDED_LENGTH) {
                return nameEditText.input.setError("Too Long")
            } else {
                TextSecurePreferences.setProfileName(this, name)
            }
        }
        val inputMethodManager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        inputMethodManager.hideSoftInputFromWindow(nameEditText.windowToken, 0)
        TextSecurePreferences.setHasSeenWelcomeScreen(this, true)
        TextSecurePreferences.setPromptedPushRegistration(this, true)
        val application = ApplicationContext.getInstance(this)
        application.setUpP2PAPI()
        application.startLongPollingIfNeeded()
        startActivity(Intent(this, ConversationListActivity::class.java))
        finish()
    }
}