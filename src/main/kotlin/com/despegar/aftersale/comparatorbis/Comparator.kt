package com.despegar.aftersale.comparatorbis

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import khttp.get
import khttp.responses.Response
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.coroutines.experimental.channels.ReceiveChannel
import kotlinx.coroutines.experimental.channels.SendChannel
import kotlinx.coroutines.experimental.channels.consumeEach
import org.apache.http.impl.client.CloseableHttpClient
import org.apache.http.impl.client.HttpClients
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager
import org.json.JSONException
import org.skyscreamer.jsonassert.JSONAssert
import org.skyscreamer.jsonassert.JSONCompareMode
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.BufferedWriter
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files
import kotlin.coroutines.experimental.CoroutineContext
import kotlin.system.measureTimeMillis


val logger: Logger = LoggerFactory.getLogger("Comparator")

fun main(args: Array<String>) {
    val cm = PoolingHttpClientConnectionManager()

    cm.maxTotal = 500
    cm.defaultMaxPerRoute = 100

    val httpClient = HttpClients.custom()
        .setConnectionManager(cm)
        .build()

    val httpContext = newFixedThreadPoolContext(9, "httpContext")
    val workers = newFixedThreadPoolContext(9, "workers")
    runComparison(
        referenceEnvironment = "http://proxy",
        toTestEnvironment = "http://viale-b-00:9290",
        sourceFile = "./data/sources/viale/structured-ticket.in",
        outputFileName = "./data/outputs/viale/structured-ticket.html",
        limit = 2500,
        httpContext = httpContext,
        httpClient = httpClient,
        workersContext = workers
    )

}

fun runComparison(
    referenceEnvironment: String,
    toTestEnvironment: String,
    sourceFile: String,
    outputFileName: String,
    limit: Int,
    httpContext: CoroutineContext,
    httpClient: CloseableHttpClient,
    workersContext: CoroutineContext,
    workers: Int = 1,
    consumers: Int = 1
) {
    val channel = Channel<Result>()

    val requestProcessor = RequestProcessor(referenceEnvironment, toTestEnvironment, httpContext, httpClient)
    val jobs = arrayListOf<Job>()
    for (workList in requestsWorlistsFrom(sourceFile, limit, workers)) {
        jobs += processWorkListAsync(workList, channel, requestProcessor, workersContext)
    }

    val outputFileWriter = Files.newBufferedWriter(File(outputFileName).toPath(), Charset.forName("UTF-8"))
    val storeJob = arrayListOf<Job>()
    repeat(consumers) { storeJob += processResults(channel, outputFileWriter) }


    waitTillWorkIsDoneAndCloseChannel(jobs, channel)
    waitTillResultsAreStoredAndCloseStream(storeJob, outputFileWriter)
}

fun waitTillResultsAreStoredAndCloseStream(jobs: List<Job>, outputFileWriter: BufferedWriter) = runBlocking {
    jobs.forEach { it.join() }
    outputFileWriter.close()
}

fun waitTillWorkIsDoneAndCloseChannel(jobs: List<Job>, channel: Channel<Result>) =
    runBlocking {
        jobs.forEach { it.join() }
        channel.close()
    }

fun requestsWorlistsFrom(sourceFile: String, limit: Int, workers: Int): List<List<SimpleRequest>> {

    val requests = File(sourceFile)
        .readLines()
        .distinct()
        .take(limit)
        .shuffled()
        .map { SimpleRequest.of(it) }

    val workListSize = (requests.size / workers).takeUnless { it < 1 }?.toInt() ?: 1

    return requests
        .withIndex()
        .groupBy { it.index / workListSize }
        .map { it.value.map { it.value } }
}

fun processWorkListAsync(
    workList: List<SimpleRequest>,
    channel: SendChannel<Result>,
    requestProcessor: RequestProcessor,
    workersContext: CoroutineContext
) = launch(workersContext) {
    logger.info("Starting processing of worklist")
    val totalSpentTime = workList.mapIndexed { idx, it ->
        delay(300L)
        measureTimeMillis {
            channel.send(requestProcessor.processRequest(it))
        }.apply {
            logger.info("Request #${idx + 1} - Time: ${this}ms")
        }
    }.sum()
    logger.info("Average time : ${totalSpentTime / workList.size}ms")
    logger.info("Total time : ${totalSpentTime.toTime()}")
}

private fun Long.toTime(): String {
    val milis = (this % 1000).toInt()
    val mins = (this / 60000).toInt()
    val secs = (this / 1000).toInt() - (this / 60000).toInt() * 60

    return when {
        mins > 0 -> "${mins}m ${secs}s ${milis}ms"
        secs > 0 -> "${secs}s ${milis}ms"
        else -> "$${milis}ms"
    }
}

