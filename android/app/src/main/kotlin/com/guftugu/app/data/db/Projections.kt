package com.guftugu.app.data.db

/** Small Room projections (not entities) used by the repositories. */

/** `(convId, keyId)` of a stored conversation key — enough for the UI's `hasKey` flag without loading key bytes. */
data class ConvKeyRef(val convId: String, val keyId: String)

/** `(convId, count)` of undecryptable rows, to know which conversations to re-decrypt after keys arrive. */
data class ConvCount(val convId: String, val count: Int)
