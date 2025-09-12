from typing import List, Dict, Any
import xml.etree.ElementTree as ET


def invoices_to_xml_string(invoices: List[Dict[str, Any]]) -> str:
    root = ET.Element('invoices')
    for inv in invoices:
        e = ET.SubElement(root, 'invoice')
        ET.SubElement(e, 'filename').text = str(inv.get('filename') or '')
        ET.SubElement(e, 'invoice_number').text = str(inv.get('invoice_number') or '')
        ET.SubElement(e, 'invoice_date').text = str(inv.get('invoice_date') or '')
        ET.SubElement(e, 'total_amount').text = '' if inv.get('total_amount') is None else f"{inv.get('total_amount'):.2f}"
        ET.SubElement(e, 'currency').text = str(inv.get('currency') or '')

    # pretty print
    _indent(root)
    return ET.tostring(root, encoding='unicode')


def _indent(elem, level: int = 0):
    i = "\n" + level * "  "
    if len(elem):
        if not elem.text or not elem.text.strip():
            elem.text = i + "  "
        for child in elem:
            _indent(child, level + 1)
        if not child.tail or not child.tail.strip():
            child.tail = i
    if level and (not elem.tail or not elem.tail.strip()):
        elem.tail = i

