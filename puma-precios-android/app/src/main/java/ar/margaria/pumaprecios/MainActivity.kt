package ar.margaria.pumaprecios

import android.graphics.Paint
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ---------------- Colores ----------------
private val Fondo = Color(0xFF0B0E11)
private val Tarjeta = Color(0xFF12151A)
private val Borde = Color(0xFF1F242B)
private val Texto = Color(0xFFF4F6F8)
private val Gris = Color(0xFF8A93A0)
private val Naranja = Color(0xFFFB923C)
private val Celeste = Color(0xFF38BDF8)
private val Rojo = Color(0xFFF87171)
private val Verde = Color(0xFF4ADE80)
private val ColorFuente = listOf(
    Color(0xFF60A5FA), // YPF
    Color(0xFFFACC15), // Shell
    Color(0xFFEF4444), // Puma
    Color(0xFFA78BFA), // Sugerido
    Color(0xFF94A3B8), // Costo
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repo = Repo(applicationContext)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Naranja, background = Fondo, surface = Tarjeta,
                    onPrimary = Fondo, onBackground = Texto, onSurface = Texto,
                )
            ) {
                Box(Modifier.fillMaxSize().background(Fondo).windowInsetsPadding(WindowInsets.safeDrawing)) {
                    App(repo)
                }
            }
        }
    }
}

enum class Modo(val etiqueta: String) { H48("48 h"), D7("7 días $"), D7P("7 días %") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(repo: Repo) {
    val scope = rememberCoroutineScope()
    var configurando by remember { mutableStateOf(repo.url.isBlank() || repo.clave.isBlank()) }
    var datos by remember { mutableStateOf(repo.cache?.let { runCatching { parsear(it) }.getOrNull() }) }
    var cargando by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var ahora by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var modo by remember { mutableStateOf(Modo.H48) }
    var prodGrafico by remember { mutableIntStateOf(0) }

    fun refrescar() {
        if (configurando || cargando || repo.url.isBlank()) return
        scope.launch {
            cargando = true
            error = null
            try {
                val txt = repo.descargar()
                val d = parsear(txt)
                repo.cache = txt
                datos = d
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            } finally {
                ahora = System.currentTimeMillis()
                cargando = false
            }
        }
    }

    // Se actualiza al abrir/volver a la app y cada 5 minutos mientras está abierta.
    LifecycleResumeEffect(configurando) {
        val job = scope.launch {
            while (true) {
                refrescar()
                delay(5 * 60_000L)
            }
        }
        onPauseOrDispose { job.cancel() }
    }

    if (configurando) {
        BackHandler(enabled = repo.url.isNotBlank() && repo.clave.isNotBlank()) { configurando = false }
        Configuracion(
            repo = repo,
            puedeCancelar = repo.url.isNotBlank() && repo.clave.isNotBlank(),
            onListo = { configurando = false; datos = null; error = null },
            onCancelar = { configurando = false },
        )
        return
    }

    PullToRefreshBox(isRefreshing = cargando, onRefresh = { refrescar() }, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Encabezado(datos, cargando, onRefrescar = { refrescar() }, onAjustes = { configurando = true })
            }
            error?.let { msg -> item { Aviso(msg) } }
            val d = datos
            if (d == null) {
                item {
                    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                        if (cargando) CircularProgressIndicator(color = Naranja)
                        else Text("Sin datos todavía. Deslizá hacia abajo para actualizar.", color = Gris, textAlign = TextAlign.Center)
                    }
                }
            } else {
                item { PreciosHoy(d, ahora, modo, onModo = { modo = it }) }
                item { PumaVs(d) }
                item { Grafico(d, prodGrafico, onProd = { prodGrafico = it }) }
                item { Cambios(d, ahora) }
            }
        }
    }
}

// ---------------- Piezas ----------------

@Composable
fun Tarjeta(titulo: String? = null, contenido: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Tarjeta)
            .border(1.dp, Borde, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        if (titulo != null) {
            Text(titulo.uppercase(), color = Gris, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            Spacer(Modifier.height(10.dp))
        }
        contenido()
    }
}

