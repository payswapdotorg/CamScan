package org.payswap.camscan.export.share

import androidx.core.content.FileProvider

/**
 * Export share provider (CAMSCAN-PROD-007 §6.4): a one-line FileProvider
 * subclass owned by the export tree so share URIs resolve against a provider
 * this work order controls. Declared in AndroidManifest.xml (the lead's
 * integration amendment) with authority [ShareIntents.EXPORT_PROVIDER_AUTHORITY]
 * and the paths document `@xml/export_file_paths` (cache-path `exports/`).
 */
class ExportFileProvider : FileProvider()