fun processResults(channel: ReceiveChannel<Result>, outputFileWriter: BufferedWriter) = launch(CommonPool) {
    outputFileWriter.write("<html>")

    var matchesCounter = 0
    var matchErrorsCounter = 0
    var executionErrorsCounter = 0

    channel.consumeEach {
        when (it) {
            is Match -> matchesCounter++
            is MatchError -> {
                matchErrorsCounter++
                outputFileWriter.write("<ul><li>Match error</li><li>${it.path}</li><li>${it.diff}</li></ul>")
            }
            is ExecutionError -> {
                executionErrorsCounter++
                outputFileWriter.write("<ul><li>Execution error</li><li>${it.path}</li><li>${it.errorDescription}</li></ul>")
            }
        }
        outputFileWriter.newLine()
    }
    outputFileWriter.write("<ul><li>Matches: $matchesCounter</li></ul>")
    outputFileWriter.write("<ul><li>Match errors: $matchErrorsCounter</li></ul>")
    outputFileWriter.write("<ul><li>Execution errors: $executionErrorsCounter</li></ul>")
    outputFileWriter.write("</html>")
}

class RequestProcessor(
    val referenceEnvironment: String,
    val toTestEnvironment: String,
    val httpContext: CoroutineContext,
    val httpClient: CloseableHttpClient
) {

    private val diffGenerator = DiffMatchPatch()
    val mapper = ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)

    suspend fun processRequest(request: SimpleRequest): Result {
        try {
            val deferredReferenceResponse = async(httpContext) { getAsync(referenceEnvironment, request) }
            val deferredToTestResponse = async(httpContext) { getAsync(toTestEnvironment, request) }
            val referenceResponse = deferredReferenceResponse.await()
            val toTestResponse = deferredToTestResponse.await()

            if (referenceResponse.statusCode == 200 && toTestResponse.statusCode == 200) {
                val toTestJson = orderedJsonString(toTestResponse)
                val referenceJson = orderedJsonString(referenceResponse)
                val diff = diffGenerator.diff_main(referenceJson.toString(), toTestJson.toString())

                return try {
                    JSONAssert.assertEquals(toTestJson, referenceJson, JSONCompareMode.NON_EXTENSIBLE)
                    Match()
                } catch(assert: AssertionError) {
                    MatchError(request.path, diffGenerator.diff_prettyHtml(diff))
                } catch (e: JSONException) {
                    logger.error("Error parsing responses for: $request", e)
                    ExecutionError(request.toString(), "Error parsing responses for: $request - Cause: ${e.message}")
                }
            } else {
                return ExecutionError(
                    request.toString(), "A response was not valid. " +
                            "Reference environment response status code: ${referenceResponse.statusCode} | " +
                            "Test environment response status code ${toTestResponse.statusCode}"
                )
            }
        } catch (e: GetException) {
            logger.error("Execution error", e)
            return ExecutionError(request.toString(), e.message ?: "")
        }
    }

    private fun orderedJsonString(response: Response) = mapper.writeValueAsString(
        mapper.treeToValue(mapper.readTree(response.text), Object::class.java)
    )

    private fun getAsync(host: String, request: SimpleRequest): Response {
        try {
            return get(
                url = host + request.path,
                timeout = 15.0,
                headers = (SHARED_HEADERS + request.headers).toMap()
            )
        } catch (e: Exception) {
            throw GetException("${e.message} for $request at $host", e)
        }
    }

    companion object {
        private const val XVO_BETA =
            "viale-cond=beta|empire-state-cond=beta|razor=beta|bowie=bt|hotelier-api=beta|cars-contracts-cond=b|dooku=dooku-beta|zeus=zeus-beta|ds-fnx-java=ds-fnx-b-java|ds-fnx-java-cond=beta"

        private val SHARED_HEADERS = listOf(
            "X-Client" to "Comparator"
            ,"X-UOW" to "cristianrana-comparator-0001"
//          ,"X-Version-Override" to XVO_BETA
//          ,"XDesp-Sandbox" to "true"
            ,"X-Session-Token" to "0x00000000000000000000000000"
            ,"X-Platform-Name" to "ios"
            ,"X-Platform-Version" to "6.0.0"
        )
    }

}

class SimpleRequest(val path: String, val headers: List<Pair<String, String>>) {

    companion object {
        private const val HEADERS_SEPARATOR = ";"

        fun of(rawRequestLine: String): SimpleRequest =
            rawRequestLine.split(" ").let { requestParts ->
                SimpleRequest(
                    path = requestParts[0],
                    headers = if (requestParts.size > 1) {
                        requestParts[1]
                            .split(HEADERS_SEPARATOR)
                            .map { it.split(":").let { headerKV -> Pair(headerKV[0], headerKV[1]) } }
                    } else {
                        emptyList()
                    }
                )
            }
    }

    override fun toString(): String {
        return "SimpleRequest(path='$path', headers=$headers)"
    }
}

class GetException(p0: String?, p1: Throwable?) : Exception(p0, p1)

interface Result
class Match : Result
class MatchError(val path: String, val diff: String) : Result
class ExecutionError(val path: String, val errorDescription: String) : Result