@Composable
fun Encabezado(datos: Datos?, cargando: Boolean, onRefrescar: () -> Unit, onAjustes: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(Rojo))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text("Puma Precios", color = Texto, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                if (datos != null) "Actualizado ${fechaHora(datos.actualizado)}" else "—",
                color = Gris, fontSize = 12.sp,
            )
        }
        if (cargando) {
            CircularProgressIndicator(Modifier.size(20.dp), color = Naranja, strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        TextButton(onClick = onRefrescar) { Text("↻", color = Texto, fontSize = 22.sp) }
        TextButton(onClick = onAjustes) { Text("⚙", color = Gris, fontSize = 20.sp) }
    }
}

@Composable
fun Aviso(msg: String) {
    Text(
        "No se pudo actualizar: $msg",
        color = Rojo, fontSize = 13.sp,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(Rojo.copy(alpha = 0.12f)).padding(12.dp),
    )
}

@Composable
fun Chip(texto: String, activo: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(10.dp))
            .background(if (activo) Naranja else Fondo)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(texto, color = if (activo) Fondo else Gris, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun PreciosHoy(d: Datos, ahora: Long, modo: Modo, onModo: (Modo) -> Unit) {
    Tarjeta("Precios hoy") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Modo.entries.forEach { m -> Chip(m.etiqueta, m == modo, Modifier.weight(1f)) { onModo(m) } }
        }
        Spacer(Modifier.height(12.dp))
        // Encabezado de columnas
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1.25f))
            FUENTES_CORTO.forEachIndexed { f, n ->
                Text(
                    n, Modifier.weight(1f), color = if (f == F_PUMA) Texto else Gris,
                    fontSize = 11.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        PRODUCTOS.indices.forEach { p ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    PRODUCTOS_CORTO[p], Modifier.weight(1.25f), color = Texto,
                    fontSize = 12.sp, fontWeight = FontWeight.Bold,
                )
                FUENTES.indices.forEach { f ->
                    CeldaPrecio(d, p, f, ahora, modo, Modifier.weight(1f))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Leyenda(Naranja, "cambió en 48 h")
            Spacer(Modifier.width(14.dp))
            Leyenda(Celeste, "cambió en 7 días")
        }
    }
}

