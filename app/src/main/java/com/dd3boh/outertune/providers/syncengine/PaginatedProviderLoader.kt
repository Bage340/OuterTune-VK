package com.dd3boh.outertune.providers.syncengine

import com.dd3boh.outertune.providers.ProviderError
import com.dd3boh.outertune.providers.ProviderErrorCode
import com.dd3boh.outertune.providers.ProviderId
import com.dd3boh.outertune.providers.ProviderResult
import com.dd3boh.outertune.providers.domain.PageRequest
import com.dd3boh.outertune.providers.domain.ProviderPage
import kotlinx.coroutines.CancellationException

data class PaginatedLoad<T>(
    val items: List<T>,
    val pagesRead: Int,
    val isComplete: Boolean,
    val error: ProviderError? = null,
)

internal object PaginatedProviderLoader {
    suspend fun <T> load(
        provider: ProviderId,
        pageSize: Int,
        maxPages: Int,
        fetch: suspend (PageRequest) -> ProviderResult<ProviderPage<T>>,
    ): PaginatedLoad<T> {
        val items = mutableListOf<T>()
        val seenContinuationTokens = mutableSetOf<String>()
        var continuationToken: String? = null
        var pagesRead = 0

        while (pagesRead < maxPages) {
            val result = try {
                fetch(PageRequest(continuationToken = continuationToken, pageSize = pageSize))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                return PaginatedLoad(
                    items = items,
                    pagesRead = pagesRead,
                    isComplete = false,
                    error = ProviderError(
                        provider = provider,
                        code = ProviderErrorCode.UNKNOWN,
                        message = "Provider request threw ${error.safeClassName()}",
                        isRetryable = true,
                    ),
                )
            }

            when (result) {
                is ProviderResult.Failure -> return PaginatedLoad(
                    items = items,
                    pagesRead = pagesRead,
                    isComplete = false,
                    error = result.error.copy(
                        message = "Provider request failed with ${result.error.code.name}"
                    ),
                )

                is ProviderResult.Success -> {
                    pagesRead += 1
                    items += result.value.items
                    val next = result.value.continuationToken
                        ?: return PaginatedLoad(items, pagesRead, isComplete = true)
                    if (!seenContinuationTokens.add(next)) {
                        return PaginatedLoad(
                            items = items,
                            pagesRead = pagesRead,
                            isComplete = false,
                            error = ProviderError(
                                provider = provider,
                                code = ProviderErrorCode.MALFORMED_RESPONSE,
                                message = "Provider repeated a continuation token",
                            ),
                        )
                    }
                    continuationToken = next
                }
            }
        }

        return PaginatedLoad(
            items = items,
            pagesRead = pagesRead,
            isComplete = false,
            error = ProviderError(
                provider = provider,
                code = ProviderErrorCode.MALFORMED_RESPONSE,
                message = "Provider pagination page limit reached",
            ),
        )
    }

    private fun Throwable.safeClassName(): String =
        this::class.java.simpleName.takeIf(String::isNotBlank) ?: "Exception"
}
