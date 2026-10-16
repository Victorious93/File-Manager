package ai.victorious.filemanager.core.syntax

/**
 * Lightweight in-house tokenizer for syntax highlighting. Produces non-overlapping,
 * ordered spans; text not covered by a span is plain. Pure Kotlin so it is unit-testable
 * and maps onto Compose AnnotatedString in the app module.
 */
enum class TokenType { KEYWORD, STRING, COMMENT, NUMBER, TAG, ATTRIBUTE, ANNOTATION, HEADING, PUNCTUATION }

data class Token(val start: Int, val end: Int, val type: TokenType)

enum class Language { KOTLIN, JAVA, SMALI, XML, JSON, JAVASCRIPT, PYTHON, SHELL, MARKDOWN, PLAIN }

object SyntaxHighlighter {
    fun languageFor(fileName: String): Language = when (fileName.substringAfterLast('.', "").lowercase()) {
        "kt", "kts" -> Language.KOTLIN
        "java" -> Language.JAVA
        "smali" -> Language.SMALI
        "xml", "html", "htm", "svg", "plist" -> Language.XML
        "json" -> Language.JSON
        "js", "mjs", "cjs", "ts", "jsx", "tsx" -> Language.JAVASCRIPT
        "py" -> Language.PYTHON
        "sh", "bash", "zsh" -> Language.SHELL
        "md", "markdown" -> Language.MARKDOWN
        else -> Language.PLAIN
    }

    private val kotlinKw = setOf(
        "package", "import", "class", "interface", "object", "fun", "val", "var", "if", "else", "when", "for",
        "while", "do", "return", "break", "continue", "try", "catch", "finally", "throw", "is", "as", "in",
        "null", "true", "false", "this", "super", "private", "public", "protected", "internal", "override",
        "open", "abstract", "data", "sealed", "enum", "companion", "suspend", "inline", "lateinit", "const",
        "typealias", "by", "init", "constructor",
    )
    private val javaKw = setOf(
        "package", "import", "class", "interface", "enum", "extends", "implements", "public", "private",
        "protected", "static", "final", "abstract", "void", "int", "long", "short", "byte", "char", "boolean",
        "float", "double", "if", "else", "switch", "case", "default", "for", "while", "do", "return", "break",
        "continue", "try", "catch", "finally", "throw", "throws", "new", "this", "super", "null", "true",
        "false", "instanceof", "synchronized", "volatile", "transient", "native", "var", "record",
    )
    private val jsKw = setOf(
        "var", "let", "const", "function", "return", "if", "else", "for", "while", "do", "switch", "case",
        "default", "break", "continue", "new", "this", "class", "extends", "import", "export", "from", "async",
        "await", "try", "catch", "finally", "throw", "typeof", "instanceof", "null", "undefined", "true", "false",
        "of", "in", "yield", "interface", "type",
    )
    private val pyKw = setOf(
        "def", "class", "return", "if", "elif", "else", "for", "while", "in", "not", "and", "or", "is", "import",
        "from", "as", "try", "except", "finally", "raise", "with", "lambda", "yield", "pass", "break",
        "continue", "None", "True", "False", "global", "nonlocal", "async", "await", "del", "assert",
    )
    private val shKw = setOf(
        "if", "then", "else", "elif", "fi", "for", "in", "do", "done", "while", "until", "case", "esac",
        "function", "return", "export", "local", "readonly", "set", "unset", "echo", "exit", "source",
    )
    private val jsonKw = setOf("true", "false", "null")

    fun tokenize(text: String, language: Language): List<Token> = when (language) {
        Language.KOTLIN -> code(text, kotlinKw, lineComment = "//", block = true, annotations = true, tripleQuote = true)
        Language.JAVA -> code(text, javaKw, lineComment = "//", block = true, annotations = true)
        Language.JAVASCRIPT -> code(text, jsKw, lineComment = "//", block = true, backtick = true)
        Language.PYTHON -> code(text, pyKw, lineComment = "#", annotations = true, tripleQuote = true, single = true)
        Language.SHELL -> code(text, shKw, lineComment = "#", single = true, variables = true)
        Language.JSON -> code(text, jsonKw, lineComment = null)
        Language.SMALI -> smali(text)
        Language.XML -> xml(text)
        Language.MARKDOWN -> markdown(text)
        Language.PLAIN -> emptyList()
    }

