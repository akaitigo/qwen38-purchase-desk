package com.example.purchasedesk

import com.example.purchasedesk.cli.Seed
import com.example.purchasedesk.data.Database
import com.example.purchasedesk.domain.ApiException
import com.example.purchasedesk.http.ApiServer
import com.example.purchasedesk.service.Service
import java.io.File

fun main(args: Array<String>) {
    val cmd = args.firstOrNull()
    val rest = args.drop(1)
    try {
        when (cmd) {
            "seed" -> runSeed(rest)
            "serve" -> runServe(rest)
            "backup" -> runBackup(rest)
            "version" -> println("purchase-desk 1.0.0")
            null, "help", "--help", "-h" -> printHelp()
            else -> {
                System.err.println("Unknown command: $cmd")
                printHelp()
                System.exit(2)
            }
        }
    } catch (e: ApiException) {
        System.err.println(e.message)
        System.exit(1)
    } catch (e: Exception) {
        System.err.println("エラー: ${e.message}")
        System.exit(1)
    }
}

private fun parseFlags(args: List<String>): Map<String, String> {
    val out = mutableMapOf<String, String>()
    var i = 0
    while (i < args.size) {
        val a = args[i]
        if (a.startsWith("--")) {
            val key = a.removePrefix("--")
            val v = if (i + 1 < args.size && !args[i + 1].startsWith("--")) args[i + 1] else "true"
            out[key] = v
            i += if (v == "true") 1 else 2
        } else {
            i++
        }
    }
    return out
}

private fun runSeed(args: List<String>) {
    val f = parseFlags(args)
    val dbPath = f["db"] ?: die("seed: --db が必須です。")
    val usersFile = f["users-file"] ?: die("seed: --users-file が必須です。")
    Seed.run(dbPath, usersFile)
}

private fun runServe(args: List<String>) {
    val f = parseFlags(args)
    val host = f["host"] ?: "127.0.0.1"
    val port = (f["port"] ?: "8080").toIntOrNull() ?: die("serve: --port は整数で指定してください。")
    val dbPath = f["db"] ?: die("serve: --db が必須です。")
    // Optional: trusted public origin for TLS-terminated deployments behind a
    // reverse proxy. When set, cookies carry Secure and the same-origin check
    // accepts this fixed origin (and the loopback HTTP host of the proxy).
    val publicOrigin = (f["public-origin"] ?: System.getenv("PURCHASE_PUBLIC_ORIGIN"))
        ?.takeIf { s -> s.isNotBlank() }
    if (publicOrigin != null && !isValidPublicOrigin(publicOrigin)) {
        die("serve: --public-origin / PURCHASE_PUBLIC_ORIGIN は絶対URI http(s)://host[:port] 形式で指定してください。")
    }

    val db = Database(dbPath)
    val service = Service(db)
    val server = ApiServer(db, service, host, port, publicOrigin)
    val shownOrigin = publicOrigin?.let { " (public-origin=$it, cookies: Secure)" } ?: ""
    println("Purchase Desk listening on http://$host:${server.port}/ (db=$dbPath)$shownOrigin")

    val latch = java.util.concurrent.CountDownLatch(1)
    val stopHook = Runtime.getRuntime().addShutdownHook(
        Thread {
            println("Shutting down...")
            try {
                server.close()
            } catch (_: Exception) {
            }
            try {
                db.close()
            } catch (_: Exception) {
            }
            latch.countDown()
        }
    )
    try {
        latch.await()
    } finally {
        try {
            stopHook
        } catch (_: Exception) {
        }
    }
}

private fun runBackup(args: List<String>) {
    val f = parseFlags(args)
    val dbPath = f["db"] ?: die("backup: --db が必須です。")
    val output = f["output"] ?: die("backup: --output が必須です。")
    val src = File(dbPath)
    if (!src.exists()) die("backup: DBファイルが存在しません: $dbPath")
    val dest = File(output)
    dest.absoluteFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
    // Use SQLite's online backup API (exposed by the sqlite-jdbc driver's
    // connection class) for a consistent snapshot even while a writer is active.
    val srcConn = java.sql.DriverManager.getConnection("jdbc:sqlite:$dbPath")
    try {
        srcConn.createStatement().use { it.execute("PRAGMA busy_timeout=10000;") }
        val src = unwrapDb(srcConn)
        val progress = object : org.sqlite.core.DB.ProgressObserver {
            override fun progress(remaining: Int, total: Int) {}
        }
        // sqlite-jdbc opens the destination connection from the path itself and
        // steps until the snapshot is complete (returns 0 on success).
        val remaining = src.backup("main", output, progress)
        if (remaining != 0) {
            die("backup: バックアップが不完全です (remaining=$remaining)。")
        }
    } finally {
        srcConn.close()
    }
    // Fold WAL/SHM so the output is a single self-contained file.
    consolidate(dest)
    println("Backup complete: $output")
}

/**
 * sqlite-jdbc returns a JDBC4Connection (or JDBC3Connection) that delegates to
 * the underlying org.sqlite.core.DB. unwrap() does not support jumping that
 * level, so we peel the wrapper via reflection on the exposed "db" field.
 */
@Suppress("UNCHECKED_CAST")
private fun unwrapDb(conn: java.sql.Connection): org.sqlite.core.DB {
    // org.sqlite.jdbc4.JDBC4Connection exposes the underlying DB via a field `db`.
    for (clazz in chainOf(conn.javaClass)) {
        val f: java.lang.reflect.Field? = try {
            clazz.declaredFields.firstOrNull { it.name == "db" }
        } catch (_: Exception) {
            null
        }
        if (f != null) {
            try {
                f.isAccessible = true
            } catch (_: Exception) {
            }
            return f.get(conn) as org.sqlite.core.DB
        }
    }
    throw IllegalStateException("Backup: cannot unwrap sqlite connection of type ${conn.javaClass.name}")
}

private fun chainOf(c: Class<*>): List<Class<*>> {
    val out = mutableListOf(c)
    var cur: Class<*>? = c.superclass
    while (cur != null && cur != Any::class.java) {
        out.add(cur)
        cur = cur.superclass
    }
    return out
}

/** Runs VACUUM on the target to produce a single self-contained file. */
private fun consolidate(path: File) {
    val conn = java.sql.DriverManager.getConnection("jdbc:sqlite:${path.absolutePath}")
    try {
        conn.createStatement().use { it.execute("VACUUM;") }
    } finally {
        conn.close()
    }
}

private fun die(msg: String): Nothing {
    System.err.println(msg)
    System.exit(2)
    throw IllegalStateException("unreachable")
}

/**
 * Validates the public-origin config value. Must be an absolute URI with an
 * http or https scheme and a resolvable host. The port, when present, must be
 * 1..65535.
 */
private fun isValidPublicOrigin(v: String): Boolean {
    return try {
        val u = java.net.URI(v.trim())
        val s = u.scheme?.lowercase() ?: return false
        (s == "http" || s == "https") &&
            (u.host?.isNotBlank() ?: false) &&
            (u.port == -1 || u.port in 1..65535)
    } catch (_: Exception) {
        false
    }
}

private fun printHelp() {
    println(
        """
        Purchase Desk
        Usage:
          serve  --host 127.0.0.1 --port PORT --db PATH
          seed   --db PATH --users-file PATH
          backup --db PATH --output PATH
          version
        """.trimIndent()
    )
}
