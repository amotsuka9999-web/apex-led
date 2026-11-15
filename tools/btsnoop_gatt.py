#!/usr/bin/env python3
"""
Extrai as escritas/notificações GATT de um btsnoop_hci.log (Android) para descobrir os comandos que o BanlanX
envia ao SP621E. Só usa a biblioteca padrão do Python 3.8+.

Uso:
    python3 tools/btsnoop_gatt.py btsnoop_hci.log                 # tabela legível
    python3 tools/btsnoop_gatt.py btsnoop_hci.log --csv saida.csv # também grava CSV
    python3 tools/btsnoop_gatt.py btsnoop_hci.log --only-writes   # só o que o app mandou
    python3 tools/btsnoop_gatt.py btsnoop_hci.log --marks         # separa por pausas > 2 s (uma ação do usuário por bloco)

O script marca cada pacote `A0 <op> ...` com o nome do comando QUANDO ele coincide com o que já está implementado em
led-sp621e (Sp621ePackets) e destaca com "NOVO" tudo que não está na tabela — são os candidatos a tipo de CI,
número de pixels, etc.

Formato btsnoop: https://datatracker.ietf.org/doc/html/rfc1761 (Snoop v2 / BTSnoop, datalink 1001 = HCI sem
indicador, 1002 = HCI UART/H4, o usado pelo Android).
"""
import argparse
import csv
import struct
import sys
from collections import defaultdict

BTSNOOP_MAGIC = b"btsnoop\x00"
# Microssegundos entre 0000-01-01 e 1970-01-01 (época do btsnoop).
BTSNOOP_EPOCH_DELTA = 0x00DCDDB30F2F8000

ATT_CID = 0x0004
ATT_OPS = {
    0x12: "Write Request", 0x52: "Write Command", 0x13: "Write Response",
    0x1B: "Notification", 0x1D: "Indication", 0x0A: "Read Request", 0x0B: "Read Response",
    0x16: "Prepare Write", 0x18: "Execute Write",
}

# Comandos já implementados (Sp621ePackets). Fonte: monty68/uniled banlanx2.py.
KNOWN = {
    0x62: "power(on/off)", 0x63: "efeito", 0x64: "ordem de cor", 0x66: "brilho", 0x67: "velocidade do efeito",
    0x68: "comprimento do efeito", 0x69: "cor sólida (R G B nível)", 0x6A: "modo de luz", 0x70: "consulta de estado",
    0x6B: "sensibilidade (música)", 0x6C: "entrada de áudio", 0x76: "branco",
}


def read_records(path):
    with open(path, "rb") as f:
        head = f.read(16)
        if len(head) < 16 or head[:8] != BTSNOOP_MAGIC:
            raise SystemExit("Não parece um arquivo btsnoop (cabeçalho 'btsnoop' ausente).")
        _version, datalink = struct.unpack(">II", head[8:16])
        if datalink not in (1001, 1002):
            raise SystemExit(f"Tipo de enlace {datalink} não suportado (esperado 1001 ou 1002).")
        while True:
            rec = f.read(24)
            if len(rec) < 24:
                return
            orig_len, incl_len, flags, _drops, ts = struct.unpack(">IIIIq", rec)
            data = f.read(incl_len)
            if len(data) < incl_len:
                return
            yield datalink, flags, (ts - BTSNOOP_EPOCH_DELTA) / 1e6, data


class Reassembler:
    """Remonta fragmentos ACL → pacotes L2CAP, por conexão e direção."""

    def __init__(self):
        self.buf = {}

    def feed(self, key, pb, payload):
        """Devolve (cid, dados) quando um pacote L2CAP completo está disponível."""
        if pb in (0x00, 0x02):  # início
            if len(payload) < 4:
                return None
            l2len, cid = struct.unpack("<HH", payload[:4])
            self.buf[key] = [l2len, cid, bytearray(payload[4:])]
        elif pb == 0x01:  # continuação
            if key not in self.buf:
                return None
            self.buf[key][2].extend(payload)
        else:
            return None
        l2len, cid, data = self.buf[key]
        if len(data) >= l2len:
            del self.buf[key]
            return cid, bytes(data[:l2len])
        return None


