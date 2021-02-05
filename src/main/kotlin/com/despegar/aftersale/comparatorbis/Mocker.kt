package com.despegar.aftersale.comparatorbis

import com.despegar.aftersale.comparatorbis.WiremockFiles.baseDir
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategy
import com.fasterxml.jackson.module.kotlin.KotlinModule
import java.io.File
import java.math.BigDecimal
import java.net.URI
import java.util.*

fun main(args: Array<String>) {
    val mapper = ObjectMapper()
        .registerModule(KotlinModule())
        .setPropertyNamingStrategy(PropertyNamingStrategy.SNAKE_CASE)

    val gockModels =
        File(WiremockFiles.demo)
            .listFiles()
            .map { mapper.readTree(it) }
//            .also { it.forEach { println("http://spectre-v2-lt-01:9290" + URI(it.get("request").get("url").asText())) } }
            .map { toGockModel(it, mapper) }
//            .groupBy { productType(it) }
            .groupBy { "all" }

    gockModels.forEach { s, list ->
        println("$s has ${list.size}")
        mapper.writeValue(File("$baseDir/mocks/prueba_gock_${s}_slower_max_delay_2.json"), list)
    }
}

fun productType(gockProduct: GockModel) = when {
    gockProduct.uri.contains("v2/flights") -> "flight"
    gockProduct.uri.contains("status") -> "status"
    gockProduct.uri.contains("v2/hotel") -> "hotel"
    gockProduct.uri.contains("social")
            || gockProduct.uri.contains("apocalypse")
            || gockProduct.uri.contains("lina") -> "general"
    else -> "other"
}

fun toGockModel(p1: JsonNode, mapper: ObjectMapper) =
    configureDelayAccordingToService(p1.get("request").get("url").asText()).let {
        GockModel(
            uri = URI(p1.get("request").get("url").asText()).path,
            params = URI(p1.get("request").get("url").asText())
                .query
                .split("&")
                .map { it.split("=") }
                .map { Pair(it[0], it[1]) }
                .toMap(),
            minDelayInMs = it,
            maxDelayInMs = it + maxDelay(p1.get("request").get("url").asText()),
            response = mapper.readTree(p1.get("response").get("body").textValue())
        )
    }

fun maxDelay(endpoint: String) = when {
    endpoint.contains("social")
            || endpoint.contains("lina")
            || endpoint.contains("apocalypse") -> uniformRandom(1, 0)
    else -> uniformRandom(200, 0)
}

fun configureDelayAccordingToService(endpoint: String) = when {
    endpoint.contains("flights") -> uniformRandom(650, 450)
    endpoint.contains("hotels") -> uniformRandom(650, 450)
    endpoint.contains("status") -> uniformRandom(650, 450)
    endpoint.contains("car") -> uniformRandom(650, 450)
    endpoint.contains("insurance") -> uniformRandom(650, 450)
    endpoint.contains("tour") -> uniformRandom(650, 450)
    endpoint.contains("ticket") -> uniformRandom(650, 450)
    endpoint.contains("bus") -> uniformRandom(650, 450)
    endpoint.contains("lina") -> uniformRandom(40, 20)
    endpoint.contains("social") -> uniformRandom(75, 40)
    endpoint.contains("apocalypse") -> uniformRandom(7, 5)
    else -> uniformRandom(650, 450)
}

fun normalDistribution(mean: Int, deviation: Int) = BigDecimal(Random().nextGaussian() * deviation + mean).toInt()

fun uniformRandom(max: Int, min: Int) = Random().nextInt(max - min) + min

object WiremockFiles {
    const val baseDir = "/home/jorgemangeruga/dev/api_v2_tests/load_test_pre_release"
    const val demo = baseDir + "/responses/mappings"
}

data class GockModel(
    val uri: String,
    val params: Map<String, String>,
    val minDelayInMs: Int,
    val maxDelayInMs: Int = minDelayInMs,
    val response: JsonNode
)