@Composable
fun CeldaPrecio(d: Datos, p: Int, f: Int, ahora: Long, modo: Modo, modifier: Modifier) {
    val v48 = d.variacion(p, f, 48 * HORA_MS, ahora)
    val v7 = d.variacion(p, f, 7 * DIA_MS, ahora)
    val marca = when {
        v48 != null -> Naranja
        v7 != null -> Celeste
        else -> null
    }
    val v = if (modo == Modo.H48) v48 else v7
    val txtVar = when {
        v == null -> "—"
        modo == Modo.D7P -> pct(v.pct)
        else -> conSigno(v.abs)
    }
    val colorVar = when {
        v == null || kotlin.math.abs(v.abs) < 0.004 -> Gris
        v.abs > 0 -> Rojo
        else -> Verde
    }
    Column(
        modifier.padding(horizontal = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(marca?.copy(alpha = 0.16f) ?: Color.Transparent)
            .border(1.dp, marca?.copy(alpha = 0.55f) ?: Color.Transparent, RoundedCornerShape(8.dp))
            .padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            precio(d.actual(p, f)), color = marca ?: Texto, fontSize = 13.sp,
            fontWeight = if (f == F_PUMA) FontWeight.Bold else FontWeight.Medium, maxLines = 1,
        )
        Text(txtVar, color = colorVar, fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
fun Leyenda(color: Color, texto: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Spacer(Modifier.width(5.dp))
        Text(texto, color = Gris, fontSize = 11.sp)
    }
}

@Composable
fun PumaVs(d: Datos) {
    Tarjeta("Puma vs competencia") {
        PRODUCTOS.indices.forEach { p ->
            val puma = d.actual(p, F_PUMA)
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(PRODUCTOS_CORTO[p], color = Texto, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("Puma $${precio(puma)}", color = ColorFuente[F_PUMA], fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(4.dp))
                listOf(F_YPF, F_SHELL, F_SUGERIDO).forEach { f ->
                    val otro = d.actual(p, f)
                    if (puma != null && otro != null && otro != 0.0) {
                        val dif = puma - otro
                        FilaVs("vs ${NOMBRE_VS[f]}", "$${precio(otro)}", conSigno(dif), pct(dif / otro * 100), colorDif(dif))
                    } else {
                        FilaVs("vs ${NOMBRE_VS[f]}", "—", "", "", Gris)
                    }
                }
                val costo = d.actual(p, F_COSTO)
                if (puma != null && costo != null && costo != 0.0) {
                    val mb = puma - costo
                    FilaVs("Margen bruto", "costo $${precio(costo, true)}", "$${precio(mb)}", pct(mb / costo * 100).removePrefix("+") + " s/c", if (mb >= 0) Verde else Rojo)
                }
            }
            if (p < PRODUCTOS.size - 1) Box(Modifier.fillMaxWidth().height(1.dp).background(Borde))
        }
    }
}

private val NOMBRE_VS = listOf("YPF", "Shell", "Puma", "Sugerido", "Costo")

private fun colorDif(dif: Double): Color = when {
    dif > 0.004 -> Rojo      // Puma más caro
    dif < -0.004 -> Verde    // Puma más barato
    else -> Gris
}

@Composable
fun FilaVs(etiqueta: String, ref: String, dif: String, porc: String, color: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(etiqueta, color = Gris, fontSize = 13.sp, modifier = Modifier.weight(1.2f))
        Text(ref, color = Gris, fontSize = 12.sp, modifier = Modifier.weight(1.2f), textAlign = TextAlign.End)
        Text(dif, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.9f), textAlign = TextAlign.End)
        Text(porc, color = color, fontSize = 12.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
    }
}

@Composable
fun Grafico(d: Datos, prod: Int, onProd: (Int) -> Unit) {
    Tarjeta("Últimos 30 días") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PRODUCTOS_CORTO.forEachIndexed { i, n -> Chip(n, i == prod, Modifier.weight(1f)) { onProd(i) } }
        }
        Spacer(Modifier.height(12.dp))
        val dias = d.dias.takeLast(30)
        val valores = dias.flatMap { it.v[prod] }.filterNotNull()
        if (dias.size < 2 || valores.isEmpty()) {
            Text("No hay datos suficientes para el gráfico.", color = Gris, fontSize = 13.sp)
            return@Tarjeta
        }
        val lo0 = valores.min()
        val hi0 = if (valores.max() - lo0 < 1) lo0 + 20 else valores.max()
        val pad = (hi0 - lo0) * 0.08
        val min = lo0 - pad
        val max = hi0 + pad
        val gris = Gris.toArgb()
        Canvas(Modifier.fillMaxWidth().height(210.dp)) {
            val izq = 44.dp.toPx()
            val abajo = size.height - 18.dp.toPx()
            val ancho = size.width - izq - 4.dp.toPx()
            val paint = Paint().apply { color = gris; textSize = 10.sp.toPx(); isAntiAlias = true }
            fun y(v: Double) = (abajo - (v - min) / (max - min) * (abajo - 6.dp.toPx())).toFloat()
            fun x(i: Int) = izq + ancho * i / (dias.size - 1)
            // grilla
            for (k in 0..3) {
                val v = min + (max - min) * k / 3
                val yy = y(v)
                drawLine(Borde, Offset(izq, yy), Offset(size.width, yy), strokeWidth = 1f)
                paint.textAlign = Paint.Align.RIGHT
                drawContext.canvas.nativeCanvas.drawText(precio(v), izq - 6.dp.toPx(), yy + 4.dp.toPx(), paint)
            }
            paint.textAlign = Paint.Align.CENTER
            listOf(0, dias.size / 2, dias.size - 1).distinct().forEach { i ->
                drawContext.canvas.nativeCanvas.drawText(diaMes(dias[i].fecha), x(i), size.height - 2.dp.toPx(), paint)
            }
            // series (Costo punteado; Puma más gruesa)
            FUENTES.indices.forEach { f ->
                val path = Path()
                var abierto = false
                dias.forEachIndexed { i, dia ->
                    val v = dia.v[prod][f]
                    if (v == null) { abierto = false } else {
                        if (!abierto) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v))
                        abierto = true
                    }
                }
                drawPath(
                    path, ColorFuente[f],
                    style = Stroke(
                        width = (if (f == F_PUMA) 3.dp else 2.dp).toPx(),
                        cap = StrokeCap.Round,
                        pathEffect = if (f == F_COSTO) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null,
                    ),
                )
                // punto final
                dias.indices.reversed().firstOrNull { dias[it].v[prod][f] != null }?.let { i ->
                    drawCircle(ColorFuente[f], 3.dp.toPx(), Offset(x(i), y(dias[i].v[prod][f]!!)))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            FUENTES_CORTO.forEachIndexed { f, n ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(ColorFuente[f]))
                    Spacer(Modifier.width(4.dp))
                    Text(n, color = Gris, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
fun Cambios(d: Datos, ahora: Long) {
    val lista = d.cambios.filter { it.ant != null && it.t >= ahora - 7 * DIA_MS }.sortedByDescending { it.t }
    Tarjeta("Cambios últimos 7 días") {
        if (lista.isEmpty()) {
            Text("Sin cambios en los últimos 7 días.", color = Gris, fontSize = 13.sp)
        }
        lista.forEachIndexed { i, c ->
            val dif = c.nue - c.ant!!
            val reciente = c.t >= ahora - 48 * HORA_MS
            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (reciente) Naranja else Celeste))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(FUENTES[c.f].lowercase().replaceFirstChar { it.uppercase() }, color = ColorFuente[c.f], fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text(" · ${PRODUCTOS_CORTO[c.p]}", color = Texto, fontSize = 13.sp)
                    }
                    Text(fechaHora(c.t), color = Gris, fontSize = 11.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("${precio(c.ant, true)} → ${precio(c.nue, true)}", color = Texto, fontSize = 13.sp)
                    val porc = if (c.ant != 0.0) " (${pct(dif / c.ant * 100)})" else ""
                    Text(conSigno(dif, true) + porc, color = if (dif > 0) Rojo else if (dif < 0) Verde else Gris, fontSize = 11.sp)
                }
            }
            if (i < lista.size - 1) Box(Modifier.fillMaxWidth().height(1.dp).background(Borde))
        }
    }
}

