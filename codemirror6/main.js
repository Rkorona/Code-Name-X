// ─────────────────────────────────────────────────────────────
// CodeMirror 6 × Android WebView  entry point
// 构建命令: vite build  →  dist/assets/editor.js
// ─────────────────────────────────────────────────────────────

// ── 核心 ──────────────────────────────────────────────────────
import { EditorView, keymap }          from "@codemirror/view"
import { EditorState, Compartment }    from "@codemirror/state"
import { basicSetup }                  from "codemirror"
import { indentWithTab }               from "@codemirror/commands"
import { openSearchPanel,
         closeSearchPanel }            from "@codemirror/search"
import { StreamLanguage }              from "@codemirror/language"

// ── 语言包 ────────────────────────────────────────────────────
import { javascript }  from "@codemirror/lang-javascript"
import { python }      from "@codemirror/lang-python"
import { java }        from "@codemirror/lang-java"
import { css }         from "@codemirror/lang-css"
import { html }        from "@codemirror/lang-html"
import { json }        from "@codemirror/lang-json"
import { go }          from "@codemirror/lang-go"
import { rust }        from "@codemirror/lang-rust"
import { vue }         from "@codemirror/lang-vue"
import { xml }         from "@codemirror/lang-xml"
import { yaml }        from "@codemirror/lang-yaml"
import { markdown }    from "@codemirror/lang-markdown"
import { kotlin }      from "@codemirror/legacy-modes/mode/clike"
import { shell }       from "@codemirror/legacy-modes/mode/shell" // 🌟 新增：引入 Shell 传统模式


// ── 语法分析、缩进与 Linter 插件 ────────────────────────────────
import { syntaxTree, indentUnit }  from "@codemirror/language"
import { linter, lintGutter }      from "@codemirror/lint"

// ── 主题 ──────────────────────────────────────────────────────
// 内置默认深色主题（当用户未选择具体主题、跟随 App 明暗模式时使用）
import { oneDark }     from "@codemirror/theme-one-dark"

// @uiw/codemirror-themes-all 汇总了社区常见的编辑器配色方案，
// 全量具名导入后在 THEME_MAP 中注册，供设置页「编辑器主题」自由切换。
import {
  abcdef,
  abyss,
  androidstudio,
  andromeda,
  atomone,
  aura,
  basicLight,
  basicDark,
  bbedit,
  bespin,
  consoleLight,
  consoleDark,
  copilot,
  darcula,
  dracula,
  duotoneLight,
  duotoneDark,
  eclipse,
  githubLight,
  githubDark,
  gruvboxDark,
  gruvboxLight,
  kimbie,
  materialLight,
  materialDark,
  monokai,
  monokaiDimmed,
  noctisLilac,
  nord,
  okaidia,
  quietlight,
  red,
  solarizedLight,
  solarizedDark,
  sublime,
  tokyoNight,
  tokyoNightStorm,
  tokyoNightDay,
  tomorrowNightBlue,
  vscodeLight,
  vscodeDark,
  whiteLight,
  whiteDark,
  xcodeLight,
  xcodeDark,
} from "@uiw/codemirror-themes-all"

// ═════════════════════════════════════════════════════════════
// 1. Compartments — 支持运行时动态切换，无需重建编辑器
// ═════════════════════════════════════════════════════════════
const languageConf  = new Compartment()   // 当前语言
const themeConf     = new Compartment()   // 亮/暗主题
const inputModeConf = new Compartment()   // 键盘唤起行为
const editableConf  = new Compartment()   // 是否可编辑
const indentConf    = new Compartment()   // 缩进规格与 Tab 大小


// ═════════════════════════════════════════════════════════════
// 1.1 主题目录 — id → CM6 主题扩展
//     id 与 Android 端 EditorThemeMode 枚举保持一致，供设置页下拉选择
// ═════════════════════════════════════════════════════════════
const THEME_MAP = {
  // 特殊值：跟随 App 明暗模式（浅色为默认样式，深色为 oneDark）
  auto: null,

  oneDark,

  abcdef,
  abyss,
  androidstudio,
  andromeda,
  atomone,
  aura,
  basicLight,
  basicDark,
  bbedit,
  bespin,
  consoleLight,
  consoleDark,
  copilot,
  darcula,
  dracula,
  duotoneLight,
  duotoneDark,
  eclipse,
  githubLight,
  githubDark,
  gruvboxDark,
  gruvboxLight,
  kimbie,
  materialLight,
  materialDark,
  monokai,
  monokaiDimmed,
  noctisLilac,
  nord,
  okaidia,
  quietlight,
  red,
  solarizedLight,
  solarizedDark,
  sublime,
  tokyoNight,
  tokyoNightStorm,
  tokyoNightDay,
  tomorrowNightBlue,
  vscodeLight,
  vscodeDark,
  whiteLight,
  whiteDark,
  xcodeLight,
  xcodeDark,
}