def parse(path):
    """Gera dicionários: t, direction ('app→dispositivo' | 'dispositivo→app'), op, handle, value."""
    reasm = Reassembler()
    for datalink, flags, t, data in read_records(path):
        received = bool(flags & 1)
        if flags & 2:  # comando/evento HCI
            continue
        if datalink == 1002:
            if not data or data[0] != 0x02:  # só ACL
                continue
            data = data[1:]
        if len(data) < 4:
            continue
        h, _dlen = struct.unpack("<HH", data[:4])
        handle, pb = h & 0x0FFF, (h >> 12) & 0x3
        got = reasm.feed((handle, received), pb, data[4:])
        if not got:
            continue
        cid, att = got
        if cid != ATT_CID or not att:
            continue
        yield {"t": t, "received": received, "conn": handle, "op": att[0], "att": att}


def build_handle_map(events):
    """handle de valor → UUID, a partir de Read By Type Response (0x09) com declarações de característica (0x2803)."""
    names = {}
    for e in events:
        att = e["att"]
        if e["op"] == 0x09 and len(att) >= 2:
            size = att[1]
            for off in range(2, len(att) - size + 1, size):
                item = att[off:off + size]
                if size == 7:      # handle(2) props(1) value_handle(2) uuid16(2)
                    vh = struct.unpack("<H", item[3:5])[0]
                    names[vh] = "%04X" % struct.unpack("<H", item[5:7])[0]
                elif size == 21:   # uuid128
                    vh = struct.unpack("<H", item[3:5])[0]
                    names[vh] = item[5:21][::-1].hex()
    return names


def describe(value):
    if len(value) >= 3 and value[0] == 0xA0:
        op = value[1]
        name = KNOWN.get(op)
        return f"A0 {op:02X} ({name})" if name else f"A0 {op:02X}  ← NOVO"
    return ""


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("log")
    ap.add_argument("--csv")
    ap.add_argument("--only-writes", action="store_true", help="só escritas do app (Write Request/Command)")
    ap.add_argument("--marks", action="store_true", help="linha em branco quando há pausa > 2 s")
    args = ap.parse_args()

    events = list(parse(args.log))
    handles = build_handle_map(events)
    rows = []
    for e in events:
        op, att = e["op"], e["att"]
        if op not in (0x12, 0x52, 0x1B, 0x1D) or len(att) < 3:
            continue
        if args.only_writes and op not in (0x12, 0x52):
            continue
        vh = struct.unpack("<H", att[1:3])[0]
        value = att[3:]
        rows.append({
            "t": e["t"], "dir": "dispositivo→app" if e["received"] else "app→dispositivo",
            "op": ATT_OPS.get(op, hex(op)), "handle": vh, "uuid": handles.get(vh, "?"),
            "hex": value.hex(" ").upper(), "note": describe(value),
        })
    if not rows:
        print("Nenhuma escrita/notificação GATT encontrada. Confira se o registro HCI estava ligado durante o uso do BanlanX.")
        return 1

    t0, last = rows[0]["t"], None
    new_ops = defaultdict(int)
    for r in rows:
        if args.marks and last is not None and r["t"] - last > 2.0:
            print()
        last = r["t"]
        print(f"{r['t'] - t0:8.3f}s  {r['dir']:<16} {r['op']:<14} h={r['handle']:#06x} ({r['uuid']:>4})  {r['hex']}  {r['note']}")
        if "NOVO" in r["note"]:
            new_ops[r["hex"][:5]] += 1
    if new_ops:
        print("\nComandos A0 que o app ainda NÃO implementa (prefixo → ocorrências):")
        for k, v in sorted(new_ops.items()):
            print(f"  {k}  × {v}")
    if args.csv:
        with open(args.csv, "w", newline="", encoding="utf-8") as f:
            w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
            w.writeheader()
            w.writerows(rows)
        print(f"\nCSV gravado em {args.csv}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
