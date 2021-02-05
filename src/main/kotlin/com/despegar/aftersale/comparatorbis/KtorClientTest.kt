package com.despegar.aftersale.comparatorbis

import com.google.gson.FieldNamingPolicy
import io.ktor.cio.executor
import io.ktor.client.HttpClient
import io.ktor.client.engine.apache.Apache
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.config
import io.ktor.client.features.json.GsonSerializer
import io.ktor.client.features.json.JsonFeature
import io.ktor.client.request.get
import io.ktor.client.request.request
import io.ktor.client.response.HttpResponse
import io.ktor.network.util.ioThreadGroup
import io.netty.handler.codec.http.HttpHeaders
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.asCoroutineDispatcher
import kotlinx.coroutines.experimental.newFixedThreadPoolContext
import kotlinx.coroutines.experimental.runBlocking
import org.apache.http.impl.nio.reactor.IOReactorConfig
import org.asynchttpclient.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicLong
import org.asynchttpclient.Dsl.*
import org.asynchttpclient.Response
import org.asynchttpclient.handler.resumable.ResumableAsyncHandler
import org.asynchttpclient.netty.channel.DefaultChannelPool
import io.netty.util.HashedWheelTimer


val logger2: Logger = LoggerFactory.getLogger("KtorClientTest")

val httpContext = newFixedThreadPoolContext(1, "httpContext").executor().asCoroutineDispatcher()

internal class DefaultThreadFactory : ThreadFactory {

    override fun newThread(r: Runnable): Thread {
        return Thread(group, r, "Client SARASA I/O " + COUNT.getAndIncrement())
    }

    companion object {

        private val COUNT = AtomicLong(1)
    }

}

val group = ThreadGroup("I/O-dispatchers JJ")

fun main(args: Array<String>) = runBlocking(CommonPool) {
    val httpContext = newFixedThreadPoolContext(1, "httpContext").executor().asCoroutineDispatcher()
    logger2.info("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")


    val client2 = HttpClient(CIO.config {
        dispatcher = httpContext

    }) {
        install(JsonFeature) {
            serializer = GsonSerializer { setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES) }
        }
    }


//
    val client = HttpClient(Apache.config {
        socketTimeout = 1_0
        connectTimeout = 1_0
        connectionRequestTimeout = 1_0
        dispatcher = httpContext
        customizeClient {
            setThreadFactory(DefaultThreadFactory())
            setMaxConnTotal(1000)
            setMaxConnPerRoute(1000)
//            setDefaultIOReactorConfig(IOReactorConfig.custom().apply {
//                setMaxConnPerRoute(100)
//                setMaxConnTotal(100)
//                setIoThreadCount(8)
//            }.build())
        }
    }) {
        install(JsonFeature) {
            serializer = GsonSerializer { setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES) }
        }
    }

//    val a = client.get<HttpResponse>("http://dog.ceo/api/breeds/list/all")
//
//    a.receiveContent()
//
//    val ab = client.request<HttpResponse> {  }


//    client.createRequest()

//    logger2.info(a.toString())
}

data class Response(val status: String, val message: Message)

data class Message(val bulldog: List<String>)