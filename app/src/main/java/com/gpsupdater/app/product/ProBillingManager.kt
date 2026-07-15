package com.gpsupdater.app.product

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.gpsupdater.app.R

data class ProBillingState(
    val connected: Boolean = false,
    val loading: Boolean = true,
    val formattedPrice: String? = null,
    val purchased: Boolean = false,
    val pending: Boolean = false,
    val ownershipChecked: Boolean = false,
    val errorMessage: String? = null,
)

class ProBillingManager(
    context: Context,
    private val entitlementRepository: EntitlementRepository,
    private val onStateChanged: (ProBillingState) -> Unit,
) : PurchasesUpdatedListener, AutoCloseable {
    private val resourceContext = context
    private val appContext = context.applicationContext
    private val billingClient = BillingClient.newBuilder(appContext)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build(),
        )
        .build()
    private var productDetails: ProductDetails? = null
    private var state = ProBillingState()

    fun connect(loadProductDetails: Boolean = true) {
        updateState(state.copy(loading = true, errorMessage = null))
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    updateState(state.copy(connected = true, loading = true))
                    if (loadProductDetails) {
                        queryProduct()
                    }
                    restorePurchases()
                } else {
                    reportBillingError(result)
                }
            }

            override fun onBillingServiceDisconnected() {
                updateState(state.copy(connected = false, loading = false))
            }
        })
    }

    fun launchPurchase(activity: Activity) {
        val details = productDetails
        if (!billingClient.isReady || details == null) {
            updateState(
                state.copy(errorMessage = resourceContext.getString(R.string.pro_product_unavailable)),
            )
            return
        }
        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .build()
        val result = billingClient.launchBillingFlow(
            activity,
            BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(listOf(productParams))
                .build(),
        )
        if (result.responseCode != BillingClient.BillingResponseCode.OK) reportBillingError(result)
    }

    fun restorePurchases() {
        if (!billingClient.isReady) return
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build(),
        ) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                processPurchases(purchases)
            } else {
                reportBillingError(result)
            }
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> processPurchases(purchases.orEmpty())
            BillingClient.BillingResponseCode.USER_CANCELED ->
                updateState(state.copy(loading = false, errorMessage = null))
            else -> reportBillingError(result)
        }
    }

    override fun close() {
        billingClient.endConnection()
    }

    private fun queryProduct() {
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(PRO_PRODUCT_ID)
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        billingClient.queryProductDetailsAsync(
            QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build(),
        ) { result, queryResult ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                productDetails = queryResult.productDetailsList.firstOrNull()
                updateState(
                    state.copy(
                        loading = false,
                        formattedPrice = productDetails?.oneTimePurchaseOfferDetails?.formattedPrice,
                        errorMessage = if (productDetails == null) {
                            resourceContext.getString(R.string.pro_product_unavailable)
                        } else {
                            null
                        },
                    ),
                )
            } else {
                reportBillingError(result)
            }
        }
    }

    private fun processPurchases(purchases: List<Purchase>) {
        val purchase = purchases.firstOrNull { PRO_PRODUCT_ID in it.products }
        if (purchase == null) {
            entitlementRepository.updateVerifiedProOwnership(false)
            updateState(
                state.copy(
                    loading = false,
                    purchased = false,
                    pending = false,
                    ownershipChecked = true,
                ),
            )
            return
        }
        when (purchase.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> {
                if (purchase.isAcknowledged) {
                    grantPro()
                } else {
                    billingClient.acknowledgePurchase(
                        AcknowledgePurchaseParams.newBuilder()
                            .setPurchaseToken(purchase.purchaseToken)
                            .build(),
                    ) { result ->
                        if (result.responseCode == BillingClient.BillingResponseCode.OK) grantPro()
                        else reportBillingError(result)
                    }
                }
            }

            Purchase.PurchaseState.PENDING -> {
                entitlementRepository.updateVerifiedProOwnership(false)
                updateState(
                    state.copy(
                        loading = false,
                        purchased = false,
                        pending = true,
                        ownershipChecked = true,
                    ),
                )
            }

            else -> {
                entitlementRepository.updateVerifiedProOwnership(false)
                updateState(
                    state.copy(
                        loading = false,
                        purchased = false,
                        pending = false,
                        ownershipChecked = true,
                    ),
                )
            }
        }
    }

    private fun grantPro() {
        entitlementRepository.updateVerifiedProOwnership(true)
        updateState(
            state.copy(
                loading = false,
                purchased = true,
                pending = false,
                ownershipChecked = true,
                errorMessage = null,
            ),
        )
    }

    private fun reportBillingError(result: BillingResult) {
        updateState(
            state.copy(
                loading = false,
                errorMessage = resourceContext.getString(
                    R.string.billing_failed,
                    result.responseCode,
                ),
            ),
        )
    }

    private fun updateState(newState: ProBillingState) {
        state = newState
        onStateChanged(state)
    }

    companion object {
        const val PRO_PRODUCT_ID = "gps_updater_pro_lifetime"
    }
}