/** 当前生效的主题 id 与 App 明暗状态，用于 auto 模式回退计算 */
let currentThemeId = "auto"
let currentIsDark   = false

/** 根据 currentThemeId / currentIsDark 计算出应生效的主题扩展 */
function resolveThemeExtension() {
  if (currentThemeId === "auto" || !(currentThemeId in THEME_MAP)) {
    return currentIsDark ? oneDark : []
  }
  return THEME_MAP[currentThemeId] || []
}

/** 将计算出的主题应用到编辑器 Compartment */
function applyResolvedTheme() {
  view.dispatch({ effects: themeConf.reconfigure(resolveThemeExtension()) })
}


// ═════════════════════════════════════════════════════════════
// 2. 语言映射表 — 文件扩展名 → CM6 语言扩展
// ═════════════════════════════════════════════════════════════
const LANG_MAP = {
  // JavaScript 系
  js:   () => javascript(),
  mjs:  () => javascript(),
  cjs:  () => javascript(),
  jsx:  () => javascript({ jsx: true }),
  ts:   () => javascript({ typescript: true }),
  tsx:  () => javascript({ jsx: true, typescript: true }),
  // Web
  html: () => html(),
  css:  () => css(),
  vue:  () => vue(),
  xml:  () => xml(),
  svg:  () => xml(),
  // 数据 / 配置
  json: () => json(),
  yaml: () => yaml(),
  yml:  () => yaml(),
  md:   () => markdown(),
  // 系统语言
  py:   () => python(),
  java: () => java(),
  go:   () => go(),
  rs:   () => rust(),
  
  // Kotlin 按需动态载入 (已妥善安置)
  kt:   () => StreamLanguage.define(kotlin), // 标准 Kotlin 文件
  kts:  () => StreamLanguage.define(kotlin), // Kotlin 脚本文件 (比如 build.gradle.kts)

  // Shell / Bash 脚本支持 (🌟 新增映射)
  sh:   () => StreamLanguage.define(shell),
  bash: () => StreamLanguage.define(shell),
  zsh:  () => StreamLanguage.define(shell),
}

/** 根据扩展名获取语言扩展 */
function getLang(ext) {
  const fn = LANG_MAP[ext?.toLowerCase?.()]
  return fn ? fn() : []
}


// ═════════════════════════════════════════════════════════════
// 3. Android WebView 核心优化：IME / 键盘唤起控制
// ═════════════════════════════════════════════════════════════
const ATTR_NO_KEYBOARD   = EditorView.contentAttributes.of({ inputmode: "none" })
const ATTR_WITH_KEYBOARD = EditorView.contentAttributes.of({ inputmode: "text" })


// ═════════════════════════════════════════════════════════════
// 4. 智能版 语法错误 Linter
//    利用 Lezer 的 Error 节点起止区间，动态截取文本并生成上下文提示
// ═════════════════════════════════════════════════════════════
const syntaxErrorLinter = linter((view) => {
  const diagnostics = []

  syntaxTree(view.state).iterate({
    enter: (node) => {
      if (node.type.isError || node.name === "Error") {
        let from = node.from
        let to = node.to

        // 规避零宽语法错误
        if (from === to) {
          if (from < view.state.doc.length) {
            to = from + 1
          } else if (from > 0) {
            from = from - 1
          }
        }

        // 💡 智能提取发生语法错误的原始文本
        const rawText = view.state.sliceDoc(node.from, node.to).trim()
        let errorMsg = "语法错误"

        if (rawText.length > 0) {
          // 如果截取的 token 过长，进行安全截断，防止报错气泡在小屏手机上撑爆
          const displayToken = rawText.length > 25 ? rawText.slice(0, 25) + "..." : rawText
          errorMsg = `语法错误: 意外的字符 "${displayToken}"`
        } else {
          errorMsg = "语法错误: 期待更完整的代码表达式"
        }

        diagnostics.push({
          from,
          to,
          severity: "error",
          message: errorMsg
        })
      }
    }
  })

  const errorsCount = diagnostics.filter(d => d.severity === "error").length
  const warningsCount = diagnostics.filter(d => d.severity === "warning").length

  if (window.AndroidBridge && typeof window.AndroidBridge.onDiagnosticsChanged === "function") {
    requestAnimationFrame(() => {
      window.AndroidBridge.onDiagnosticsChanged(errorsCount, warningsCount)
    })
  }

  return diagnostics
})

