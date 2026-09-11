# Part of ITC / OVPS. See LICENSE file for full copyright and licensing details.
from odoo import fields, models


class PosConfig(models.Model):
    _inherit = "pos.config"

    sunmi_printer = fields.Boolean(
        string="Impresora Sunmi integrada",
        help=(
            "Imprime el ticket directo en la impresora térmica integrada del terminal "
            "Sunmi (V3, V2, T2...), sin caja IoT ni impresora Epson en red. Requiere tener "
            "instalado el APK del puente sunmi-print-bridge en el propio terminal: "
            "https://github.com/odoovps/sunmi-print-bridge"
        ),
    )
