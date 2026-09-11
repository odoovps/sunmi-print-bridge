# Part of ITC / OVPS. See LICENSE file for full copyright and licensing details.
{
    "name": "POS Sunmi Printer",
    "summary": "Imprime el ticket directo en la impresora térmica integrada de terminales Sunmi, sin caja IoT",
    "description": """
Sustituye la impresión del ticket por navegador (que en Android no puede hablar con la
impresora integrada de un terminal Sunmi) por una entrega directa vía el puente
sunmi-print-bridge (https://github.com/odoovps/sunmi-print-bridge): el mismo recibo que
Odoo ya renderiza para una impresora Epson se convierte a una imagen ESC/POS y se envía a
través de un esquema de URL (`sunmiprint://`) que la app puente instalada en el terminal
recibe y manda al SDK oficial de Sunmi.

Requiere tener instalado el APK de sunmi-print-bridge en el terminal Sunmi.
    """,
    "version": "19.0.1.0.0",
    "category": "Sales/Point of Sale",
    "author": "ITC",
    "website": "https://github.com/odoovps/sunmi-print-bridge",
    "license": "LGPL-3",
    "depends": ["point_of_sale"],
    "data": [
        "views/pos_config_views.xml",
    ],
    "assets": {
        "point_of_sale._assets_pos": [
            "pos_sunmi_printer/static/src/app/utils/printer/sunmi_printer.js",
            "pos_sunmi_printer/static/src/app/services/pos_store_patch.js",
        ],
    },
    "installable": True,
    "application": False,
}
