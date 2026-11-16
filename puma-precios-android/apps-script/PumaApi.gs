/**
 * PUMA PRECIOS – API para la app de Android.
 *
 * Devuelve en JSON SOLO los precios (hojas DIARIO e HISTORIAL) de la planilla
 * "PLANILLA GRUPO MARGARIA PUMA PRECIOS/MARGENES". Nada más de la planilla sale.
 *
 * Está protegida con una clave secreta: sin la clave responde "Clave inválida".
 *
 * Instalación (una sola vez, en la PC):
 *  1. script.google.com → Nuevo proyecto → pegar este archivo en Código.gs.
 *  2. Elegir la función "obtenerClave" arriba y tocar ▶ Ejecutar. Aceptar permisos.
 *     En "Registro de ejecución" aparece la CLAVE: copiala.
 *  3. Implementar → Nueva implementación → tipo "Aplicación web".
 *     Ejecutar como: Yo.   Quién tiene acceso: Cualquier usuario.
 *     Copiar la URL (termina en /exec).
 *  4. En la app del celu pegar la URL y la clave.
 */

const SHEET_ID = '1x8CmQ_1cDEcXWNMqthQQGtkO_1wTKSQ_FHFqESW0Vks';
const PRODUCTOS = ['SUPER', 'PREMIUM', 'DIESEL COMUN', 'DIESEL PREMIUM'];
const FUENTES = ['YPF', 'SHELL', 'PUMA', 'SUGERIDO', 'COSTO PUMA'];
const DIAS_DIARIO = 31;     // días de DIARIO que se mandan (para el gráfico)
const DIAS_HISTORIAL = 35;  // días de HISTORIAL que se mandan (para variaciones y cambios)

function doGet(e) {
  const clave = PropertiesService.getScriptProperties().getProperty('CLAVE');
  const enviada = e && e.parameter ? e.parameter.key : '';
  if (!clave || enviada !== clave) return salida_({ ok: false, error: 'Clave inválida' });
  try {
    return salida_(datos_());
  } catch (err) {
    return salida_({ ok: false, error: String(err && err.message || err) });
  }
}

/** Ejecutalo una vez desde el editor: crea (si no existe) y muestra la clave. */
function obtenerClave() {
  const props = PropertiesService.getScriptProperties();
  let clave = props.getProperty('CLAVE');
  if (!clave) {
    clave = Utilities.getUuid().replace(/-/g, '');
    props.setProperty('CLAVE', clave);
  }
  Logger.log('CLAVE para la app: ' + clave);
  return clave;
}

/** Si alguna vez querés invalidar la clave vieja (ej. perdiste el celu). */
function cambiarClave() {
  PropertiesService.getScriptProperties().deleteProperty('CLAVE');
  return obtenerClave();
}

/** Para probar desde el editor: muestra un resumen de lo que mandaría. */
function probar() {
  const d = datos_();
  Logger.log('Días: ' + d.diario.length + ' | Cambios: ' + d.historial.length);
  Logger.log(JSON.stringify(d.diario[d.diario.length - 1]));
}

function datos_() {
  const ss = SpreadsheetApp.openById(SHEET_ID);
  const tz = ss.getSpreadsheetTimeZone();
  const ahora = new Date();

  // ---------- DIARIO ----------
  const shD = ss.getSheetByName('DIARIO');
  if (!shD) throw new Error('No encuentro la hoja DIARIO');
  const colsD = shD.getLastColumn();
  const headD = shD.getRange(1, 1, 1, colsD).getDisplayValues()[0].map(norm_);
  const idx = PRODUCTOS.map(p => FUENTES.map(f => headD.indexOf(p + ' - ' + f)));
  const iFecha = Math.max(0, headD.indexOf('FECHA'));
  const lastD = shD.getLastRow();
  const desde = Math.max(2, lastD - (DIAS_DIARIO + 20));
  const filasD = lastD >= desde ? shD.getRange(desde, 1, lastD - desde + 1, colsD).getValues() : [];
  const hoyStr = Utilities.formatDate(ahora, tz, 'yyyy-MM-dd');

  let diario = [];
  filasD.forEach(r => {
    const f = fecha_(r[iFecha]);
    if (!f) return;
    const fs = Utilities.formatDate(f, tz, 'yyyy-MM-dd');
    if (fs > hoyStr) return;
    const v = idx.map(fila => fila.map(c => (c < 0 ? null : num_(r[c]))));
    if (v.every(fila => fila.every(x => x === null))) return;
    diario.push({ f: fs, v: v });
  });
  diario = diario.slice(-DIAS_DIARIO);

  // ---------- HISTORIAL ----------
  const shH = ss.getSheetByName('HISTORIAL');
  const historial = [];
  if (shH) {
    const colsH = shH.getLastColumn();
    const headH = shH.getRange(1, 1, 1, colsH).getDisplayValues()[0].map(norm_);
    const c = {
      fecha: headH.indexOf('FECHA'), fuente: headH.indexOf('FUENTE'), prod: headH.indexOf('PRODUCTO'),
      ant: headH.indexOf('ANTERIOR'), nue: headH.indexOf('NUEVO')
    };
    if (Object.keys(c).some(k => c[k] < 0)) throw new Error('Faltan columnas en HISTORIAL');
    const lastH = shH.getLastRow();
    const desdeH = Math.max(2, lastH - 600);
    const filasH = lastH >= desdeH ? shH.getRange(desdeH, 1, lastH - desdeH + 1, colsH).getValues() : [];
    const limite = ahora.getTime() - DIAS_HISTORIAL * 86400000;
    filasH.forEach(r => {
      const f = fecha_(r[c.fecha]);
      const nue = num_(r[c.nue]);
      if (!f || nue === null || f.getTime() < limite) return;
      const fu = norm_(r[c.fuente]), p = norm_(r[c.prod]);
      if (FUENTES.indexOf(fu) < 0 || PRODUCTOS.indexOf(p) < 0) return;
      historial.push({ t: f.getTime(), fu: fu, p: p, a: num_(r[c.ant]), n: nue });
    });
  }

  return {
    ok: true,
    actualizado: ahora.getTime(),
    productos: PRODUCTOS,
    fuentes: FUENTES,
    diario: diario,
    historial: historial
  };
}

function salida_(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj)).setMimeType(ContentService.MimeType.JSON);
}

function norm_(s) {
  return String(s == null ? '' : s).trim().toUpperCase();
}

/** Acepta números o textos tipo "2.192,00" / "$ 1.925,89". */
function num_(x) {
  if (x === '' || x === null || x === undefined) return null;
  if (typeof x === 'number') return isFinite(x) ? x : null;
  let s = String(x).replace(/[$\s]/g, '');
  if (!s) return null;
  if (s.indexOf(',') >= 0) s = s.replace(/\./g, '').replace(',', '.');
  const n = parseFloat(s);
  return isNaN(n) ? null : n;
}

/** Acepta Date o textos "dd/MM/yyyy" o "dd/MM/yyyy HH:mm". */
function fecha_(x) {
  if (x instanceof Date) return isNaN(x.getTime()) ? null : x;
  const m = String(x || '').match(/(\d{1,2})\/(\d{1,2})\/(\d{4})(?:\s+(\d{1,2}):(\d{2}))?/);
  if (!m) return null;
  return new Date(+m[3], +m[2] - 1, +m[1], +(m[4] || 0), +(m[5] || 0));
}
