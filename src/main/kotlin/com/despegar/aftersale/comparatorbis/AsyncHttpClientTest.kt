package com.despegar.aftersale.comparatorbis

import com.typesafe.config.ConfigFactory
import io.netty.channel.EventLoopGroup
import io.netty.channel.nio.NioEventLoopGroup
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.runBlocking
import org.asynchttpclient.*
import org.asynchttpclient.Dsl.asyncHttpClient
import org.asynchttpclient.Response
import org.asynchttpclient.extras.typesafeconfig.AsyncHttpClientTypesafeConfig
import org.asynchttpclient.filter.FilterContext
import org.asynchttpclient.filter.RequestFilter
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.experimental.suspendCoroutine
import kotlin.system.measureTimeMillis

val logger3: Logger = LoggerFactory.getLogger("AsyncHttpClientTest")

internal class AsyncHttpClientThreadFactory(private val name: String) : ThreadFactory {

    override fun newThread(r: Runnable): Thread {
        return Thread(group, r, "$name-" + COUNT.getAndIncrement())
    }

    companion object {

        private val COUNT = AtomicLong(1)
    }

}

class WithDespegarRoutingConfig(private val baseConfig: AsyncHttpClientConfig) : AsyncHttpClientConfig by baseConfig {

    object AddDespeHeaders : RequestFilter {
        override fun <T : Any?> filter(ctx: FilterContext<T>) = ctx.apply { request.headers.add("Prueba", "Test") }
    }

    override fun getRequestFilters(): List<RequestFilter> {
        return baseConfig.requestFilters + listOf(
            AddDespeHeaders
        )
    }

}

class WithNioEventLoopGroup(
    private val baseConfig: AsyncHttpClientConfig,
    private val executor: Executor
) : AsyncHttpClientConfig by baseConfig {

    override fun getEventLoopGroup(): EventLoopGroup {
        return NioEventLoopGroup(1, executor) //La duda es, ¿cómo influye el threadCount cuando se provee un executor?
    }

}


fun main(args: Array<String>) = runBlocking {

    val cond = WithNioEventLoopGroup(
        WithDespegarRoutingConfig(
            AsyncHttpClientTypesafeConfig(ConfigFactory.load())
        ),
        Executors.newFixedThreadPool(1, AsyncHttpClientThreadFactory("EXECUTOR"))
    )

    val config = DefaultAsyncHttpClientConfig
        .Builder()
        .setMaxConnectionsPerHost(1)
        .setMaxConnections(1)
        .setConnectTimeout(1000)
        .setReadTimeout(2000)

    val clientAsync: AsyncHttpClient = asyncHttpClient(config)

//    val get = clientAsync.prepareGet("https://jsonplaceholder.typicode.com/posts/1/comments")

    val get = clientAsync.prepareGet("http://proxy/aftersale/v2/flights/32113905")
        .addHeader("x-client", "spectre")
        .addHeader("x-version-override","spectre-v2-cond=beta|ps-bifrost=beta|invoker-cond=beta|lion-cond=beta|empire-state-cond=beta|as-operations=beta|razor=beta|bowie=bt|hotelier-api=beta|cars-contracts-cond=b|jean=beta|dooku=dooku-beta")


    val time = measureTimeMillis {
        (1..1).map { async(CommonPool) { get.executeAndAwait() } }
            .mapNotNull {
                try {
                    it.await()
                } catch (e: Exception) {
                    logger3.error("EEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEE", e)
                    null
                }
            }
            .forEach {
                logger3.info("STATUSSSSS ${it.statusCode}")
                logger3.info(it.responseBody)
            }
    }


    logger3.info("TOTAL TIME : $time")

    clientAsync.close()
}


private suspend fun BoundRequestBuilder.executeAndAwait() = suspendCoroutine<Response> { cont ->
    logger3.info("))))))))))))))))))) EXECUTE 2")
    execute(
        object : AsyncCompletionHandlerBase() {

            override fun onCompleted(response: Response): Response {
                logger3.info("))))))))))))))))))) ON COMPLETED")
                cont.resume(response)
                return response
            }

            override fun onThrowable(t: Throwable) {
                cont.resumeWithException(t)
            }

        }
    )

}