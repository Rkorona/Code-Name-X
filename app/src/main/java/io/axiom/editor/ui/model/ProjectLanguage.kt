package io.axiom.editor.ui.model

import androidx.compose.ui.graphics.Color

/**
 * 项目编程语言/技术栈。
 *
 * bgColor  — 图标背景色（各技术官方主色）
 * fgColor  — 图标文字色
 * symbol   — 图标中显示的缩写（1-3 个字符）
 *
 * 使用方式：在 Project 数据类中添加字段：
 *   val language: ProjectLanguage = ProjectLanguage.UNKNOWN
 */
enum class ProjectLanguage(
    val displayName: String,
    val bgColor: Color,
    val fgColor: Color,
    val symbol: String,
) {
    // ── Web / JS ──────────────────────────────────────────────────────
    NODEJS(     "Node.js",     Color(0xFF339933), Color.White,         "JS"),
    JAVASCRIPT( "JavaScript",  Color(0xFFF7DF1E), Color(0xFF323330),   "JS"),
    TYPESCRIPT( "TypeScript",  Color(0xFF3178C6), Color.White,         "TS"),
    HTML(       "HTML",        Color(0xFFE44D26), Color.White,         "H5"),
    CSS(        "CSS",         Color(0xFF264DE4), Color.White,         "Cs"),
    PHP(        "PHP",         Color(0xFF777BB4), Color.White,         "Ph"),

    // ── JVM ──────────────────────────────────────────────────────────
    KOTLIN(     "Kotlin",      Color(0xFF7F52FF), Color.White,         "Kt"),
    JAVA(       "Java",        Color(0xFFED8B00), Color.White,         "Jv"),

    // ── Mobile ───────────────────────────────────────────────────────
    FLUTTER(    "Flutter",     Color(0xFF54C5F8), Color(0xFF003D75),   "Fl"),
    DART(       "Dart",        Color(0xFF0175C2), Color.White,         "Dt"),
    SWIFT(      "Swift",       Color(0xFFF05138), Color.White,         "Sw"),

    // ── Systems ──────────────────────────────────────────────────────
    RUST(       "Rust",        Color(0xFFB7410E), Color.White,         "Rs"),
    GO(         "Go",          Color(0xFF00ADD8), Color.White,         "Go"),
    CPP(        "C++",         Color(0xFF00599C), Color.White,         "C+"),
    CSHARP(     "C#",          Color(0xFF512BD4), Color.White,         "C#"),

    // ── Scripting ────────────────────────────────────────────────────
    PYTHON(     "Python",      Color(0xFF306998), Color(0xFFFFE873),   "Py"),
    RUBY(       "Ruby",        Color(0xFFCC342D), Color.White,         "Rb"),

    // ── 未知 ─────────────────────────────────────────────────────────
    UNKNOWN(    "Unknown",     Color(0xFF757575), Color.White,         "?"),
    ;

    companion object {
        /**
         * 根据文件扩展名或配置文件自动推断语言。
         * 传入项目根目录的文件名列表（通过 File.list() 获取）。
         */
        fun detect(fileNames: List<String>): ProjectLanguage {
            val names = fileNames.map { it.lowercase() }
            return when {
                "package.json" in names && ("tsconfig.json" in names || names.any { it.endsWith(".ts") })
                    -> TYPESCRIPT
                "package.json" in names -> NODEJS
                "pubspec.yaml" in names -> FLUTTER
                "build.gradle.kts" in names || names.any { it.endsWith(".kt") } -> KOTLIN
                "build.gradle" in names || names.any { it.endsWith(".java") }   -> JAVA
                names.any { it.endsWith(".swift") }  -> SWIFT
                "cargo.toml" in names                -> RUST
                "go.mod" in names                    -> GO
                "requirements.txt" in names || "setup.py" in names || names.any { it.endsWith(".py") }
                    -> PYTHON
                names.any { it.endsWith(".rb") || it == "gemfile" } -> RUBY
                names.any { it.endsWith(".php") }    -> PHP
                names.any { it.endsWith(".cs") }     -> CSHARP
                names.any { it.endsWith(".cpp") || it.endsWith(".cc") } -> CPP
                names.any { it.endsWith(".html") }   -> HTML
                names.any { it.endsWith(".dart") }   -> DART
                else -> UNKNOWN
            }
        }
    }
}
