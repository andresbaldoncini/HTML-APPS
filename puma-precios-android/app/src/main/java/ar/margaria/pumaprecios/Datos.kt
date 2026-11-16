package ar.margaria.pumaprecios

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

val PRODUCTOS = listOf("SUPER", "PREMIUM", "DIESEL COMUN", "DIESEL PREMIUM")
val PRODUCTOS_CORTO = listOf("Súper", "Premium", "Diésel", "D. Premium")
val FUENTES = listOf("YPF", "SHELL", "PUMA", "SUGERIDO", "COSTO PUMA")
val FUENTES_CORTO = listOf("YPF", "Shell", "Puma", "Sug.", "Costo")

const val F_YPF = 0
const val F_SHELL = 1
const val F_PUMA = 2
const val F_SUGERIDO = 3
const val F_COSTO = 4

const val HORA_MS = 3_600_000L
const val DIA_MS = 24 * HORA_MS

class Dia(val fecha: LocalDate, val v: List<List<Double?>>)   // v[producto][fuente]
class Cambio(val t: Long, val f: Int, val p: Int, val ant: Double?, val nue: Double)
class Datos(val actualizado: Long, val dias: List<Dia>, val cambios: List<Cambio>)
class Variacion(val abs: Double, val pct: Double)

fun parsear(texto: String): Datos {
    val o = JSONObject(texto)
    if (!o.optBoolean("ok", false)) throw Exception(o.optString("error", "Respuesta inválida del servidor"))
    val jd = o.getJSONArray("diario")
    val dias = (0 until jd.length()).map { i ->
        val d = jd.getJSONObject(i)
        val v = d.getJSONArray("v")
        Dia(
            LocalDate.parse(d.getString("f")),
            List(PRODUCTOS.size) { p ->
                val fila = v.getJSONArray(p)
                List(FUENTES.size) { f -> if (f >= fila.length() || fila.isNull(f)) null else fila.getDouble(f) }
            }
        )
    }
    val jh = o.getJSONArray("historial")
    val cambios = (0 until jh.length()).mapNotNull { i ->
        val h = jh.getJSONObject(i)
        val f = FUENTES.indexOf(h.getString("fu"))
        val p = PRODUCTOS.indexOf(h.getString("p"))
        if (f < 0 || p < 0) null
        else Cambio(h.getLong("t"), f, p, if (h.isNull("a")) null else h.getDouble("a"), h.getDouble("n"))
    }.sortedBy { it.t }
    return Datos(o.optLong("actualizado", System.currentTimeMillis()), dias, cambios)
}

/** Precio vigente: último valor de DIARIO; si no hay, último NUEVO de HISTORIAL. */
fun Datos.actual(p: Int, f: Int): Double? {
    for (i in dias.indices.reversed()) dias[i].v[p][f]?.let { return it }
    return cambios.lastOrNull { it.p == p && it.f == f }?.nue
}

/** Cambios reales (con precio anterior) de un producto/fuente dentro de la ventana. */
fun Datos.cambiosEn(p: Int, f: Int, ventanaMs: Long, ahora: Long): List<Cambio> =
    cambios.filter { it.p == p && it.f == f && it.ant != null && it.t >= ahora - ventanaMs }

/** Variación = precio actual − precio antes del primer cambio de la ventana. null = sin cambios. */
fun Datos.variacion(p: Int, f: Int, ventanaMs: Long, ahora: Long): Variacion? {
    val cs = cambiosEn(p, f, ventanaMs, ahora)
    if (cs.isEmpty()) return null
    val base = cs.first().ant!!
    val cur = actual(p, f) ?: cs.last().nue
    val d = cur - base
    return Variacion(d, if (base != 0.0) d / base * 100.0 else 0.0)
}

// ---------------- Formato ----------------

private val LOCALE_AR: Locale = Locale.forLanguageTag("es-AR")
private val ZONA: ZoneId = ZoneId.systemDefault()

fun precio(x: Double?, decimales: Boolean = false): String {
    if (x == null) return "—"
    val nf = NumberFormat.getNumberInstance(LOCALE_AR)
    val conDec = decimales && abs(x - Math.round(x)) > 0.004
    nf.minimumFractionDigits = if (conDec) 2 else 0
    nf.maximumFractionDigits = if (conDec) 2 else 0
    return nf.format(x)
}

fun conSigno(x: Double, decimales: Boolean = false): String {
    val s = precio(abs(x), decimales)
    return when {
        x > 0.004 -> "+$s"
        x < -0.004 -> "−$s"
        else -> "0"
    }
}

fun pct(x: Double): String {
    val nf = NumberFormat.getNumberInstance(LOCALE_AR)
    nf.minimumFractionDigits = 1
    nf.maximumFractionDigits = 1
    val s = nf.format(abs(x))
    return when {
        x > 0.04 -> "+$s%"
        x < -0.04 -> "−$s%"
        else -> "0%"
    }
}

fun fechaHora(ms: Long): String =
    DateTimeFormatter.ofPattern("dd/MM HH:mm").format(Instant.ofEpochMilli(ms).atZone(ZONA))

fun diaMes(d: LocalDate): String = DateTimeFormatter.ofPattern("dd/MM").format(d)

// ---------------- Repositorio ----------------

class Repo(ctx: Context) {
    private val sp = ctx.getSharedPreferences("puma_precios", Context.MODE_PRIVATE)

    var url: String
        get() = sp.getString("url", "") ?: ""
        set(v) = sp.edit().putString("url", v).apply()

    var clave: String
        get() = sp.getString("clave", "") ?: ""
        set(v) = sp.edit().putString("clave", v).apply()

    var cache: String?
        get() = sp.getString("cache", null)
        set(v) = sp.edit().putString("cache", v).apply()

    suspend fun descargar(): String = withContext(Dispatchers.IO) {
        val base = url.trim()
        val sep = if (base.contains("?")) "&" else "?"
        val u = URL(base + sep + "key=" + URLEncoder.encode(clave.trim(), "UTF-8"))
        val con = u.openConnection() as HttpURLConnection
        try {
            con.instanceFollowRedirects = true
            con.connectTimeout = 20_000
            con.readTimeout = 30_000
            val code = con.responseCode
            if (code !in 200..299) throw Exception("Error del servidor ($code)")
            val txt = con.inputStream.bufferedReader().use { it.readText() }
            if (txt.trimStart().startsWith("<")) {
                throw Exception("Google pidió iniciar sesión: revisá que la implementación tenga acceso \"Cualquier usuario\" y la URL termine en /exec")
            }
            txt
        } finally {
            con.disconnect()
        }
    }
}
