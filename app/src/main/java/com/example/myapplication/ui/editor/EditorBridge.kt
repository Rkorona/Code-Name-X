class EditorBridge(
    private val webView: WebView,
    private val onContentChanged: (String) -> Unit = {}
) {

    // Android → JS
    fun setContent(code: String) {
        val escaped = code.replace("\\", "\\\\").replace("`", "\\`")
        webView.evaluateJavascript("window.EditorBridge.setContent(`$escaped`)", null)
    }

    fun getContent(callback: (String) -> Unit) {
        webView.evaluateJavascript("window.EditorBridge.getContent()") { result ->
            // result 是带引号的 JSON 字符串，需要去掉引号
            callback(result.removeSurrounding("\"").replace("\\n", "\n"))
        }
    }

    // JS → Android（WebView addJavascriptInterface 注入的接口）
    @JavascriptInterface
    fun onEditorReady() {
        // 编辑器初始化完成回调
    }

    @JavascriptInterface
    fun onContentChange(content: String) {
        onContentChanged(content)
    }
}