    private fun isIdStart(c: Char) = c.isLetter() || c == '_'
    private fun isId(c: Char) = c.isLetterOrDigit() || c == '_'

    private fun code(
        s: String,
        keywords: Set<String>,
        lineComment: String?,
        block: Boolean = false,
        annotations: Boolean = false,
        tripleQuote: Boolean = false,
        backtick: Boolean = false,
        single: Boolean = true,
        variables: Boolean = false,
    ): List<Token> {
        val out = ArrayList<Token>()
        var i = 0
        val n = s.length
        while (i < n) {
            val c = s[i]
            when {
                lineComment != null && s.startsWith(lineComment, i) -> {
                    val e = s.indexOf('\n', i).let { if (it < 0) n else it }
                    out += Token(i, e, TokenType.COMMENT); i = e
                }
                block && s.startsWith("/*", i) -> {
                    val e = s.indexOf("*/", i + 2).let { if (it < 0) n else it + 2 }
                    out += Token(i, e, TokenType.COMMENT); i = e
                }
                tripleQuote && (s.startsWith("\"\"\"", i) || s.startsWith("'''", i)) -> {
                    val q = s.substring(i, i + 3)
                    val e = s.indexOf(q, i + 3).let { if (it < 0) n else it + 3 }
                    out += Token(i, e, TokenType.STRING); i = e
                }
                c == '"' || (single && c == '\'') || (backtick && c == '`') -> {
                    val e = stringEnd(s, i, c)
                    out += Token(i, e, TokenType.STRING); i = e
                }
                variables && c == '$' && i + 1 < n && (isIdStart(s[i + 1]) || s[i + 1] == '{') -> {
                    var e = i + 1
                    if (s[e] == '{') { e = s.indexOf('}', e).let { if (it < 0) n else it + 1 } }
                    else while (e < n && isId(s[e])) e++
                    out += Token(i, e, TokenType.ATTRIBUTE); i = e
                }
                annotations && c == '@' && i + 1 < n && isIdStart(s[i + 1]) -> {
                    var e = i + 1
                    while (e < n && (isId(s[e]) || s[e] == '.')) e++
                    out += Token(i, e, TokenType.ANNOTATION); i = e
                }
                c.isDigit() && (i == 0 || !isId(s[i - 1])) -> {
                    var e = i
                    while (e < n && (s[e].isLetterOrDigit() || s[e] == '.' || s[e] == '_')) e++
                    out += Token(i, e, TokenType.NUMBER); i = e
                }
                isIdStart(c) -> {
                    var e = i
                    while (e < n && isId(s[e])) e++
                    if (s.substring(i, e) in keywords) out += Token(i, e, TokenType.KEYWORD)
                    i = e
                }
                else -> i++
            }
        }
        return out
    }

    private fun stringEnd(s: String, start: Int, quote: Char): Int {
        var i = start + 1
        while (i < s.length) {
            val c = s[i]
            if (c == '\\') { i += 2; continue }
            if (c == quote) return i + 1
            if (c == '\n' && quote != '`') return i
            i++
        }
        return s.length
    }

