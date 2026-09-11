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
window.location.href = "sunmiprint://print?data=" + base64url(rawEscPosBytes)
```

Android abre la app puente con esos datos, la app los decodifica y los manda tal cual a la
impresora vía `sendRAWData()` del SDK oficial de Sunmi (`com.sunmi:printerlibrary`, Maven
Central). Sin diálogo de impresión de por medio, sin conversión a PDF, sin recompilar nada
cuando cambia el formato del ticket — eso vive del lado del emisor (JS), no en la app.

## Estructura

- `android-app/` — la app puente. `./gradlew assembleDebug` genera el APK.
- `test-page/` — página de pruebas standalone (botones que arman payloads ESC/POS de ejemplo
  y disparan el intent). Útil para validar la instalación sin tocar Odoo todavía.
- `odoo-module/` — (pendiente) módulo de Point of Sale que sustituye la impresión por navegador
  por esta vía cuando el TPV corre en un terminal Sunmi.

## Instalar en un terminal Sunmi

1. Descarga el APK de la última release de este repo.
2. Ábrelo en el propio terminal (desde el navegador o transferido por USB) e instálalo —
   Android pedirá permiso para "instalar apps de fuentes desconocidas" la primera vez.
3. No hace falta abrir la app: se activa sola cuando una página web dispara el esquema
   `sunmiprint://`.

## Protocolo

`sunmiprint://print?data=<bytes ESC/POS en base64 URL-safe>`

La app es un "tubo tonto": no interpreta líneas, precios ni totales — solo decodifica y envía.
Toda la lógica de formato del ticket (qué imprimir, cómo alinear, cuándo cortar) vive en el lado
que genera el payload (hoy: `test-page/`, mañana: el módulo de Odoo).

Comandos ESC/POS usados en las pruebas:
- `1B 40` — inicializar impresora
- `1B 61 01` / `1B 61 00` — centrar / alinear a la izquierda
- `1B 45 01` / `1B 45 00` — negrita on/off
- `1D 56 01` — corte parcial de papel (el V3 de mano normalmente NO tiene cuchilla — es
  esperable que esta prueba falle en esa unidad)
- `1B 70 00 19 FA` — pulso de apertura de cajón portamonedas

## Estado

- [x] App puente compila y genera APK (`assembleDebug`).
- [ ] Probado en un Sunmi V3 físico.
- [ ] Módulo de Odoo POS que sustituye la impresión estándar por esta vía.
- [ ] Firma de release + distribución (hoy solo hay build debug).
