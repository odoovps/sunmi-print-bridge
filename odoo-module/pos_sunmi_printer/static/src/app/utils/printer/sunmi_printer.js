import { formatCurrency } from "@web/core/currency";

// Ancho objetivo del logo en píxeles: suficiente para verse bien tanto en
// 58mm (~384 puntos) como en 80mm (~576 puntos) sin generar una imagen
// enorme. El propio SDK de Sunmi la re-difumina a 1 bit al imprimir.
const LOGO_MAX_WIDTH = 300;

/**
 * Imprime el ticket directo en la impresora térmica integrada de un terminal
 * Sunmi, sin caja IoT y sin impresora de red, vía el puente
 * https://github.com/odoovps/sunmi-print-bridge (esquema de URL
 * `sunmiprint://`, que una app mínima instalada aparte en el terminal recibe
 * y reenvía al SDK oficial de Sunmi).
 *
 * A diferencia de EpsonPrinter (que renderiza el recibo HTML a un canvas y
 * lo manda como imagen), esta clase arma el ticket como una lista de
 * ÓRDENES DE ALTO NIVEL (texto, filas de columnas, QR, logo...) que el
 * puente ejecuta con los métodos Unicode-nativos del SDK de Sunmi
 * (`printText`, `printColumnsString`, `printQRCode`, `printBitmap`).
 *
 * Se probaron antes dos vías y las dos fallaron por lo mismo — asumir cosas
 * sobre cómo interpreta bytes crudos esta impresora en concreto:
 *   1) Imagen del recibo HTML: el recibo de Odoo está pensado para pantalla,
 *      no para 58mm, y el difuminado a 1 bit borraba el texto gris clarito
 *      del subtotal/IVA.
 *   2) ESC/POS de texto plano armado a mano (`GS v 0`/bytes ASCII directos):
 *      asumía que la impresora interpreta 0x80-0xFF como Latin-1/CP-1252,
 *      pero esta impresora los interpreta como cabecera de una secuencia
 *      GBK — de ahí que las tildes salieran como caracteres CJK — y el
 *      símbolo € (fuera de un único byte) no tenía ningún mapeo.
 * La vía correcta es no tocar bytes/codepages en absoluto: se manda el
 * string en UTF-8 tal cual y es el propio SDK/firmware el que dibuja los
 * glifos (incluye tildes y € correctamente), igual que hace con QR y logo.
 *
 * No extiende BasePrinter: ese contrato (`processCanvas`/`printReceipt`) es
 * para impresoras que reciben una imagen, y aquí no se genera ninguna. El
 * enganche real está en pos_store_patch.js, que intercepta antes de que
 * Odoo renderice el HTML del recibo.
 */
