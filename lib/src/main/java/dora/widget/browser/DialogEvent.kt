package dora.widget.browser

interface DialogEvent {

    companion object {

        const val EVENT_TYPE_ON_JS_CONFIRM = "onJsConfirm"
        const val EVENT_TYPE_ON_RECEIVED_SSL_ERROR = "onReceivedSslError"
        const val EVENT_TYPE_CALL = "call"
    }
}