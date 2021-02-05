package com.despegar.aftersale.comparatorbis

import kotlinx.coroutines.experimental.*
import org.apache.http.client.methods.HttpGet
import org.apache.http.impl.client.CloseableHttpClient
import org.apache.http.impl.client.HttpClients
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager
import org.apache.http.util.EntityUtils
import java.util.concurrent.TimeUnit
import kotlin.coroutines.experimental.CoroutineContext
import kotlin.system.measureTimeMillis

fun main(args: Array<String>) = runBlocking(CommonPool) {
    val cm = PoolingHttpClientConnectionManager()

    cm.maxTotal = 3
    cm.defaultMaxPerRoute = 3

    val httpClient: CloseableHttpClient = HttpClients.custom()
                                .setConnectionManager(cm)
                                .build()

    val httpContext: CoroutineContext = newFixedThreadPoolContext(1, "httpContext")

    val jobs = (1..3).toList().parallelMap(httpContext) {
        logger.info("precall" + coroutineContext)
            //testCall(httpClient)
            delay(1, TimeUnit.SECONDS)
    }

//    val jobs = (1..3).map {
//        launch(httpContext) {
//            logger.info("precall" + coroutineContext)
//            //testCall(httpClient)
//            delay(1, TimeUnit.SECONDS)
//        }
//    }

    measureTimeMillis {
        jobs.forEach {
            it
            logger.info("After joining" + coroutineContext)
        }
    }.let(::println)
}

suspend fun testCall(httpClient: CloseableHttpClient) {
    try {
        val r = httpClient.execute(HttpGet("http://localhost:8080/prueba"))
        EntityUtils.consume(r.entity)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

suspend fun <T, R> List<T>.parallelMap(interceptorContext: CoroutineContext,
                                       partitionSize: Int = 10,
                                       transform: suspend (T) -> R) = when {
    this.isEmpty() -> emptyList()
    this.size <= 1 -> listOf(transform(this.first()))
    this.size <= partitionSize -> this.mapAndAwait(interceptorContext) { transform(it) }
    else -> this.groupsOf(10).flatMap{ it.mapAndAwait(interceptorContext) { transform(it) } }
}

fun <T> List<T>.groupsOf(size: Int): List<List<T>> {
    var partial = this
    val result: MutableList<List<T>> = mutableListOf()
    while (partial.isNotEmpty()) {
        result += partial.take(size)
        partial = partial.drop(size)
    }
    return result
}

suspend fun <T, R> List<T>.mapAndAwait(interceptor: CoroutineContext,
                                       mapper: suspend (T) -> R) = this
        .map { async(interceptor) { mapper(it) } }
        .map { it.await() }


/*

typealias ResponseConfigurer = ((Request, Response) -> Any, ResponseConfigurableRoute) -> Unit

typealias ResponseConfigurableRoute = (String, Route, ResponseTransformer) -> Unit

get("/:tripId/payments", paymentController::payments, ::asJson)


private fun get(path: String, routeHandler: (Request, Response) -> Any, responseConfigurer: ResponseConfigurer) =
    responseConfigurer(routeHandler) { type, route, trans -> get(path, type, route, trans) }

private fun asJson(reg: (Request, Response) -> Any, typeRoute: ResponseConfigurableRoute) {
    typeRoute("application/json", Route(json(reg)), ResponseTransformer(mapper::writeValueAsString))
}

private fun json(route: (Request, Response) -> Any): (Request, Response) -> Any = { req, res ->
    val result = route(req, res)
    res.type("application/json; charset=utf-8")
    result
}


//SERIALIZE / DESERIALIZE usando locale

//        val a = mapper.writer().with(Locale.GERMAN).writeValueAsString("")

para serializar, sacar locale del
SerializerProvider




*/