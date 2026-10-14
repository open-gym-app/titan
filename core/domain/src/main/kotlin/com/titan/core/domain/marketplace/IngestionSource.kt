package com.titan.core.domain.marketplace

/**
 * Where a [MarketplaceIngestionStrategy] is told to read from.
 *
 * ### The extension point for a remote API
 *
 * Today there is one source, a bundle shipped inside the APK. A remote catalogue is a second
 * subtype here (say `Remote(baseUrl)`) plus a second strategy that reads it, and nothing
 * downstream changes: [MarketplaceReconciler] decides what to insert, update and hide from
 * *a list of templates*, whatever fetched it, and the store applies that decision without
 * knowing where the list came from. A remote strategy therefore only has to fetch and parse
 * into `List<MarketplaceTemplate>`, call the reconciler through the same store, and emit an
 * [IngestionResult]. Adding the subtype makes every exhaustive `when` over it fail to compile,
 * which is how a strategy that cannot read it is found.
 *
 * Remote ingestion also needs the network, which the app deliberately does not have
 * (`NetworkPolicyTest`); that decision belongs to whoever lifts the restriction, not to this type.
 */
sealed interface IngestionSource {

    /** A JSON bundle in the APK's assets, at [assetPath] relative to the assets root. */
    data class LocalBundle(val assetPath: String) : IngestionSource {
        init {
            require(assetPath.isNotBlank()) { "assetPath must not be blank" }
        }
    }
}
