package dora.widget

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Build.VERSION_CODES
import android.util.AttributeSet
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.hjq.permissions.Permission
import com.hjq.permissions.XXPermissions
import dora.BaseActivity
import dora.util.ViewUtils
import dora.widget.browser.DialogEvent.Companion.EVENT_TYPE_CALL
import dora.widget.browser.DialogEvent.Companion.EVENT_TYPE_ON_GEOLOCATION_PERMISSIONS_SHOW_PROMPT
import dora.widget.browser.DialogEvent.Companion.EVENT_TYPE_ON_JS_CONFIRM
import dora.widget.browser.DialogEvent.Companion.EVENT_TYPE_ON_RECEIVED_SSL_ERROR
import dora.widget.browser.R

class BrowserView @SuppressLint("SetJavaScriptEnabled")
    @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : NestedWebView(getFixedContext(context), attrs, android.R.attr.webViewStyle), LifecycleEventObserver {

    init {
        val settings = settings
        // 允许文件访问
        settings.allowFileAccess = true
        // 允许网页定位
        settings.setGeolocationEnabled(true)
        // 允许保存密码
        // settings.setSavePassword(true);
        // 开启 JavaScript
        settings.javaScriptEnabled = true
        // 允许网页弹对话框
        settings.javaScriptCanOpenWindowsAutomatically = true
        // 加快网页加载完成的速度，等页面完成再加载图片
        settings.loadsImagesAutomatically = true
        // 本地 DOM 存储（解决加载某些网页出现白板现象）
        settings.domStorageEnabled = true
        // 解决 Android 5.0 上 WebView 默认不允许加载 Http 与 Https 混合内容
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        // 不显示滚动条
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
    }

    /**
     * 获取当前的 url。
     *
     * @return 返回原始的 url，因为有些url是被WebView解码过的
     */
    override fun getUrl(): String? {
        val originalUrl = super.getOriginalUrl()
        // 避免开始时同时加载两个地址而导致的崩溃
        return originalUrl ?: super.getUrl()
    }

    /**
     * 设置 WebView 生命管控（自动回调生命周期方法）。
     */
    fun setLifecycleOwner(owner: LifecycleOwner) {
        owner.lifecycle.addObserver(this)
    }

    /**
     * [LifecycleEventObserver]。
     */
    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_RESUME -> {
                onResume()
                resumeTimers()
            }

            Lifecycle.Event.ON_PAUSE -> {
                onPause()
                pauseTimers()
            }

            Lifecycle.Event.ON_DESTROY -> onDestroy()
            else -> {}
        }
    }

    /**
     * 销毁 WebView。
     */
    fun onDestroy() {
        // 停止加载网页
        stopLoading()
        // 清除历史记录
        clearHistory()
        // 取消监听引用
        setBrowserChromeClient(null)
        setBrowserViewClient(null)
        // 移除WebView所有的View对象
        removeAllViews()
        // 销毁此的WebView的内部状态
        destroy()
    }

    /**
     * 已过时，推荐使用 [BrowserViewClient]。
     */
    @Deprecated("", ReplaceWith("super.setWebViewClient(client)"))
    override fun setWebViewClient(client: WebViewClient) {
        super.setWebViewClient(client)
    }

    fun setBrowserViewClient(client: BrowserViewClient?) {
        if (client != null) {
            super.setWebViewClient(client)
        }
    }

    /**
     * 已过时，推荐使用 [BrowserChromeClient]。
     */
    @Deprecated("", ReplaceWith("super.setWebChromeClient(client)"))
    override fun setWebChromeClient(client: WebChromeClient?) {
        super.setWebChromeClient(client)
    }

    fun setBrowserChromeClient(client: BrowserChromeClient?) {
        super.setWebChromeClient(client)
    }

    open class BrowserViewClient : WebViewClient() {

        /**
         * 网站证书校验错误。
         */
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            val activity = view.context as Activity
            DoraDoubleButtonDialog(activity, object : DoraDoubleButtonDialog.DialogListener {
                override fun onCancel(eventType: String) {
                    if (eventType == EVENT_TYPE_ON_RECEIVED_SSL_ERROR) {
                        handler.cancel()
                    }
                }

                @SuppressLint("WebViewClientOnReceivedSslError")
                override fun onConfirm(eventType: String) {
                    if (eventType == EVENT_TYPE_ON_RECEIVED_SSL_ERROR) {
                        handler.proceed()
                    }
                }

            }).show(EVENT_TYPE_ON_RECEIVED_SSL_ERROR,
                ContextCompat.getString(activity, R.string.common_web_ssl_error_title),
                positiveResId = R.string.common_web_ssl_error_allow,
                negativeResId = R.string.common_web_ssl_error_reject)
        }

        /**
         * 同名 API 兼容。
         */
        @TargetApi(VERSION_CODES.M)
        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            if (request.isForMainFrame) {
                onReceivedError(
                    view,
                    error.errorCode, error.description.toString(),
                    request.url.toString()
                )
            }
        }

        /**
         * 加载错误。
         */
        override fun onReceivedError(
            view: WebView,
            errorCode: Int,
            description: String,
            failingUrl: String
        ) {
            super.onReceivedError(view, errorCode, description, failingUrl)
        }

        /**
         * 同名 API 兼容。
         */
        @TargetApi(VERSION_CODES.N)
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            return shouldOverrideUrlLoading(view, request.url.toString())
        }

        /**
         * 跳转到其他链接。
         */
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
            val scheme = Uri.parse(url).scheme ?: return true
            when (scheme) {
                "http", "https" -> view.loadUrl(url)
                "tel" -> dialing(view, url)
                else -> {}
            }
            // 已经处理该链接请求
            return true
        }

        /**
         * 跳转到拨号界面。
         */
        open fun dialing(view: WebView, url: String) {
            val context = view.context as Activity
            DoraDoubleButtonDialog(context, object : DoraDoubleButtonDialog.DialogListener {
                override fun onCancel(eventType: String) {
                    if (eventType == EVENT_TYPE_CALL) {
                        // nothing to do
                    }
                }

                override fun onConfirm(eventType: String) {
                    if (eventType == EVENT_TYPE_CALL) {
                        val intent = Intent(Intent.ACTION_DIAL)
                        intent.data = Uri.parse(url)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    }
                }

            }).show(EVENT_TYPE_CALL, String.format(
                view.resources.getString(R.string.common_web_call_phone_title),
                url.replace("tel:", "")
            ), "", positiveResId = R.string.common_web_call_phone_allow,
                negativeResId = R.string.common_web_call_phone_reject)
        }
    }

    class BrowserChromeClient(private val webView: BrowserView?) : WebChromeClient() {

        init {
            requireNotNull(webView) { "web view, are you ok?" }
        }

        /**
         * 网页弹出警告框。
         */
        override fun onJsAlert(
            view: WebView,
            url: String,
            message: String,
            result: JsResult
        ): Boolean {
            view.context ?: return false
            Tips.showWarning(message)
            return true
        }

        /**
         * 网页弹出确定取消框。
         */
        override fun onJsConfirm(
            view: WebView,
            url: String,
            message: String,
            result: JsResult
        ): Boolean {
            val activity = view.context as Activity
            DoraDoubleButtonDialog(activity, object : DoraDoubleButtonDialog.DialogListener {
                override fun onCancel(eventType: String) {
                    if (eventType == EVENT_TYPE_ON_JS_CONFIRM) {
                        result.cancel()
                    }
                }

                override fun onConfirm(eventType: String) {
                    if (eventType == EVENT_TYPE_ON_JS_CONFIRM) {
                        result.confirm()
                    }
                }

            }).show(EVENT_TYPE_ON_JS_CONFIRM, message, "")
            return true
        }

        /**
         * 网页弹出输入框。
         */
        override fun onJsPrompt(
            view: WebView,
            url: String,
            message: String,
            defaultValue: String,
            result: JsPromptResult
        ): Boolean {
            val activity = view.context ?: return false
            DoraAlertDialog(activity).show(dora.widget.alertdialog.R.layout.dialog_dview_input) {
                positiveListener {
                    val editText = getView<AppCompatEditText>(dora.widget.alertdialog.R.id.et_dview_input)
                    result.confirm(ViewUtils.getText(editText))
                }
                negativeListener {
                    result.cancel()
                }
            }
            return true
        }

        override fun onGeolocationPermissionsShowPrompt(
            origin: String,
            callback: GeolocationPermissions.Callback
        ) {
            val activity = webView!!.context as Activity
            DoraDoubleButtonDialog(activity, object : DoraDoubleButtonDialog.DialogListener {
                override fun onCancel(eventType: String) {
                    if (eventType == EVENT_TYPE_ON_GEOLOCATION_PERMISSIONS_SHOW_PROMPT) {
                        callback.invoke(origin, false, true)
                    }
                }

                override fun onConfirm(eventType: String) {
                    if (eventType == EVENT_TYPE_ON_GEOLOCATION_PERMISSIONS_SHOW_PROMPT) {
                        XXPermissions.with(activity).permission(
                            Permission.ACCESS_FINE_LOCATION,
                            Permission.ACCESS_COARSE_LOCATION
                        )
                            .request { _, _ -> callback.invoke(origin, true, true) }
                    }
                }
            }).show(EVENT_TYPE_ON_GEOLOCATION_PERMISSIONS_SHOW_PROMPT,
                ContextCompat.getString(activity, R.string.common_web_location_permission_title),
                positiveResId = R.string.common_web_location_permission_allow,
                negativeResId = R.string.common_web_location_permission_reject)
        }

        /**
         * 网页弹出选择文件请求。
         * 测试地址：https://app.xunjiepdf.com/jpg2pdf/、http://www.script-tutorials.com/demos/199/index.html
         *
         * @param callback 文件选择回调
         * @param params   文件选择参数
         */
        @RequiresApi(VERSION_CODES.LOLLIPOP_MR1)
        override fun onShowFileChooser(
            webView: WebView,
            callback: ValueCallback<Array<Uri>>,
            params: FileChooserParams
        ): Boolean {
            val activity = webView.context as? BaseActivity<*> ?: return false
            XXPermissions.with(activity).permission(Permission.MANAGE_EXTERNAL_STORAGE)
                .request { _, _ -> openSystemFileChooser(activity, callback, params) }
            return true
        }

        /**
         * 打开系统文件选择器。
         */
        private fun openSystemFileChooser(
            activity: AppCompatActivity,
            callback: ValueCallback<Array<Uri>>,
            params: FileChooserParams
        ) {
            val intent = params.createIntent()
            val mimeTypes = params.acceptTypes
            if (mimeTypes != null && mimeTypes.isNotEmpty() && mimeTypes[0] != null && "" != mimeTypes[0]) {
                // 要过滤的文件类型
                intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
            }
            // 是否是多选模式
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.mode == FileChooserParams.MODE_OPEN_MULTIPLE)

            val activityResultLauncher: ActivityResultLauncher<Intent> = activity.registerForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) { result ->
                if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                    val data = result.data
                    val uri = data?.data
                    if (uri != null) {
                        // 如果用户只选择了一个文件
                        val uris = arrayOf(uri)
                        callback.onReceiveValue(uris)
                    } else {
                        // 如果用户选择了多个文件
                        val clipData = data?.clipData
                        if (clipData != null) {
                            val uris = Array(clipData.itemCount) { i ->
                                clipData.getItemAt(i).uri
                            }
                            callback.onReceiveValue(uris)
                        }
                    }
                    return@registerForActivityResult
                }
                // 不管用户最后有没有选择文件，最后必须要调用 onReceiveValue，如果没有调用就会导致网页再次点击上传无响应
                callback.onReceiveValue(null)
            }
            val chooser = Intent.createChooser(intent, params.title)
            activityResultLauncher.launch(chooser)
        }
    }

    companion object {

        init {
            // WebView 调试模式开关
            setWebContentsDebuggingEnabled(false)
        }

        /**
         * 修复原生 WebView 和 AndroidX 在 Android 5.x 上面崩溃的问题。
         *
         * doc：https://stackoverflow.com/questions/41025200/android-view-inflateexception-error-inflating-class-android-webkit-webview
         */
        private fun getFixedContext(context: Context): Context {
            return if (Build.VERSION.SDK_INT < VERSION_CODES.M) {
                // 为什么不用 ContextImpl，因为使用 ContextImpl 获取不到 Activity 对象，而 ContextWrapper 可以
                // 这种写法返回的 Context 是 ContextImpl，而不是 Activity 或者 ContextWrapper
                // return context.createConfigurationContext(new Configuration());
                // 如果使用 ContextWrapper 还是导致崩溃，因为 Resources 对象冲突了
                // return new ContextWrapper(context);
                // 如果使用 ContextThemeWrapper 就没有问题，因为它重写了 getResources 方法，返回的是一个新的 Resources 对象
                ContextThemeWrapper(context, context.theme)
            } else context
        }
    }
}