export class SunmiPrinter {
    /**
     * @param {import("@point_of_sale/app/models/pos_order").PosOrder} order
     * @param {boolean} basic - recibo básico (sin desglose de impuestos/pago), igual que basic_receipt en OrderReceipt
     */
    async printOrder(order, basic = false) {
        const ops = [];
        const push = (op) => ops.push(op);
        const text = (value = "") => push({ op: "text", value });
        const row = (left, right, bold = false) =>
            push({ op: "row", cols: [left, right], weights: [3, 1], aligns: [0, 2], bold });
        const align = (value) => push({ op: "align", value });
        const currency = (amount) => formatCurrency(amount, order.currency.id);

        const company = order.company;
        const config = order.config;

        align(1); // centrado
        const logo = await this.getLogoBase64(config);
        if (logo) {
            push({ op: "logo", value: logo });
        }
        push({ op: "bold", value: true });
        text(company.name?.toUpperCase() || "");
        push({ op: "bold", value: false });
        if (company.street) {
            text(company.street);
        }
        const cityLine = [company.zip, company.city].filter(Boolean).join(" ");
        if (cityLine) {
            text(cityLine);
        }
        text("");
        text(`Ticket ${order.pos_reference || ""}`);
        if (order.date_order) {
            text(order.formatDateOrTime("date_order"));
        }
        const cashier = order?.getCashierName?.();
        if (cashier) {
            text(`Atendido por: ${cashier}`);
        }
        push({ op: "divider" });

        align(0); // izquierda
        for (const line of order.lines || []) {
            const qty = line.getQuantityStr?.() || {};
            const qtyStr = `${qty.unitPart || line.qty}${
                qty.decimalPart ? `${qty.decimalPoint}${qty.decimalPart}` : ""
            }`;
            const name = line.full_product_name || line.product_id?.name || "";
            const total = line.currencyDisplayPrice ?? currency(line.price_subtotal_incl);

            // El nombre va en su propia línea (printText envuelve por palabra
            // completa) y el precio en una fila aparte: meter los dos en una
            // misma "row" corta por caracteres a mitad de palabra en nombres
            // largos, porque printColumnsString reparte el ancho fijo entre
            // columnas sin mirar dónde acaban las palabras.
            text(`${qtyStr} ${name}`);
            row("", total);
            if (Number(qty.unitPart || line.qty) !== 1) {
                const uom = line.product_id?.uom_id?.name || "";
                text(`  ${line.currencyDisplayPriceUnit || ""} / ${uom}`);
            }
        }
        push({ op: "divider" });

        if (!basic) {
            const taxTotals = order.prices?.taxDetails;
            if (taxTotals?.has_tax_groups) {
                row("Subtotal", currency(order.priceExcl));
                for (const subtotal of taxTotals.subtotals || []) {
                    for (const taxGroup of subtotal.tax_groups || []) {
                        row(taxGroup.group_name, currency(taxGroup.tax_amount_currency));
                    }
                }
            }
            row("TOTAL", order.currencyDisplayPriceIncl, true);

            if (order.appliedRounding) {
                row("Redondeo", currency(order.appliedRounding));
                row("A pagar", currency(order.roundedPriceIncl));
            }

            text("");
            const paymentLines = (order.payment_ids || []).filter((p) => !p.is_change);
            for (const payment of paymentLines) {
                row(payment.payment_method_id?.name || "", currency(payment.getAmount()));
            }
            if (order.showChange) {
                row("Cambio", currency(order.change));
            }

            const totalDiscount = order.getTotalDiscount?.();
            if (totalDiscount) {
                row("Descuentos", currency(totalDiscount));
            }
        }

        if (config?.receipt_footer) {
            align(1);
            text("");
            for (const footerLine of config.receipt_footer.split("\n")) {
                text(footerLine);
            }
        }

        if (company.point_of_sale_use_ticket_qr_code && order.finalized) {
            const baseUrl = config._base_url;
            const url = `${baseUrl}/pos/ticket/validate?access_token=${order.access_token}`;
            align(1);
            text("");
            push({ op: "qr", value: url });
        }

        align(1);
        text("");
        text(config?.name || "");
        if (company.phone) {
            text(`Tel: ${company.phone}`);
        }
        if (company.email) {
            text(company.email);
        }
        text("Con tecnología de Odoo");
        push({ op: "feed", value: 3 });
        push({ op: "cut" });

        this.dispatchToBridge(ops);
        return { successful: true };
    }

    openCashbox() {
        this.dispatchToBridge([{ op: "drawer" }]);
    }

    /**
     * `receiptLogoUrl` ya es un data-URL cuando Odoo lo tiene cacheado para
     * impresión offline; si no, es una URL propia (`/web/image/...`) que se
     * puede pedir con fetch porque es del mismo origen. En ambos casos se
     * reescala en un canvas para no mandar una imagen más grande de lo que
     * cualquier ticket térmico necesita.
     */
    async getLogoBase64(config) {
        const url = config?.receiptLogoUrl;
        if (!url) {
            return null;
        }
        try {
            let dataUrl = url;
            if (!url.startsWith("data:")) {
                const response = await fetch(url);
                const blob = await response.blob();
                dataUrl = await new Promise((resolve, reject) => {
                    const reader = new FileReader();
                    reader.onload = () => resolve(reader.result);
                    reader.onerror = reject;
                    reader.readAsDataURL(blob);
                });
            }
            return await this.resizeDataUrl(dataUrl, LOGO_MAX_WIDTH);
        } catch {
            return null;
        }
    }

    async resizeDataUrl(dataUrl, maxWidth) {
        const img = await new Promise((resolve, reject) => {
            const image = new Image();
            image.onload = () => resolve(image);
            image.onerror = reject;
            image.src = dataUrl;
        });
        const scale = Math.min(1, maxWidth / img.width);
        const canvas = document.createElement("canvas");
        canvas.width = Math.round(img.width * scale);
        canvas.height = Math.round(img.height * scale);
        const ctx = canvas.getContext("2d");
        ctx.fillStyle = "#ffffff";
        ctx.fillRect(0, 0, canvas.width, canvas.height);
        ctx.drawImage(img, 0, 0, canvas.width, canvas.height);
        return canvas.toDataURL("image/png").split(",")[1];
    }

    dispatchToBridge(ops) {
        const json = JSON.stringify(ops);
        window.location.href = "sunmiprint://print?data=" + this.toBase64Url(json);
    }

    toBase64Url(str) {
        const bytes = new TextEncoder().encode(str);
        let binary = "";
        for (const byte of bytes) {
            binary += String.fromCharCode(byte);
        }
        return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_");
    }
}
