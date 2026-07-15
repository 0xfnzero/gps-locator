package com.gpsupdater.app.product

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Patterns
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.view.updateLayoutParams
import com.gpsupdater.app.BuildConfig
import com.gpsupdater.app.R
import com.gpsupdater.app.databinding.ActivityProUpgradeBinding

class ProUpgradeActivity : AppCompatActivity() {
    private lateinit var binding: ActivityProUpgradeBinding
    private lateinit var billingManager: ProBillingManager
    private val entitlementRepository by lazy { EntitlementRepository(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProUpgradeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        configureSystemBars()
        binding.backButton.setOnClickListener { finish() }

        billingManager = ProBillingManager(this, entitlementRepository, ::renderState)
        binding.purchaseButton.setOnClickListener { billingManager.launchPurchase(this) }
        binding.restoreButton.setOnClickListener { billingManager.restorePurchases() }
        configureEnterpriseSales()

        if (BuildConfig.DEBUG) {
            binding.progress.visibility = View.GONE
            binding.priceText.setText(R.string.pro_title)
            binding.statusText.setText(R.string.pro_debug_active)
            binding.purchaseButton.isEnabled = false
            binding.restoreButton.isEnabled = false
        } else if (entitlementRepository.currentTier() == ProductTier.PRO) {
            renderState(ProBillingState(loading = false, connected = true, purchased = true))
            billingManager.connect()
        } else {
            binding.priceText.setText(R.string.pro_price_loading)
            billingManager.connect()
        }
    }

    override fun onDestroy() {
        if (::billingManager.isInitialized) billingManager.close()
        super.onDestroy()
    }

    private fun configureSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.topBar.updateLayoutParams {
                height = dp(64) + bars.top
            }
            binding.topBar.updatePadding(top = bars.top)
            binding.root.updatePadding(bottom = bars.bottom)
            insets
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun configureEnterpriseSales() {
        val salesAddress = BuildConfig.ENTERPRISE_SALES_EMAIL
            .trim()
            .takeIf { it.isNotBlank() && Patterns.EMAIL_ADDRESS.matcher(it).matches() }
        binding.enterpriseSalesButton.isEnabled = salesAddress != null
        binding.enterpriseSalesUnavailableText.visibility = if (salesAddress == null) {
            View.VISIBLE
        } else {
            View.GONE
        }
        binding.enterpriseSalesButton.setOnClickListener {
            val email = salesAddress ?: return@setOnClickListener
            val mailUri = Uri.Builder()
                .scheme("mailto")
                .opaquePart(email)
                .appendQueryParameter(
                    "subject",
                    getString(R.string.enterprise_sales_email_subject),
                )
                .build()
            runCatching { startActivity(Intent(Intent.ACTION_SENDTO, mailUri)) }
                .onFailure {
                    binding.enterpriseSalesUnavailableText.setText(
                        R.string.enterprise_sales_email_open_failed,
                    )
                    binding.enterpriseSalesUnavailableText.visibility = View.VISIBLE
                }
        }
    }

    private fun renderState(state: ProBillingState) {
        if (BuildConfig.DEBUG) return
        binding.progress.visibility = if (state.loading) View.VISIBLE else View.GONE
        binding.priceText.text = state.formattedPrice ?: getString(R.string.pro_title)
        binding.statusText.text = when {
            state.purchased -> getString(R.string.pro_purchased)
            state.pending -> getString(R.string.pro_pending)
            state.errorMessage != null -> state.errorMessage
            state.formattedPrice == null && !state.loading -> getString(R.string.pro_product_unavailable)
            else -> ""
        }
        binding.purchaseButton.isEnabled = !state.loading && !state.purchased &&
            state.formattedPrice != null
        binding.restoreButton.isEnabled = state.connected && !state.loading
    }
}
