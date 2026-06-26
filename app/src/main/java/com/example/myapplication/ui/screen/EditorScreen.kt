@Composable
fun CodeEditorScreen(
    initialCode: String = "",
    onSave: (String) -> Unit = {}
) {
    val context = LocalContext.current
    var bridge by remember { mutableStateOf<EditorBridge?>(null) }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccessFromFileURLs = true
                    allowUniversalAccessFromFileURLs = true
                }

                val editorBridge = EditorBridge(this)
                addJavascriptInterface(editorBridge, "Android")
                bridge = editorBridge

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        // 页面加载完后注入初始代码
                        view.evaluateJavascript(
                            "window.EditorBridge.init(${
                                initialCode.let {
                                    "`${it.replace("\\", "\\\\").replace("`", "\\`")}`"
                                }
                            })", null
                        )
                    }
                }

                loadUrl("file:///android_asset/editor/index.html")
            }
        }
    )

    // 保存按钮示例（悬浮）
    Box(Modifier.fillMaxSize()) {
        FloatingActionButton(
            onClick = { bridge?.getContent(onSave) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        ) {
            Icon(Icons.Default.Save, contentDescription = "保存")
        }
    }
}