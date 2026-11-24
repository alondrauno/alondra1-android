package com.alondra1.app.core

import java.util.UUID

/** New ids follow the shared contract: 32 lowercase hex characters (same as Windows Guid "n"). */
object Ids {
    fun newId(): String = UUID.randomUUID().toString().replace("-", "")
}