// ---------------- Configuración ----------------

@Composable
fun Configuracion(repo: Repo, puedeCancelar: Boolean, onListo: () -> Unit, onCancelar: () -> Unit) {
    var url by remember { mutableStateOf(repo.url) }
    var clave by remember { mutableStateOf(repo.clave) }
    val colores = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Naranja, unfocusedBorderColor = Borde,
        focusedLabelColor = Naranja, unfocusedLabelColor = Gris,
        focusedTextColor = Texto, unfocusedTextColor = Texto, cursorColor = Naranja,
    )
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Puma Precios", color = Texto, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(
            "Pegá la URL de la aplicación web de Apps Script (termina en /exec) y la clave que te mostró la función obtenerClave.",
            color = Gris, fontSize = 14.sp, lineHeight = 20.sp,
        )
        OutlinedTextField(
            value = url, onValueChange = { url = it.trim() },
            label = { Text("URL (…/exec)") }, singleLine = true, colors = colores,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = clave, onValueChange = { clave = it.trim() },
            label = { Text("Clave") }, singleLine = true, colors = colores,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                // Si pegaron la URL con ?key=... ya incluida, la separamos.
                var u = url
                var k = clave
                val m = Regex("[?&]key=([^&]+)").find(u)
                if (m != null) {
                    if (k.isBlank()) k = m.groupValues[1]
                    u = u.replace(Regex("[?&]key=[^&]+"), "")
                }
                repo.url = u
                repo.clave = k
                repo.cache = null
                onListo()
            },
            enabled = url.startsWith("https://") && (clave.isNotBlank() || url.contains("key=")),
            colors = ButtonDefaults.buttonColors(containerColor = Naranja, contentColor = Fondo),
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) { Text("Guardar y conectar", fontWeight = FontWeight.Bold) }
        if (puedeCancelar) {
            TextButton(onClick = onCancelar, modifier = Modifier.fillMaxWidth()) { Text("Cancelar", color = Gris) }
        }
    }
}