// ═════════════════════════════════════════════════════════════
// 5. 缩进自适应启发式检测算法
//    扫描前 150 行代码，提取最频繁出现的缩进样式与步长
// ═════════════════════════════════════════════════════════════
function detectIndentation(text) {
  const lines = text.split("\n")
  let spaces = 0
  let tabs = 0
  const spaceCounts = {}

  for (let i = 0; i < Math.min(lines.length, 150); i++) {
    const line = lines[i]
    const match = line.match(/^([ \t]+)\S/)
    if (match) {
      const indent = match[1]
      if (indent.includes("\t")) {
        tabs++
      } else {
        spaces++
        const len = indent.length
        if (len > 0) {
          spaceCounts[len] = (spaceCounts[len] || 0) + 1
        }
      }
    }
  }

  if (tabs > spaces) {
    return { type: "Tab", size: 4 }
  }

  let bestSize = 4
  let maxCount = 0
  for (const sizeStr in spaceCounts) {
    const size = parseInt(sizeStr, 10)
    const count = spaceCounts[sizeStr]
    if (count > maxCount && (size === 2 || size === 4 || size === 8)) {
      maxCount = count
      bestSize = size
    }
  }

  if (maxCount === 0) {
    for (const sizeStr in spaceCounts) {
      const size = parseInt(sizeStr, 10)
      const count = spaceCounts[sizeStr]
      if (count > maxCount) {
        maxCount = count
        bestSize = size
      }
    }
  }

  return { type: "Space", size: bestSize }
}


// ═════════════════════════════════════════════════════════════
// 6. 编辑器实例
// ═════════════════════════════════════════════════════════════
const container = document.getElementById("editor")

// 初始明暗状态：跟随系统偏好，供 auto 主题回退计算
currentIsDark = window.matchMedia('(prefers-color-scheme: dark)').matches

const view = new EditorView({
  state: EditorState.create({
    doc: "",
    extensions: [
      basicSetup,
      keymap.of([indentWithTab]),
      syntaxErrorLinter,
      lintGutter(),

      // ── 动态 Compartment 初始值 ──
      languageConf.of(getLang("js")),
      themeConf.of(resolveThemeExtension()),
      inputModeConf.of(ATTR_NO_KEYBOARD),
      editableConf.of(EditorView.editable.of(true)),
      indentConf.of([indentUnit.of("    "), EditorState.tabSize.of(4)]), // 默认4空格对齐

      // ── 变更 / 选区/ 重构事件 → 通知 Android ──
      EditorView.updateListener.of((update) => {
        if (!window.AndroidBridge) return
        
        if (update.docChanged || update.transactions.some(tr => tr.reconfigured)) {
          const doc = update.state.doc
          
          const indentVal = update.state.facet(indentUnit)
          const isTab = indentVal.includes("\t")
          const indentSize = isTab ? update.state.tabSize : indentVal.length
          const indentLabel = `${isTab ? "Tab" : "Spaces"}: ${indentSize}`

          AndroidBridge.onStatsChanged(doc.lines, doc.length, indentLabel)
        }
        
        if (update.selectionSet) {
          const sel  = update.state.selection.main
          const line = update.state.doc.lineAt(sel.head)
          AndroidBridge.onCursorChanged(
            line.number,
            sel.head - line.from + 1
          )
        }
      }),

      EditorView.theme({
        "&": {
          height: "100%",
          fontSize: "14px",
        },
        ".cm-scroller": {
          overflow: "auto",
          fontFamily: "'JetBrains Mono', 'Fira Code', 'Cascadia Code', monospace",
        },
        ".cm-tooltip": { maxWidth: "90vw" },
        ".cm-tooltip-autocomplete > ul": { maxHeight: "40vh" },
        ".cm-diagnostic": {
          fontSize: "12px",
          fontFamily: "system-ui, sans-serif"
        }
      }),
    ],
  }),
  parent: container,
})


// ═════════════════════════════════════════════════════════════
// 7. 工具函数
// ═════════════════════════════════════════════════════════════

function b64Encode(str) {
  return btoa(unescape(encodeURIComponent(str)))
}

function b64Decode(b64) {
  return decodeURIComponent(escape(atob(b64)))
}


