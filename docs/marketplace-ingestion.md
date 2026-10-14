# Marketplace ingestion

How marketplace templates get from outside the app into the local `marketplace_template` table (M8-04), and where a remote source would plug in.

## The flow

```
IngestionSource ──► MarketplaceIngestionStrategy.ingest() ──► Flow<IngestionResult>
                          │
   LocalBundleIngestionStrategy
     1. BundleOpener.read(path)            asset text from the APK
     2. MarketplaceBundleParser.parse()    List<MarketplaceTemplate>, all or nothing
     3. MarketplaceReconciler.plan()       pure: inserts / updates / touches / hides
     4. MarketplaceIngestionStore.reconcile()   one Room transaction: read, plan, write
```

Everything before step 4 can fail without a write having happened, so a broken bundle cannot make the stored templates look absent. `MarketplaceBootstrap.start()` runs this once per launch from `TitanApplication`, in the background; a failure leaves the table as it was.

## Rules (all in `MarketplaceReconciler`)

- **Dedup by `remoteId`.** A template whose id is already stored is updated in place. Unchanged templates are only stamped as seen, so re-ingesting the same bundle reports `0 inserted, 0 updated, 0 soft-deleted` and adds no rows.
- **Soft delete after a TTL.** A stored template absent from the bundle is hidden (`is_hidden = 1`) once `now - last_seen_at >= ttl`; the default TTL is 30 days (`MarketplaceReconciler.DEFAULT_TTL_DAYS`). The row is never deleted, and a hidden template that reappears is updated and shown again. Hidden templates are not served by `MarketplaceRepository.getAll()`.
- **`IngestionResult`** is `inserted`, `updated` (changed content, or un-hidden) and `softDeleted` (newly hidden this run).

## Bundle format

```json
{
  "version": 1,
  "templates": [
    {
      "remote_id": "mp-push-pull-legs",
      "title": "Push Pull Legs",
      "author": "Alex",
      "description": "…",
      "tags": ["chest", "legs"],
      "download_count": 4200,
      "average_rating": 4.6,
      "duration_minutes": 60,
      "created_at": 1700000000000
    }
  ]
}
```

`remote_id`, `title`, `duration_minutes` and `created_at` are required. A different `version`, a wrong type, a repeated `remote_id` or a template the domain rejects (rating outside 0..5, non-positive duration) fails the whole bundle. Unknown keys are ignored. The shipped bundle is `core/data/src/main/assets/marketplace/templates.json`.

## Adding a remote source

1. Add a subtype to `IngestionSource` (for example `Remote(baseUrl)`). Every exhaustive `when` over it then fails to compile until handled.
2. Write a second `MarketplaceIngestionStrategy` that fetches and maps the response to `List<MarketplaceTemplate>`, then calls `MarketplaceReconciler.plan` through `MarketplaceIngestionStore.reconcile` exactly as `LocalBundleIngestionStrategy` does, and emits the `IngestionResult`. No reconciliation logic is duplicated.
3. Choose which strategy runs when (for example remote with the bundle as fallback). `MarketplaceBootstrap` takes one strategy today.

The app currently strips the `INTERNET` permission (`NetworkPolicyTest` guards it), so step 2 also means deciding to lift that, which is a product decision and not part of this extension point.
