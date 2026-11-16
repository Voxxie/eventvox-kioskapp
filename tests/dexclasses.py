"""Lijst de klassen die in een classes.dex gedefinieerd zijn (geen dexdump nodig): python3 dexclasses.py classes.dex"""
import struct, sys

d = open(sys.argv[1], 'rb').read()
u = lambda off: struct.unpack_from('<I', d, off)[0]
str_off, type_off, cls_n, cls_off = u(0x3C), u(0x44), u(0x60), u(0x64)

def string(i):
    p = u(str_off + 4 * i)
    while d[p] & 0x80: p += 1   # lengte (uleb128) overslaan
    p += 1
    return d[p:d.index(b'\0', p)].decode('utf-8', 'replace')

for c in range(cls_n):
    print(string(u(type_off + 4 * u(cls_off + 32 * c))))
