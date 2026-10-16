package com.elintpos.wrapper.printer

/**
 * Serial ports worth trying when the K8 printer is not on USB.
 * ttyS0 is included last because on some boards it is the debug console.
 */
object K8SerialPorts {
    val BAUDS = intArrayOf(115200, 9600)
    const val MAX_PORTS = 8

    fun candidates(paths: List<String>, remembered: String? = null): List<String> {
        return paths
            .filter { rank(it) < 100 }
            .distinct()
            .sortedWith(compareBy({ if (it == remembered) 0 else 1 }, { rank(it) }, { it }))
            .take(MAX_PORTS)
    }

    fun rank(path: String): Int {
        val name = path.substringAfterLast('/').lowercase()
        return when {
            name.startsWith("ttyusb") -> 0
            name.startsWith("ttyacm") -> 1
            name.startsWith("ttys") && name != "ttys0" -> 2
            name.startsWith("ttymt") -> 3
            name.startsWith("ttyhs") -> 4
            name == "ttys0" -> 9
            else -> 100
        }
    }
}
