#!/usr/bin/env python3
"""Teste do analisador: monta um btsnoop sintético (H4) com escritas ATT e confere a extração."""
import os
import struct
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import btsnoop_gatt as b  # noqa: E402


def record(t_us, received, payload):
    flags = 1 if received else 0
    ts = t_us + b.BTSNOOP_EPOCH_DELTA
    return struct.pack(">IIIIq", len(payload), len(payload), flags, 0, ts) + payload


def acl(handle, att, pb=0x02):
    l2 = struct.pack("<HH", len(att), 4) + att
    return b"\x02" + struct.pack("<HH", handle | (pb << 12), len(l2)) + l2


def make_log(path, packets):
    with open(path, "wb") as f:
        f.write(b.BTSNOOP_MAGIC + struct.pack(">II", 1, 1002))
        for t, rx, p in packets:
            f.write(record(t, rx, p))


class T(unittest.TestCase):
    def test_extracts_writes_and_labels_known_and_new_commands(self):
        decl = bytes([0x09, 7]) + struct.pack("<HBHH", 0x0010, 0x1E, 0x0011, 0xFFE1)   # característica FFE1, handle de valor 0x11
        w_color = bytes([0x52]) + struct.pack("<H", 0x0011) + bytes.fromhex("A0690400FF00FF")
        w_new = bytes([0x52]) + struct.pack("<H", 0x0011) + bytes.fromhex("A0 7A 02 03 1E".replace(" ", ""))
        notif = bytes([0x1B]) + struct.pack("<H", 0x0011) + bytes.fromhex("5343010505AABBCCDD")
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, "x.log")
            make_log(p, [(0, True, acl(0x40, decl)), (1_000_000, False, acl(0x40, w_color)),
                         (4_000_000, False, acl(0x40, w_new)), (4_100_000, True, acl(0x40, notif))])
            events = list(b.parse(p))
            self.assertEqual(len(events), 4)
            self.assertEqual(b.build_handle_map(events), {0x11: "FFE1"})
            out = subprocess.run([sys.executable, os.path.join(HERE, "btsnoop_gatt.py"), p, "--marks"],
                                 capture_output=True, text=True)
            self.assertEqual(out.returncode, 0, out.stderr)
            self.assertIn("A0 69 04 00 FF 00 FF", out.stdout)
            self.assertIn("cor sólida", out.stdout)
            self.assertIn("A0 7A", out.stdout)
            self.assertIn("NOVO", out.stdout)
            self.assertIn("(FFE1)", out.stdout)

    def test_reassembles_fragmented_acl(self):
        att = bytes([0x12]) + struct.pack("<H", 0x0011) + bytes(range(40))
        l2 = struct.pack("<HH", len(att), 4) + att
        first, rest = l2[:20], l2[20:]
        pk1 = b"\x02" + struct.pack("<HH", 0x40 | (0x02 << 12), len(first)) + first
        pk2 = b"\x02" + struct.pack("<HH", 0x40 | (0x01 << 12), len(rest)) + rest
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, "x.log")
            make_log(p, [(0, False, pk1), (10, False, pk2)])
            ev = list(b.parse(p))
            self.assertEqual(len(ev), 1)
            self.assertEqual(ev[0]["att"], att)

    def test_rejects_non_btsnoop(self):
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, "x.log")
            open(p, "wb").write(b"hello world, not a log file")
            with self.assertRaises(SystemExit):
                list(b.parse(p))


if __name__ == "__main__":
    unittest.main()
