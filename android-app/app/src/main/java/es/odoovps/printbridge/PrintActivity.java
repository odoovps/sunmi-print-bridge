package es.odoovps.printbridge;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.RemoteException;
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;

import com.sunmi.peripheral.printer.InnerPrinterCallback;
import com.sunmi.peripheral.printer.InnerPrinterException;
import com.sunmi.peripheral.printer.InnerPrinterManager;
import com.sunmi.peripheral.printer.SunmiPrinterService;
import com.sunmi.peripheral.printer.WoyouConsts;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/**
 * Puente entre una página web (Chrome, sin ninguna app propia) y la impresora
 * térmica integrada de un terminal Sunmi.
 *
 * Uso desde JS: window.location.href =
 *   "sunmiprint://print?data=" + base64url(utf8(JSON.stringify(ops)))
 *
 * `ops` es una lista de órdenes de alto nivel — no bytes ESC/POS crudos — a
 * propósito: mandar bytes obliga a acertar la codepage exacta que interpreta
 * el firmware (esta impresora no es Latin-1 como se asumió al principio, y
 * eso rompía tildes y el símbolo €). Los métodos nativos del SDK
 * (`printText`, `printColumnsString`, `printQRCode`, `printBitmap`) reciben
 * un String de Java tal cual llega por Binder (UTF-16, sin pérdida) y es el
 * propio firmware el que dibuja los glifos — sin tabla de codepage de por
 * medio.
 *
 * Órdenes soportadas (campo "op"):
 *   align  {value: 0|1|2}              - 0 izq, 1 centro, 2 der; persiste
 *   bold   {value: true|false}         - persiste hasta el siguiente bold
 *   text   {value: "..."}              - una línea (se añade el salto)
 *   row    {cols:[a,b], weights:[3,1], aligns:[0,2], bold}
 *   divider                            - línea de guiones al ancho del papel
 *   feed   {value: n}
 *   qr     {value: "url"}
 *   logo   {value: "<png en base64>"}
 *   cut
 *   drawer
 *
 * La actividad no muestra nada (tema transparente), imprime y se cierra sola.
 */
public class PrintActivity extends Activity {

    private static final String TAG = "SunmiPrintBridge";

    private SunmiPrinterService printerService;
    private JSONArray pendingOps;

    private final InnerPrinterCallback printerCallback = new InnerPrinterCallback() {
        @Override
        protected void onConnected(SunmiPrinterService service) {
            printerService = service;
            runOps();
        }

        @Override
        protected void onDisconnected() {
            printerService = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Uri uri = getIntent() != null ? getIntent().getData() : null;
        String data = uri != null ? uri.getQueryParameter("data") : null;

        if (data == null || data.isEmpty()) {
            toast("Sunmi Print Bridge: sin datos que imprimir");
            finish();
            return;
        }

        try {
            byte[] decoded = Base64.decode(data, Base64.URL_SAFE | Base64.NO_WRAP);
            String json = new String(decoded, StandardCharsets.UTF_8);
            pendingOps = new JSONArray(json);
        } catch (Exception e) {
            Log.e(TAG, "Datos de impresión no válidos", e);
            toast("Sunmi Print Bridge: datos no válidos");
            finish();
            return;
        }

        try {
            boolean bound = InnerPrinterManager.getInstance().bindService(this, printerCallback);
            if (!bound) {
                toast("Sunmi Print Bridge: no se pudo conectar con el servicio de impresión");
                finish();
            }
        } catch (InnerPrinterException e) {
            Log.e(TAG, "Error conectando con el servicio Sunmi", e);
            toast("Sunmi Print Bridge: " + e.getMessage());
            finish();
        }
    }

    private void runOps() {
        if (printerService == null || pendingOps == null) {
            finish();
            return;
        }
        boolean ok = true;
        try {
            printerService.printerInit(null);
            int paper;
            try {
                paper = printerService.getPrinterPaper();
            } catch (RemoteException e) {
                paper = 1; // 58mm por defecto si el terminal no informa
            }
            int lineWidth = paper == 1 ? 32 : 48;

            for (int i = 0; i < pendingOps.length(); i++) {
                JSONObject cmd = pendingOps.getJSONObject(i);
                runOp(cmd, lineWidth);
            }
        } catch (Exception e) {
            Log.e(TAG, "Fallo imprimiendo", e);
            ok = false;
        }
        final boolean success = ok;
        runOnUiThread(() -> {
            toast(success ? "Ticket enviado a la impresora" : "Fallo al imprimir");
            cleanupAndFinish();
        });
    }

    private void runOp(JSONObject cmd, int lineWidth) throws Exception {
        String op = cmd.getString("op");
        switch (op) {
            case "align":
                printerService.setAlignment(cmd.getInt("value"), null);
                break;
            case "bold":
                trySetStyle(WoyouConsts.ENABLE_BOLD, cmd.getBoolean("value"));
                break;
            case "text":
                printerService.printText(cmd.optString("value", "") + "\n", null);
                break;
            case "row": {
                boolean bold = cmd.optBoolean("bold", false);
                if (bold) {
                    trySetStyle(WoyouConsts.ENABLE_BOLD, true);
                }
                JSONArray colsJson = cmd.getJSONArray("cols");
                JSONArray weightsJson = cmd.optJSONArray("weights");
                JSONArray alignsJson = cmd.optJSONArray("aligns");
                String[] cols = new String[colsJson.length()];
                int[] weights = new int[colsJson.length()];
                int[] aligns = new int[colsJson.length()];
                for (int i = 0; i < colsJson.length(); i++) {
                    cols[i] = colsJson.getString(i);
                    weights[i] = weightsJson != null ? weightsJson.getInt(i) : 1;
                    aligns[i] = alignsJson != null ? alignsJson.getInt(i) : 0;
                }
                printerService.printColumnsString(cols, weights, aligns, null);
                if (bold) {
                    trySetStyle(WoyouConsts.ENABLE_BOLD, false);
                }
                break;
            }
            case "divider": {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < lineWidth; i++) {
                    sb.append('-');
                }
                sb.append('\n');
                printerService.printText(sb.toString(), null);
                break;
            }
            case "feed":
                printerService.lineWrap(cmd.optInt("value", 1), null);
                break;
            case "qr":
                printerService.printQRCode(cmd.getString("value"), 6, 2, null);
                printerService.lineWrap(1, null);
                break;
            case "logo": {
                byte[] png = Base64.decode(cmd.getString("value"), Base64.DEFAULT);
                Bitmap bitmap = BitmapFactory.decodeByteArray(png, 0, png.length);
                if (bitmap != null) {
                    printerService.printBitmap(bitmap, null);
                    printerService.lineWrap(1, null);
                }
                break;
            }
            case "cut":
                try {
                    printerService.cutPaper(null);
                } catch (RemoteException ignored) {
                    // Terminal sin guillotina: el papel ya se ha avanzado con "feed".
                }
                break;
            case "drawer":
                printerService.openDrawer(null);
                break;
            default:
                Log.w(TAG, "Orden desconocida: " + op);
        }
    }

    private void trySetStyle(int key, boolean enable) throws RemoteException {
        printerService.setPrinterStyle(key, enable ? WoyouConsts.ENABLE : WoyouConsts.DISABLE);
    }

    private void cleanupAndFinish() {
        try {
            InnerPrinterManager.getInstance().unBindService(this, printerCallback);
        } catch (InnerPrinterException ignored) {
        }
        finish();
    }

    private void toast(String message) {
        Toast.makeText(getApplicationContext(), message, Toast.LENGTH_SHORT).show();
    }
}
