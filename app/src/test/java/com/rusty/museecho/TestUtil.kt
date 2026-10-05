package com.rusty.museecho

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

object TestUtil {
    fun resource(name: String): String =
        requireNotNull(javaClass.classLoader!!.getResource(name)) { "missing test resource $name" }.readText()

    fun json(name: String): JsonObject = Json.parseToJsonElement(resource(name)) as JsonObject
}