// ═════════════════════════════════════════════════════════════
// 8. Android Bridge API
// ═════════════════════════════════════════════════════════════
window.editorAPI = {

  // ── 内容读写 ────────────────────────────────────────────────

  getContent: () => view.state.doc.toString(),

  getContentBase64: () => b64Encode(view.state.doc.toString()),

  setContent: (code) => {
    view.dispatch({
      changes: { from: 0, to: view.state.doc.length, insert: code },
      selection: { anchor: 0 },
    })
    view.scrollDOM.scrollTop = 0

    const detected = detectIndentation(code)
    window.editorAPI.setIndentation(detected.type, detected.size)
  },

  setContentBase64: (b64) => {
    window.editorAPI.setContent(b64Decode(b64))
  },


  // ── 缩进格式动态变更 ─────────────────────────────────

  /**
   * 动态配置编辑器的对齐步长与格式
   * @param {"Tab"|"Space"} type
   * @param {number} size
   */
  setIndentation: (type, size) => {
    const unit = type === "Tab" ? "\t" : " ".repeat(size)
    view.dispatch({
      effects: indentConf.reconfigure([
        indentUnit.of(unit),
        EditorState.tabSize.of(size)
      ])
    })
  },


  // ── 语言 / 主题切换 ─────────────────────────────────────────

  setLanguage: (ext) => {
    view.dispatch({ effects: languageConf.reconfigure(getLang(ext)) })
  },

  /**
   * 兼容旧接口：仅切换 App 明暗态。
   * 若当前主题为具体主题（非 auto），主题本身不受影响，
   * 只有背景色变量与 auto 回退色会更新。
   */
  setTheme: (dark) => {
    currentIsDark = !!dark
    applyResolvedTheme()
    document.documentElement.style.setProperty('--editor-bg', dark ? '#141729' : '#ffffff')
  },

  /**
   * 自由切换编辑器配色主题
   * @param {string} themeId 主题 id，取值见 THEME_MAP（"auto" 表示跟随 App 明暗模式）
   */
  setEditorTheme: (themeId) => {
    currentThemeId = (themeId && themeId in THEME_MAP) ? themeId : "auto"
    applyResolvedTheme()
  },

  /** 返回当前可选的主题 id 列表，便于 Android 端做校验 / 调试 */
  getEditorThemes: () => Object.keys(THEME_MAP),


  // ── 键盘 / IME 控制 ─────────────────────────────────────────

  enableKeyboard: () => {
    view.dispatch({ effects: inputModeConf.reconfigure(ATTR_WITH_KEYBOARD) })
    view.focus()
  },

  disableKeyboard: () => {
    view.dispatch({ effects: inputModeConf.reconfigure(ATTR_NO_KEYBOARD) })
    view.contentDOM.blur()
  },


  // ── 只读模式 ────────────────────────────────────────────────

  setReadOnly: (readonly) => {
    view.dispatch({
      effects: editableConf.reconfigure(EditorView.editable.of(!readonly)),
    })
  },


  // ── 光标移动────────────────

  moveCursor: (dir) => {
    const state = view.state
    const doc   = state.doc
    let   pos   = state.selection.main.head

    if (dir === "left") {
      pos = Math.max(0, pos - 1)
    } else if (dir === "right") {
      pos = Math.min(doc.length, pos + 1)
    } else if (dir === "up") {
      const line = doc.lineAt(pos)
      if (line.number > 1) {
        const col      = pos - line.from
        const prevLine = doc.line(line.number - 1)
        pos = Math.min(prevLine.from + col, prevLine.to)
      }
    } else if (dir === "down") {
      const line = doc.lineAt(pos)
      if (line.number < doc.lines) {
        const col      = pos - line.from
        const nextLine = doc.line(line.number + 1)
        pos = Math.min(nextLine.from + col, nextLine.to)
      }
    }

    view.dispatch({ selection: { anchor: pos }, scrollIntoView: true })
  },


  // ── 选区操作 ────────────────────────────────────────────────

  selectAll: () => {
    view.dispatch({
      selection: { anchor: 0, head: view.state.doc.length },
    })
  },

  getSelection: () => {
    const sel = view.state.selection.main
    return view.state.doc.sliceString(sel.from, sel.to)
  },

  getSelectionBase64: () => b64Encode(window.editorAPI.getSelection()),


  // ── 剪贴板操作 ──────────────────────────────────────────────

  copySelected: () => window.editorAPI.getSelectionBase64(),

  cutSelected: () => {
    const sel  = view.state.selection.main
    const text = view.state.doc.sliceString(sel.from, sel.to)
    if (sel.from !== sel.to) {
      view.dispatch({ changes: { from: sel.from, to: sel.to, insert: "" } })
    }
    return b64Encode(text)
  },

  insertTextBase64: (b64) => {
    const text = b64Decode(b64)
    const sel  = view.state.selection.main
    view.dispatch({
      changes:   { from: sel.from, to: sel.to, insert: text },
      selection: { anchor: sel.from + text.length },
    })
  },


  // ── 搜索面板 ────────────────────────────────────────────────

  openSearch:  () => openSearchPanel(view),
  closeSearch: () => closeSearchPanel(view),


  // ── 就绪通知 ────────────────────────────────────────────────

  notifyReady: () => {
    if (window.AndroidBridge) AndroidBridge.onReady()
  },
}


// ═════════════════════════════════════════════════════════════
// 9. 初始化就绪通知
// ═════════════════════════════════════════════════════════════
window.editorAPI.notifyReady()
