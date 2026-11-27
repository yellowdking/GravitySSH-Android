package com.gravityssh.app.model

import kotlinx.serialization.json.Json

object AppJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }
}
