"""Read DEX type, field and method declarations without third-party libraries."""
import struct


class Dex:
    def __init__(self, data):
        if not data.startswith(b'dex\n'):
            raise ValueError('Not a DEX file')
        self.data = data
        count, offset = self.pair(56)
        self.strings = []
        for i in range(count):
            point = self.u32(offset + 4 * i)
            _, point = self.uleb(point)
            end = data.index(0, point)
            self.strings.append(data[point:end].decode('utf-8', errors='replace'))
        count, offset = self.pair(64)
        self.types = [self.strings[self.u32(offset + 4 * i)] for i in range(count)]
        count, offset = self.pair(72)
        self.protos = []
        for i in range(count):
            _, result, args = struct.unpack_from('<III', data, offset + i * 12)
            params = [] if not args else [self.types[struct.unpack_from('<H', data, args + 4 + j * 2)[0]]
                                         for j in range(self.u32(args))]
            self.protos.append('(' + ''.join(params) + ')' + self.types[result])
        count, offset = self.pair(80)
        self.fields = []
        for i in range(count):
            owner, kind, name = struct.unpack_from('<HHI', data, offset + i * 8)
            self.fields.append((self.types[owner], self.strings[name], self.types[kind]))
        count, offset = self.pair(88)
        self.methods = []
        for i in range(count):
            owner, proto, name = struct.unpack_from('<HHI', data, offset + i * 8)
            self.methods.append((self.types[owner], self.strings[name], self.protos[proto]))
        count, offset = self.pair(96)
        self.classes = [struct.unpack_from('<8I', data, offset + i * 32) for i in range(count)]

    def u32(self, offset):
        return struct.unpack_from('<I', self.data, offset)[0]

    def pair(self, offset):
        return struct.unpack_from('<II', self.data, offset)

    def uleb(self, offset):
        value, shift = 0, 0
        while True:
            byte = self.data[offset]
            offset += 1
            value |= (byte & 127) << shift
            if not byte & 128:
                return value, offset
            shift += 7
            if shift > 35:
                raise ValueError('Invalid ULEB128')

    def class_names(self):
        return [self.types[entry[0]] for entry in self.classes]

    def definitions(self, name):
        for entry in self.classes:
            if self.types[entry[0]] != name:
                continue
            offset = entry[6]
            if not offset:
                return [], []
            counts = []
            for _ in range(4):
                value, offset = self.uleb(offset)
                counts.append(value)
            fields, methods = [], []
            for count in counts[:2]:
                index = 0
                for _ in range(count):
                    diff, offset = self.uleb(offset)
                    index += diff
                    flags, offset = self.uleb(offset)
                    fields.append((*self.fields[index], flags))
            for count in counts[2:]:
                index = 0
                for _ in range(count):
                    diff, offset = self.uleb(offset)
                    index += diff
                    flags, offset = self.uleb(offset)
                    code, offset = self.uleb(offset)
                    methods.append((*self.methods[index], flags, code))
            return fields, methods
        return None
