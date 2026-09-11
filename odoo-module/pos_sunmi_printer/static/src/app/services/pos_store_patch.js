import { patch } from "@web/core/utils/patch";
import { PosStore } from "@point_of_sale/app/services/pos_store";
import { PrinterService } from "@point_of_sale/app/services/printer_service";
import { SunmiPrinter } from "@pos_sunmi_printer/app/utils/printer/sunmi_printer";

patch(PosStore.prototype, {
    async afterProcessServerData() {
        await super.afterProcessServerData(...arguments);
        // Tiene prioridad sobre la impresora Epson por red: si el TPV corre
        // en un terminal Sunmi, ese es siempre el destino del ticket.
        if (this.config.sunmi_printer) {
            this.hardwareProxy.printer = new SunmiPrinter();
        }
    },
});

// SunmiPrinter no implementa el contrato de BasePrinter (processCanvas +
// printReceipt sobre un elemento HTML ya renderizado) porque arma el ticket
// como texto directo desde los datos del pedido, sin pasar por HTML/canvas
// en absoluto. Por eso el enganche va aquí, antes de que Odoo renderice
// nada: si el dispositivo activo es un SunmiPrinter, se le pasa el pedido
// (todavía disponible en `props.order` en este punto) y no se llama a
// super.print(), que es quien haría el render a HTML.
patch(PrinterService.prototype, {
    async print(component, props, options = {}) {
        if (this.device instanceof SunmiPrinter && props?.order) {
            this.state.isPrinting = true;
            try {
                return await this.device.printOrder(props.order, props.basic_receipt);
            } finally {
                this.state.isPrinting = false;
            }
        }
        return super.print(component, props, options);
    },
});
