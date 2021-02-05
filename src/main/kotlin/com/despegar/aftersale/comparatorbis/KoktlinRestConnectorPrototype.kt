package com.despegar.aftersale.comparatorbis

/*

package com.despegar.aftersale.client

import com.despegar.aftersale.api.BifrostFlightProduct
import com.despegar.aftersale.api.ClientRequest
import com.despegar.library.rest.HttpStatus
import com.despegar.library.rest.RestConnector
import com.despegar.library.rest.utils.TypeReference
import com.despegar.library.routing.headers.custom.CustomHeadersHelper
import com.despegar.library.routing.transactionid.TransactionIdConstants
import com.despegar.library.routing.transactionid.TransactionIdHelper
import com.despegar.library.routing.uow.UowConstants
import com.despegar.library.routing.uow.UowHelper
import com.despegar.library.routing.user.UserIdHelper
import com.despegar.library.routing.version.VersionOverrideConstants
import com.despegar.library.routing.version.VersionOverrideHelper
import com.fasterxml.jackson.databind.ObjectMapper
import io.netty.channel.EventLoopGroup
import io.netty.channel.epoll.EpollEventLoopGroup
import org.asynchttpclient.*
import org.asynchttpclient.Dsl.asyncHttpClient
import org.asynchttpclient.Dsl.get
import org.asynchttpclient.filter.FilterContext
import org.asynchttpclient.filter.RequestFilter
import org.slf4j.LoggerFactory
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.experimental.suspendCoroutine

class DeserializableResponse( // Ver nombre
    private val delegateResponse: Response,
    private val objectMapper: ObjectMapper
) : Response by delegateResponse { // Decoramos o encapsulamos y exponemos solo nuestra api?

    fun <T> deserializeBodyAs(type: TypeReference<T>): T =
        objectMapper.readValue(delegateResponse.responseBodyAsStream, type.clazz) //Chequear con implementacion rest connector

}


class KotlinAsyncHttpClient(
    private val delegateClient: AsyncHttpClient,
    private val objectMapper: ObjectMapper
) : AsyncHttpClient by delegateClient {

    suspend fun execute(requestBuilder: RequestBuilder) = suspendCoroutine<DeserializableResponse> { cont ->
        executeRequest(requestBuilder, object : AsyncCompletionHandlerBase() {

            override fun onCompleted(response: Response): Response {
                cont.resume(DeserializableResponse(response, objectMapper))
                return response
            }

            override fun onThrowable(t: Throwable) {
                cont.resumeWithException(t)
            }

        })

    }

}

class AsyncHttpClientThreadFactory(private val name: String) : ThreadFactory { // Puede ser innecesario, hay que pulirlo

    private val group = ThreadGroup("I/O-dispatchers JJ")

    override fun newThread(r: Runnable): Thread {
        return Thread(group, r, "$name-" + COUNT.getAndIncrement())
    }

    companion object {

        private val COUNT = AtomicLong(1)
    }

}

class DespegarRoutingConfig(private val baseConfig: AsyncHttpClientConfig) :
    AsyncHttpClientConfig by baseConfig {

    companion object {
        private const val X_CLIENT_HEADER = "x-client"
        private const val X_VERSION_HEADER = "x-version"
    }

    object AddDespeHeaders : RequestFilter {

        override fun <T : Any?> filter(ctx: FilterContext<T>): FilterContext<T> {
            with(ctx.request) {
                val uowData = UowHelper.getCurrentUowData()
                val uow = uowData.uow
                val requestId = uowData.requestId
                if (uow != null) {
                    headers.set(UowConstants.UNIT_OF_WORK_HEADER, uow.trim { it <= ' ' })
                }
                if (requestId != null) {
                    headers.set(UowConstants.REQUEST_ID_HEADER, requestId.trim { it <= ' ' })
                }

                val transactionId = TransactionIdHelper.getCurrentTransactionId()
                if (transactionId != null && transactionId.isNotEmpty()) {
                    headers
                        .set(TransactionIdConstants.TRANSACTION_ID_HEADER, transactionId.trim { it <= ' ' })
                }

                val versionOverride = VersionOverrideHelper.getVersionOverride().versionOverride
                if (versionOverride != null) {
                    headers.set(
                        VersionOverrideConstants.VERSION_OVERRIDE_HEADER,
                        versionOverride
                    )
                }

                val userId = UserIdHelper.getCurrentUserId()
                if (userId != null) {
                    headers.set(UserIdHelper.USER_ID_HEADER, userId)
                }
                CustomHeadersHelper.getAll().forEach { (key, value) -> headers.set(key, value) }


                headers.set(X_CLIENT_HEADER, "spectre")
                headers.set(X_VERSION_HEADER, "")

            }

            return ctx
        }

    }

    override fun getRequestFilters(): List<RequestFilter> {
        return baseConfig.requestFilters + listOf(
            AddDespeHeaders
        )
    }

}

class NioEventLoopGroupConfig(
    private val baseConfig: AsyncHttpClientConfig,
    private val executor: Executor
) : AsyncHttpClientConfig by baseConfig {

    override fun getEventLoopGroup(): EventLoopGroup {

        //La duda es, ¿cómo influye el threadCount cuando se provee un executor?
        return EpollEventLoopGroup(1, executor)
    }

}

class BifrostClient(val connector: RestConnector, private val objectMapper: ObjectMapper) {

    suspend fun flight(flightId: String, clientRequest: ClientRequest): BifrostFlightProduct? {

        val config = NioEventLoopGroupConfig(
            DespegarRoutingConfig(
                DefaultAsyncHttpClientConfig
                    .Builder()
                    .setMaxConnectionsPerHost(1)
                    .setMaxConnections(1)
                    .setConnectTimeout(1000)
                    .setReadTimeout(2000).build()
            ),
            Executors.newFixedThreadPool(1, AsyncHttpClientThreadFactory("EXECUTOR"))
        )

        val kotlinAsyncHttpClient = KotlinAsyncHttpClient(asyncHttpClient(config), objectMapper)

        return kotlinAsyncHttpClient.execute(
            get("http://proxy/aftersale/v2/flights/$flightId?locale=es_AR&source=site")
        ).also { LOGGER.info("STATUS: ${it.statusCode}") }
            .takeIf { it.statusCode == HttpStatus.OK.code }
            ?.deserializeBodyAs(FLIGHT_RESPONSE)

//        val get = clientAsync.prepareGet("http://proxy/aftersale/v2/flights/$flightId?locale=es_AR&source=site")
//
//
//        return get.executeAndAwait()
//            .also { LOGGER.info("STATUS: ") }
//            .takeIf { it.statusCode == HttpStatus.OK.code }
//            ?.let { objectMapper.readValue(it.responseBodyAsStream, FLIGHT_RESPONSE.clazz) }
//            .also { LOGGER.info("Result: ${it?:"NULO"}") }
//        val a: BifrostFlightProduct = objectMapper.readValue(istr, FLIGHT_RESPONSE.clazz)
//
//
//
//        val response = connector.get(Url.create("/aftersale/v2/flights/$flightId") {
//            "locale" with clientRequest.locale
//            clientRequest.source?.let { "source" with it }
//            "with" with "OPERATIONS"
//        }).execute()
//
//        if (response.status == HttpStatus.OK) {
//            return response.getBodyAs(FLIGHT_RESPONSE)
//        }
//        return null
    }

    companion object {
        private val FLIGHT_RESPONSE = object : TypeReference<BifrostFlightProduct>() {}
        private val LOGGER = LoggerFactory.getLogger(BifrostClient::class.java)
    }



//    private suspend fun BoundRequestBuilder.executeAndAwait() = suspendCoroutine<Response> { cont ->
//        execute(object : AsyncCompletionHandlerBase() {
//
//            override fun onCompleted(response: Response): Response {
//                cont.resume(response)
//                return response
//            }
//
//            override fun onThrowable(t: Throwable) {
//                cont.resumeWithException(t)
//            }
//
//        })
//
//    }


}



* */