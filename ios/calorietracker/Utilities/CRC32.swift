import Foundation

/// IEEE CRC-32 (the zip / PNG polynomial). Shared by the cloud-backup zip
/// writer and the health-data export archive so both produce byte-identical
/// checksums for the same entry bytes.
nonisolated enum CRC32 {
    static let table: [UInt32] = {
        (0..<256).map { i -> UInt32 in
            var c = UInt32(i)
            for _ in 0..<8 {
                c = (c & 1) == 1 ? (0xedb88320 ^ (c >> 1)) : (c >> 1)
            }
            return c
        }
    }()

    /// One-shot checksum of `data`.
    static func checksum(_ data: Data) -> UInt32 {
        var crc: UInt32 = 0xffffffff
        data.withUnsafeBytes { buffer in
            update(&crc, with: buffer)
        }
        return crc ^ 0xffffffff
    }

    /// Streaming form: start from `0xffffffff`, feed every chunk, then XOR with `0xffffffff`.
    static func update(_ crc: inout UInt32, with bytes: UnsafeRawBufferPointer) {
        var running = crc
        for byte in bytes {
            let idx = Int((running ^ UInt32(byte)) & 0xff)
            running = (running >> 8) ^ table[idx]
        }
        crc = running
    }

    /// Finalizes a streaming checksum started at `0xffffffff`.
    static func finalize(_ crc: UInt32) -> UInt32 {
        crc ^ 0xffffffff
    }
}