    private fun smali(s: String): List<Token> {
        val out = ArrayList<Token>()
        var i = 0
        val n = s.length
        while (i < n) {
            val c = s[i]
            when {
                c == '#' -> { val e = s.indexOf('\n', i).let { if (it < 0) n else it }; out += Token(i, e, TokenType.COMMENT); i = e }
                c == '"' -> { val e = stringEnd(s, i, '"'); out += Token(i, e, TokenType.STRING); i = e }
                c == '.' && i + 1 < n && s[i + 1].isLetter() && (i == 0 || s[i - 1].isWhitespace()) -> {
                    var e = i + 1; while (e < n && (isId(s[e]) || s[e] == '-')) e++
                    out += Token(i, e, TokenType.KEYWORD); i = e
                }
                (c == 'v' || c == 'p') && i + 1 < n && s[i + 1].isDigit() && (i == 0 || !isId(s[i - 1])) -> {
                    var e = i + 1; while (e < n && s[e].isDigit()) e++
                    out += Token(i, e, TokenType.ATTRIBUTE); i = e
                }
                c == 'L' && (i == 0 || !isId(s[i - 1])) && s.indexOf(';', i).let { it > 0 && s.substring(i, it).none { ch -> ch.isWhitespace() } } -> {
                    val e = s.indexOf(';', i) + 1
                    out += Token(i, e, TokenType.TAG); i = e
                }
                c.isDigit() || (c == '-' && i + 1 < n && s[i + 1].isDigit()) -> {
                    var e = i + 1; while (e < n && (s[e].isLetterOrDigit())) e++
                    out += Token(i, e, TokenType.NUMBER); i = e
                }
                isIdStart(c) -> { var e = i; while (e < n && (isId(s[e]) || s[e] == '-' || s[e] == '/')) e++; i = e }
                else -> i++
            }
        }
        return out
    }

    private fun xml(s: String): List<Token> {
        val out = ArrayList<Token>()
        var i = 0
        val n = s.length
        while (i < n) {
            if (s.startsWith("<!--", i)) {
                val e = s.indexOf("-->", i).let { if (it < 0) n else it + 3 }
                out += Token(i, e, TokenType.COMMENT); i = e; continue
            }
            if (s[i] == '<') {
                var e = i + 1
                while (e < n && (s[e] == '/' || s[e] == '?' || s[e] == '!')) e++
                while (e < n && (isId(s[e]) || s[e] == ':' || s[e] == '-' || s[e] == '.')) e++
                out += Token(i, e, TokenType.TAG)
                i = e
                while (i < n && s[i] != '>') {
                    val c = s[i]
                    when {
                        c == '"' || c == '\'' -> { val q = s.indexOf(c, i + 1).let { if (it < 0) n else it + 1 }; out += Token(i, q, TokenType.STRING); i = q }
                        isIdStart(c) -> { var a = i; while (a < n && (isId(s[a]) || s[a] == ':' || s[a] == '-' || s[a] == '.')) a++; out += Token(i, a, TokenType.ATTRIBUTE); i = a }
                        else -> i++
                    }
                }
                if (i < n) { val st = if (i > 0 && s[i - 1] == '/' ) i - 1 else i; out += Token(maxOf(st, out.last().end), i + 1, TokenType.TAG); i++ }
                continue
            }
            i++
        }
        return out
    }

    private fun markdown(s: String): List<Token> {
        val out = ArrayList<Token>()
        var pos = 0
        var inFence = false
        var fenceStart = 0
        for (line in s.split('\n')) {
            val end = pos + line.length
            val t = line.trimStart()
            if (t.startsWith("```")) {
                if (inFence) { out += Token(fenceStart, end, TokenType.STRING); inFence = false } else { inFence = true; fenceStart = pos }
            } else if (!inFence) {
                when {
                    t.startsWith("#") -> out += Token(pos, end, TokenType.HEADING)
                    t.startsWith(">") -> out += Token(pos, end, TokenType.COMMENT)
                    else -> {
                        var i = 0
                        while (i < line.length) {
                            if (line[i] == '`') {
                                val e = line.indexOf('`', i + 1)
                                if (e > 0) { out += Token(pos + i, pos + e + 1, TokenType.STRING); i = e + 1; continue }
                            }
                            if (line.startsWith("**", i)) {
                                val e = line.indexOf("**", i + 2)
                                if (e > 0) { out += Token(pos + i, pos + e + 2, TokenType.KEYWORD); i = e + 2; continue }
                            }
                            i++
                        }
                    }
                }
            }
            pos = end + 1
        }
        if (inFence) out += Token(fenceStart, s.length, TokenType.STRING)
        return out
    }
}
