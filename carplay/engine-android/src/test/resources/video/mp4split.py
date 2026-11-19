# Extracts the avcC record and each length-prefixed sample from a one-track MP4 written by ffmpeg.
import struct, sys
data = open(sys.argv[1], 'rb').read()
out = sys.argv[2]
def boxes(buf, start, end):
    i = start
    while i + 8 <= end:
        size, kind = struct.unpack('>I4s', buf[i:i+8])
        yield kind.decode('latin1'), i, i + size
        i += size
def find(buf, start, end, path):
    for kind, s, e in boxes(buf, start, end):
        if kind == path[0]:
            if len(path) == 1: return s, e
            inner = s + 8
            if kind == 'stsd': inner = s + 16
            if kind == 'avc1': inner = s + 8 + 78
            r = find(buf, inner, e, path[1:])
            if r: return r
    return None
trak = ['moov','trak','mdia','minf','stbl']
s, e = find(data, 0, len(data), trak + ['stsd','avc1','avcC'])
avcc = data[s+8:e]
open(out + '.avcc', 'wb').write(avcc)
s, e = find(data, 0, len(data), trak + ['stsz'])
_, size0, count = struct.unpack('>III', data[s+8:s+20])
sizes = [size0]*count if size0 else list(struct.unpack('>%dI' % count, data[s+20:s+20+4*count]))
s, e = find(data, 0, len(data), trak + ['stco'])
n, = struct.unpack('>I', data[s+12:s+16]); offs = struct.unpack('>%dI' % n, data[s+16:s+16+4*n])
pos = offs[0]
for k, sz in enumerate(sizes):
    open('%s-frame%d.lp' % (out, k), 'wb').write(data[pos:pos+sz]); pos += sz
print('avcC', avcc.hex()); print('sizes', sizes, 'chunks', offs)
