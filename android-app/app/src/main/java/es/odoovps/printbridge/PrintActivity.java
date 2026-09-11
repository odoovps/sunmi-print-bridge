package es.odoovps.printbridge;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.RemoteException;
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;

import com.sunmi.peripheral.printer.InnerPrinterCallback;
import com.sunmi.peripheral.printer.InnerPrinterException;
import com.sunmi.peripheral.printer.InnerPrinterManager;
import com.sunmi.peripheral.printer.InnerResultCallback;
import com.sunmi.peripheral.printer.SunmiPrinterService;

/**
 * Puente entre una página web (Chrome, sin ninguna app propia) y la impresora
 * térmica integrada de un terminal Sunmi.
 *
 * Uso desde JS: window.location.href =
 *   "sunmiprint://print?data=" + base64url(rawEscPosBytes)
 *
 * La actividad no muestra nada (tema transparente), imprime y se cierra sola.
 * No hay lógica de formato de ticket aquí a propósito: los bytes ESC/POS ya
 * vienen completos desde el lado Odoo — esta app es un "tubo tonto" hacia el
 * SDK de Sunmi, así que iterar el formato del ticket no requiere recompilar
 * ni reinstalar nada en el terminal.
 */
public class PrintActivity extends Activity {

    private static final String TAG = "SunmiPrintBridge";

    private SunmiPrinterService printerService;
    private byte[] pendingData;

    private final InnerPrinterCallback printerCallback = new InnerPrinterCallback() {
        @Override
        protected void onConnected(SunmiPrinterService service) {
            printerService = service;
            doPrint();
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
            pendingData = Base64.decode(data, Base64.URL_SAFE | Base64.NO_WRAP);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Base64 inválido", e);
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

    private void doPrint() {
        if (printerService == null || pendingData == null) {
            finish();
            return;
        }
        try {
            printerService.sendRAWData(pendingData, new InnerResultCallback() {
                @Override
                public void onRunResult(boolean isSuccess) {
                    runOnUiThread(() -> {
                        toast(isSuccess ? "Ticket enviado a la impresora" : "Fallo al imprimir");
                        cleanupAndFinish();
                    });
                }

                @Override
                public void onReturnString(String result) {
                }

                @Override
                public void onRaiseException(int code, String msg) {
                    runOnUiThread(() -> {
                        toast("Sunmi Print Bridge: error " + code + " - " + msg);
                        cleanupAndFinish();
                    });
                }

                @Override
                public void onPrintResult(int code, String msg) {
                }
            });
        } catch (RemoteException e) {
            Log.e(TAG, "Fallo enviando datos a la impresora", e);
            toast("Sunmi Print Bridge: " + e.getMessage());
            cleanupAndFinish();
        }
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
