# sunmi-print-bridge

Puente entre una página web abierta en Chrome (sin ninguna app propia) y la impresora térmica
integrada de un terminal Sunmi (V3, V2, T2, etc.), para imprimir directo desde el TPV de Odoo
sin caja IoT y sin comprar el conector oficial de pago.

## Por qué existe

Chrome en Android no puede hablar con el servicio de impresión nativo de Sunmi
(`InnerPrinterManager`/AIDL): ese puente solo lo pueden usar apps nativas que envuelven la
página en su propio WebView e inyectan la interfaz (`WebView.addJavascriptInterface`), algo que
Chrome — al ser una app aparte — no expone. Es una limitación de Android, no de Sunmi.

La solución: una app Android mínima (`android-app/`) que no hace nada más que escuchar un
esquema de URL propio. Cualquier página web, en el navegador que sea, dispara:

```js
window.location.href = "sunmiprint://print?data=" + base64url(utf8(JSON.stringify(ops)))
```

Android abre la app puente con esos datos, la app decodifica la lista de órdenes y las ejecuta
con los métodos nativos del SDK oficial de Sunmi (`com.sunmi:printerlibrary`, Maven Central):
`printText`, `printColumnsString`, `printQRCode`, `printBitmap`... Sin diálogo de impresión de
por medio, sin conversión a PDF, sin recompilar nada cuando cambia el formato del ticket — eso
vive del lado del emisor (JS), no en la app.

**Por qué órdenes de alto nivel y no bytes ESC/POS crudos** (como se hizo en la primera
versión): mandar bytes obliga a acertar la codepage exacta que interpreta el firmware de cada
impresora. En el Sunmi V3 probado, los bytes 0x80-0xFF no son Latin-1/CP-1252 como cabría
esperar — la impresora los interpreta como el primer byte de una secuencia GBK, así que
cualquier tilde salía como un carácter CJK, y el símbolo € (fuera de rango de un único byte) no
tenía ningún mapeo posible. Los métodos nativos del SDK reciben el String de Java tal cual llega
por Binder (UTF-16, sin pérdida) y es el propio firmware el que dibuja los glifos — sin tabla de
codepage de por medio, tildes y € incluidos.

## Estructura

- `android-app/` — la app puente. `./gradlew assembleDebug` genera el APK.
- `test-page/` (copiada en `docs/` para GitHub Pages) — página de pruebas standalone con
  botones que arman listas de órdenes de ejemplo y disparan el intent. Útil para validar la
  instalación sin tocar Odoo todavía.
- `odoo-module/pos_sunmi_printer/` — módulo de Point of Sale (Odoo 19) que sustituye la
  impresión por navegador por esta vía cuando el TPV corre en un terminal Sunmi. Se activa desde
  Ajustes del TPV → "Impresora Sunmi".

## Instalar en un terminal Sunmi

1. Descarga el APK de la última release de este repo.
2. Ábrelo en el propio terminal (desde el navegador o transferido por USB) e instálalo —
   Android pedirá permiso para "instalar apps de fuentes desconocidas" la primera vez.
3. No hace falta abrir la app: se activa sola cuando una página web dispara el esquema
   `sunmiprint://`.

## Protocolo

`sunmiprint://print?data=<JSON de órdenes, UTF-8, en base64 URL-safe>`

El JSON es un array de objetos `{op, ...}`. La app ejecuta cada orden en secuencia contra el
SDK de Sunmi y no sabe nada de tickets, precios ni totales — esa lógica vive del lado que genera
el payload (`test-page/` para las pruebas, `odoo-module/` para el TPV real).

| `op`     | Campos                                        | Método SDK                         |
|----------|------------------------------------------------|-------------------------------------|
| `align`  | `value`: 0 izq, 1 centro, 2 der (persiste)     | `setAlignment`                      |
| `bold`   | `value`: true/false (persiste)                 | `setPrinterStyle(ENABLE_BOLD, …)`   |
| `text`   | `value`: una línea                             | `printText` (envuelve por palabra)  |
| `row`    | `cols`, `weights`, `aligns` (arrays paralelos) | `printColumnsString`                |
| `divider`| —                                               | `printText` con guiones al ancho del papel detectado (`getPrinterPaper`) |
| `feed`   | `value`: nº de líneas                          | `lineWrap`                          |
| `qr`     | `value`: el texto/URL a codificar              | `printQRCode`                       |
| `logo`   | `value`: imagen PNG en base64                  | `printBitmap`                       |
| `cut`    | —                                               | `cutPaper` (ignorado si no hay guillotina) |
| `drawer` | —                                               | `openDrawer`                        |

Ojo con `row`: `printColumnsString` reparte el ancho fijo del ticket entre columnas cortando
por caracteres, no por palabras — un nombre de producto largo puede partirse a mitad de palabra
si se mete directo en una columna. Por eso el módulo de Odoo imprime el nombre como su propio
`text` (que sí envuelve por palabra) y solo usa `row` para el precio, ya centrado en su columna.

## Estado

- [x] App puente compila y genera APK (`assembleDebug`).
- [x] Probado en un Sunmi V3 físico, en producción (Granja Martínez).
- [x] Módulo de Odoo POS (`odoo-module/pos_sunmi_printer`) que sustituye la impresión estándar
  por esta vía — texto, filas, logo y QR con los métodos nativos del SDK.
- [ ] Firma de release + distribución (hoy solo hay build debug).
