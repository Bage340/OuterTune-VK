package com.dd3boh.outertune.utils.potoken

class PoTokenException(message: String) : Exception(message)

// to be thrown if the WebView provided by the system is broken
class BadWebViewException(message: String) : Exception(message)

fun buildExceptionForJsError(error: String): Exception {
    return if (error.contains("SyntaxError"))
        BadWebViewException("JavaScript syntax error")
    else
        PoTokenException("JavaScript execution failed")
}
