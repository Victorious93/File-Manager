package ai.victorious.victorfiles.core.diff

enum class DiffOp { EQUAL, DELETE, INSERT }

/** One diff line. Line numbers are 1-based; null when the line is absent on that side. */
data class DiffLine(val op: DiffOp, val text: String, val oldLine: Int?, val newLine: Int?)

/** Side-by-side row: left/right may be null for pure inserts/deletes. */
data class DiffRow(val left: DiffLine?, val right: DiffLine?)

/** Myers O(ND) line diff. */
object LineDiff {
    fun lines(text: String): List<String> = if (text.isEmpty()) emptyList() else text.removeSuffix("\n").split('\n').map { it.removeSuffix("\r") }

    fun diff(a: String, b: String): List<DiffLine> = diff(lines(a), lines(b))

    fun diff(a: List<String>, b: List<String>): List<DiffLine> {
        // Trim common prefix/suffix to keep the core small.
        var pre = 0
        while (pre < a.size && pre < b.size && a[pre] == b[pre]) pre++
        var suf = 0
        while (suf < a.size - pre && suf < b.size - pre && a[a.size - 1 - suf] == b[b.size - 1 - suf]) suf++
        val out = ArrayList<DiffLine>()
        for (k in 0 until pre) out += DiffLine(DiffOp.EQUAL, a[k], k + 1, k + 1)
        val ma = a.subList(pre, a.size - suf)
        val mb = b.subList(pre, b.size - suf)
        for (d in myers(ma, mb)) {
            out += when (d.op) {
                DiffOp.EQUAL -> d.copy(oldLine = d.oldLine!! + pre, newLine = d.newLine!! + pre)
                DiffOp.DELETE -> d.copy(oldLine = d.oldLine!! + pre)
                DiffOp.INSERT -> d.copy(newLine = d.newLine!! + pre)
            }
        }
        for (k in 0 until suf) {
            val ia = a.size - suf + k
            val ib = b.size - suf + k
            out += DiffLine(DiffOp.EQUAL, a[ia], ia + 1, ib + 1)
        }
        return out
    }

    private fun myers(a: List<String>, b: List<String>): List<DiffLine> {
        val n = a.size
        val m = b.size
        if (n == 0) return b.mapIndexed { j, s -> DiffLine(DiffOp.INSERT, s, null, j + 1) }
        if (m == 0) return a.mapIndexed { i, s -> DiffLine(DiffOp.DELETE, s, i + 1, null) }
        val max = n + m
        val off = max
        val v = IntArray(2 * max + 2)
        val trace = ArrayList<IntArray>()
        loop@ for (d in 0..max) {
            trace += v.copyOf()
            var k = -d
            while (k <= d) {
                var x = if (k == -d || (k != d && v[off + k - 1] < v[off + k + 1])) v[off + k + 1] else v[off + k - 1] + 1
                var y = x - k
                while (x < n && y < m && a[x] == b[y]) { x++; y++ }
                v[off + k] = x
                if (x >= n && y >= m) break@loop
                k += 2
            }
        }
        // Backtrack.
        val rev = ArrayList<DiffLine>()
        var x = n
        var y = m
        for (d in trace.size - 1 downTo 0) {
            val vd = trace[d]
            val k = x - y
            val prevK = if (k == -d || (k != d && vd[off + k - 1] < vd[off + k + 1])) k + 1 else k - 1
            val prevX = if (d == 0) 0 else vd[off + prevK]
            val prevY = prevX - prevK
            while (x > prevX && y > prevY) { rev += DiffLine(DiffOp.EQUAL, a[x - 1], x, y); x--; y-- }
            if (d > 0) {
                if (x == prevX) rev += DiffLine(DiffOp.INSERT, b[y - 1], null, y) else rev += DiffLine(DiffOp.DELETE, a[x - 1], x, null)
            }
            x = prevX; y = prevY
        }
        return rev.asReversed()
    }

    /** Pair consecutive delete/insert runs into side-by-side rows. */
    fun sideBySide(lines: List<DiffLine>): List<DiffRow> {
        val rows = ArrayList<DiffRow>()
        var i = 0
        while (i < lines.size) {
            val l = lines[i]
            if (l.op == DiffOp.EQUAL) { rows += DiffRow(l, l); i++; continue }
            val dels = ArrayList<DiffLine>()
            val ins = ArrayList<DiffLine>()
            while (i < lines.size && lines[i].op != DiffOp.EQUAL) {
                if (lines[i].op == DiffOp.DELETE) dels += lines[i] else ins += lines[i]
                i++
            }
            for (k in 0 until maxOf(dels.size, ins.size)) rows += DiffRow(dels.getOrNull(k), ins.getOrNull(k))
        }
        return rows
    }

    fun unified(lines: List<DiffLine>): String = lines.joinToString("\n") {
        when (it.op) { DiffOp.EQUAL -> " "; DiffOp.DELETE -> "-"; DiffOp.INSERT -> "+" } + it.text
    }

    fun stats(lines: List<DiffLine>): Pair<Int, Int> = lines.count { it.op == DiffOp.INSERT } to lines.count { it.op == DiffOp.DELETE }
}
