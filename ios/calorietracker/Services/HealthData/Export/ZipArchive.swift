import Compression
import Foundation

nonisolated struct ZipEntry: Sendable, Hashable {
    let name: String
    let method: UInt16
    let compressedSize: UInt64
    let uncompressedSize: UInt64
    let crc32: UInt32
    /// Offset of the first data byte (after the local header + name + extra).
    let dataOffset: UInt64

    var isStored: Bool { method == 0 }
    var isDeflated: Bool { method == 8 }
}

nonisolated enum ZipArchiveError: LocalizedError, Sendable, Equatable {
    case notAnArchive
    case truncated
    case unsupportedMethod(UInt16)
    case badEntry(String)
    case decompressionFailed(String)
    case tooLarge

    var errorDescription: String? {
        switch self {
        case .notAnArchive: return "This file is not a zip archive."
        case .truncated: return "The archive is incomplete."
        case .unsupportedMethod(let method): return "Unsupported zip compression method \(method)."
        case .badEntry(let name): return "The archive entry “\(name)” is damaged."
        case .decompressionFailed(let name): return "Could not decompress “\(name)”."
        case .tooLarge: return "The archive is too large."
        }
    }
}

/// Central-directory zip reader supporting stored (0) and deflate (8) entries, with or
/// without data descriptors (sizes always come from the central directory). Used for the
/// health export/import archives and, via `CloudBackupZip.unpack`, for cloud restores —
/// which lets Android's deflate archives restore on iOS.
nonisolated final class ZipArchiveReader {
    let data: Data
    let entries: [ZipEntry]

    static let chunkSize = 256 * 1024

    init(data: Data) throws {
        self.data = data
        self.entries = try Self.parseCentralDirectory(data)
    }

    convenience init(url: URL) throws {
        try self.init(data: Data(contentsOf: url, options: [.mappedIfSafe]))
    }

    func entry(named name: String) -> ZipEntry? {
        entries.first { $0.name == name }
    }

    /// Fully materialized bytes of `entry`.
    func data(for entry: ZipEntry) throws -> Data {
        var out = Data()
        out.reserveCapacity(Int(min(entry.uncompressedSize, UInt64(Int32.max))))
        try forEachChunk(of: entry) { out.append($0) }
        guard UInt64(out.count) == entry.uncompressedSize else { throw ZipArchiveError.badEntry(entry.name) }
        return out
    }

    /// Every entry decoded into memory (cloud-backup sized archives only).
    func allEntries() throws -> [String: Data] {
        var result: [String: Data] = [:]
        for entry in entries where !entry.name.hasSuffix("/") {
            result[entry.name] = try data(for: entry)
        }
        return result
    }

    /// Streams decoded bytes of `entry` in chunks (stored: slices; deflate: streaming inflate).
    func forEachChunk(of entry: ZipEntry, _ body: (Data) throws -> Void) throws {
        let start = Int(entry.dataOffset)
        let end = start + Int(entry.compressedSize)
        guard start >= 0, end <= data.count else { throw ZipArchiveError.truncated }
        switch entry.method {
        case 0:
            var cursor = start
            while cursor < end {
                let next = min(cursor + Self.chunkSize, end)
                try body(data.subdata(in: cursor..<next))
                cursor = next
            }
        case 8:
            try inflate(range: start..<end, name: entry.name, body)
        default:
            throw ZipArchiveError.unsupportedMethod(entry.method)
        }
    }

    /// Streams `\n`-separated lines (without the terminator). Lines longer than
    /// `maxLineBytes` throw so a hostile file cannot balloon memory.
    func forEachLine(of entry: ZipEntry, maxLineBytes: Int, _ body: (Data) throws -> Void) throws {
        var pending = Data()
        try forEachChunk(of: entry) { chunk in
            var searchStart = chunk.startIndex
            while let newline = chunk[searchStart...].firstIndex(of: 0x0A) {
                pending.append(chunk[searchStart..<newline])
                guard pending.count <= maxLineBytes else { throw HealthImportError.lineTooLong }
                try body(Self.stripCarriageReturn(pending))
                pending.removeAll(keepingCapacity: true)
                searchStart = newline + 1
            }
            pending.append(chunk[searchStart...])
            guard pending.count <= maxLineBytes else { throw HealthImportError.lineTooLong }
        }
        if !pending.isEmpty {
            try body(Self.stripCarriageReturn(pending))
        }
    }

    private static func stripCarriageReturn(_ line: Data) -> Data {
        if line.last == 0x0D { return line.dropLast() }
        return line
    }

    // MARK: - Inflate

    private func inflate(range: Range<Int>, name: String, _ body: (Data) throws -> Void) throws {
        guard !range.isEmpty else { return }
        let stream = UnsafeMutablePointer<compression_stream>.allocate(capacity: 1)
        defer { stream.deallocate() }
        guard compression_stream_init(stream, COMPRESSION_STREAM_DECODE, COMPRESSION_ZLIB) == COMPRESSION_STATUS_OK else {
            throw ZipArchiveError.decompressionFailed(name)
        }
        defer { compression_stream_destroy(stream) }

        let dstCapacity = Self.chunkSize
        let dstBuffer = UnsafeMutablePointer<UInt8>.allocate(capacity: dstCapacity)
        defer { dstBuffer.deallocate() }

        var cursor = range.lowerBound
        var finished = false
        while !finished, cursor < range.upperBound {
            let next = min(cursor + Self.chunkSize, range.upperBound)
            let isLast = next >= range.upperBound
            let chunk = data.subdata(in: cursor..<next)
            cursor = next
            try chunk.withUnsafeBytes { (srcBuffer: UnsafeRawBufferPointer) in
                guard let base = srcBuffer.bindMemory(to: UInt8.self).baseAddress else { return }
                stream.pointee.src_ptr = base
                stream.pointee.src_size = srcBuffer.count
                let flags = isLast ? Int32(COMPRESSION_STREAM_FINALIZE.rawValue) : 0
                while true {
                    stream.pointee.dst_ptr = dstBuffer
                    stream.pointee.dst_size = dstCapacity
                    let status = compression_stream_process(stream, flags)
                    let produced = dstCapacity - stream.pointee.dst_size
                    if produced > 0 {
                        try body(Data(bytes: dstBuffer, count: produced))
                    }
                    if status == COMPRESSION_STATUS_END {
                        finished = true
                        return
                    }
                    guard status == COMPRESSION_STATUS_OK else {
                        throw ZipArchiveError.decompressionFailed(name)
                    }
                    // Source drained and destination not full: fetch the next input chunk.
                    if stream.pointee.src_size == 0, produced < dstCapacity { return }
                }
            }
        }
        guard finished else { throw ZipArchiveError.decompressionFailed(name) }
    }

    // MARK: - Central directory

    private static func parseCentralDirectory(_ data: Data) throws -> [ZipEntry] {
        guard data.count >= 22 else { throw ZipArchiveError.notAnArchive }
        // End of central directory record: scan backwards over the maximum comment length.
        let minIndex = max(0, data.count - 22 - 65_535)
        var eocd: Int?
        var index = data.count - 22
        while index >= minIndex {
            if readU32(data, index) == 0x0605_4b50 {
                eocd = index
                break
            }
            index -= 1
        }
        guard let eocd else { throw ZipArchiveError.notAnArchive }
        let entryCount = Int(readU16(data, eocd + 10))
        let centralSize = Int(readU32(data, eocd + 12))
        let centralOffset = Int(readU32(data, eocd + 16))
        guard centralOffset + centralSize <= data.count, centralOffset >= 0 else { throw ZipArchiveError.truncated }

        var entries: [ZipEntry] = []
        var cursor = centralOffset
        for _ in 0..<entryCount {
            guard cursor + 46 <= data.count, readU32(data, cursor) == 0x0201_4b50 else { throw ZipArchiveError.truncated }
            let method = readU16(data, cursor + 10)
            let crc = readU32(data, cursor + 16)
            let compressedSize = UInt64(readU32(data, cursor + 20))
            let uncompressedSize = UInt64(readU32(data, cursor + 24))
            let nameLength = Int(readU16(data, cursor + 28))
            let extraLength = Int(readU16(data, cursor + 30))
            let commentLength = Int(readU16(data, cursor + 32))
            let localOffset = Int(readU32(data, cursor + 42))
            guard cursor + 46 + nameLength <= data.count else { throw ZipArchiveError.truncated }
            let name = String(decoding: data.subdata(in: (cursor + 46)..<(cursor + 46 + nameLength)), as: UTF8.self)
            if compressedSize == 0xFFFF_FFFF || uncompressedSize == 0xFFFF_FFFF || localOffset == 0xFFFF_FFFF {
                throw ZipArchiveError.tooLarge
            }
            // Local header: signature(4) version(2) flags(2) method(2) time(2) date(2) crc(4) csize(4) usize(4) nlen(2) xlen(2)
            guard localOffset + 30 <= data.count, readU32(data, localOffset) == 0x0403_4b50 else { throw ZipArchiveError.badEntry(name) }
            let localNameLength = Int(readU16(data, localOffset + 26))
            let localExtraLength = Int(readU16(data, localOffset + 28))
            let dataOffset = localOffset + 30 + localNameLength + localExtraLength
            guard UInt64(dataOffset) + compressedSize <= UInt64(data.count) else { throw ZipArchiveError.truncated }
            entries.append(ZipEntry(
                name: name,
                method: method,
                compressedSize: compressedSize,
                uncompressedSize: uncompressedSize,
                crc32: crc,
                dataOffset: UInt64(dataOffset)
            ))
            cursor += 46 + nameLength + extraLength + commentLength
        }
        return entries
    }

    static func readU16(_ data: Data, _ i: Int) -> UInt16 {
        UInt16(data[data.startIndex + i]) | UInt16(data[data.startIndex + i + 1]) << 8
    }

    static func readU32(_ data: Data, _ i: Int) -> UInt32 {
        UInt32(data[data.startIndex + i])
            | UInt32(data[data.startIndex + i + 1]) << 8
            | UInt32(data[data.startIndex + i + 2]) << 16
            | UInt32(data[data.startIndex + i + 3]) << 24
    }
}

/// Streaming stored-entry zip writer: entries are copied from temp files (size + CRC known
/// up front), so multi-hundred-MB exports never sit in memory.
nonisolated final class ZipArchiveWriter {
    private struct Record {
        let name: Data
        let method: UInt16
        let crc: UInt32
        let size: UInt32
        let compressedSize: UInt32
        let offset: UInt32
    }

    private let handle: FileHandle
    private var offset: UInt64 = 0
    private var records: [Record] = []
    private var finished = false

    init(url: URL) throws {
        FileManager.default.createFile(atPath: url.path, contents: nil)
        handle = try FileHandle(forWritingTo: url)
    }

    deinit {
        try? handle.close()
    }

    func addStored(name: String, data: Data) throws {
        try addStored(name: name, size: UInt64(data.count), crc: CRC32.checksum(data)) { write in
            try write(data)
        }
    }

    func addStored(name: String, fileURL: URL) throws {
        let attributes = try FileManager.default.attributesOfItem(atPath: fileURL.path)
        let size = (attributes[.size] as? NSNumber)?.uint64Value ?? 0
        var crc: UInt32 = 0xffff_ffff
        let reader = try FileHandle(forReadingFrom: fileURL)
        defer { try? reader.close() }
        while let chunk = try reader.read(upToCount: ZipArchiveReader.chunkSize), !chunk.isEmpty {
            chunk.withUnsafeBytes { CRC32.update(&crc, with: $0) }
        }
        try reader.seek(toOffset: 0)
        try addStored(name: name, size: size, crc: CRC32.finalize(crc)) { write in
            while let chunk = try reader.read(upToCount: ZipArchiveReader.chunkSize), !chunk.isEmpty {
                try write(chunk)
            }
        }
    }

    private func addStored(name: String, size: UInt64, crc: UInt32, body: ((Data) throws -> Void) throws -> Void) throws {
        try addEntry(name: name, method: 0, size: size, compressedSize: size, crc: crc, body: body)
    }

    /// Deflated entry (method 8) read from an already-compressed raw DEFLATE file — used by the
    /// `ayuvo-records` archive for its text entries (docs/health-records.md §35).
    func addDeflated(name: String, deflatedURL: URL, uncompressedSize: UInt64, crc: UInt32) throws {
        let attributes = try FileManager.default.attributesOfItem(atPath: deflatedURL.path)
        let compressedSize = (attributes[.size] as? NSNumber)?.uint64Value ?? 0
        let reader = try FileHandle(forReadingFrom: deflatedURL)
        defer { try? reader.close() }
        try addEntry(name: name, method: 8, size: uncompressedSize, compressedSize: compressedSize, crc: crc) { write in
            while let chunk = try reader.read(upToCount: ZipArchiveReader.chunkSize), !chunk.isEmpty {
                try write(chunk)
            }
        }
    }

    private func addEntry(name: String, method: UInt16, size: UInt64, compressedSize: UInt64, crc: UInt32, body: ((Data) throws -> Void) throws -> Void) throws {
        guard !finished else { return }
        guard size <= UInt64(UInt32.max) - 1, compressedSize <= UInt64(UInt32.max) - 1,
              offset <= UInt64(UInt32.max) - compressedSize - 1 else { throw ZipArchiveError.tooLarge }
        let nameData = Data(name.utf8)
        var local = Data()
        local.append(contentsOf: Self.u32(0x0403_4b50))
        local.append(contentsOf: Self.u16(20))
        local.append(contentsOf: Self.u16(0x0800)) // UTF-8 names
        local.append(contentsOf: Self.u16(method))
        local.append(contentsOf: Self.u16(0))
        local.append(contentsOf: Self.u16(0))
        local.append(contentsOf: Self.u32(crc))
        local.append(contentsOf: Self.u32(UInt32(compressedSize)))
        local.append(contentsOf: Self.u32(UInt32(size)))
        local.append(contentsOf: Self.u16(UInt16(nameData.count)))
        local.append(contentsOf: Self.u16(0))
        local.append(nameData)
        try handle.write(contentsOf: local)
        var written: UInt64 = 0
        try body { chunk in
            try handle.write(contentsOf: chunk)
            written += UInt64(chunk.count)
        }
        guard written == compressedSize else { throw ZipArchiveError.badEntry(name) }
        records.append(Record(name: nameData, method: method, crc: crc, size: UInt32(size), compressedSize: UInt32(compressedSize), offset: UInt32(offset)))
        offset += UInt64(local.count) + compressedSize
    }

    func finish() throws {
        guard !finished else { return }
        finished = true
        var central = Data()
        for record in records {
            central.append(contentsOf: Self.u32(0x0201_4b50))
            central.append(contentsOf: Self.u16(20))
            central.append(contentsOf: Self.u16(20))
            central.append(contentsOf: Self.u16(0x0800))
            central.append(contentsOf: Self.u16(record.method))
            central.append(contentsOf: Self.u16(0))
            central.append(contentsOf: Self.u16(0))
            central.append(contentsOf: Self.u32(record.crc))
            central.append(contentsOf: Self.u32(record.compressedSize))
            central.append(contentsOf: Self.u32(record.size))
            central.append(contentsOf: Self.u16(UInt16(record.name.count)))
            central.append(contentsOf: Self.u16(0))
            central.append(contentsOf: Self.u16(0))
            central.append(contentsOf: Self.u16(0))
            central.append(contentsOf: Self.u16(0))
            central.append(contentsOf: Self.u32(0))
            central.append(contentsOf: Self.u32(record.offset))
            central.append(record.name)
        }
        var end = Data()
        end.append(contentsOf: Self.u32(0x0605_4b50))
        end.append(contentsOf: Self.u16(0))
        end.append(contentsOf: Self.u16(0))
        end.append(contentsOf: Self.u16(UInt16(records.count)))
        end.append(contentsOf: Self.u16(UInt16(records.count)))
        end.append(contentsOf: Self.u32(UInt32(central.count)))
        end.append(contentsOf: Self.u32(UInt32(offset)))
        end.append(contentsOf: Self.u16(0))
        try handle.write(contentsOf: central)
        try handle.write(contentsOf: end)
        try handle.close()
    }

    static func u16(_ v: UInt16) -> [UInt8] {
        [UInt8(v & 0xff), UInt8((v >> 8) & 0xff)]
    }

    static func u32(_ v: UInt32) -> [UInt8] {
        [UInt8(v & 0xff), UInt8((v >> 8) & 0xff), UInt8((v >> 16) & 0xff), UInt8((v >> 24) & 0xff)]
    }
}

/// Streaming raw-DEFLATE compressor (`COMPRESSION_ZLIB` is the headerless deflate stream zip
/// entries use — it is the same encoding `ZipArchiveReader` inflates).
nonisolated enum DeflateFile {
    struct Result {
        var crc: UInt32
        var uncompressedSize: UInt64
    }

    /// Compresses `source` into `destination`, returning the CRC-32 and byte count of the input.
    static func compress(source: URL, destination: URL) throws -> Result {
        let reader = try FileHandle(forReadingFrom: source)
        defer { try? reader.close() }
        FileManager.default.createFile(atPath: destination.path, contents: nil)
        guard let writer = try? FileHandle(forWritingTo: destination) else {
            throw ZipArchiveError.badEntry(destination.lastPathComponent)
        }
        defer { try? writer.close() }

        let stream = UnsafeMutablePointer<compression_stream>.allocate(capacity: 1)
        defer { stream.deallocate() }
        guard compression_stream_init(stream, COMPRESSION_STREAM_ENCODE, COMPRESSION_ZLIB) == COMPRESSION_STATUS_OK else {
            throw ZipArchiveError.decompressionFailed(source.lastPathComponent)
        }
        defer { compression_stream_destroy(stream) }

        let dstCapacity = ZipArchiveReader.chunkSize
        let dstBuffer = UnsafeMutablePointer<UInt8>.allocate(capacity: dstCapacity)
        defer { dstBuffer.deallocate() }

        var crc: UInt32 = 0xffff_ffff
        var total: UInt64 = 0
        var done = false
        while !done {
            let chunk = (try reader.read(upToCount: dstCapacity)) ?? Data()
            let isLast = chunk.isEmpty
            if !chunk.isEmpty {
                total += UInt64(chunk.count)
                chunk.withUnsafeBytes { CRC32.update(&crc, with: $0) }
            }
            // `[UInt8]` (never an empty `Data`): the final FINALIZE pass must run even with no input.
            let bytes = [UInt8](chunk)
            try bytes.withUnsafeBufferPointer { (src: UnsafeBufferPointer<UInt8>) in
                stream.pointee.src_ptr = src.baseAddress ?? UnsafePointer(dstBuffer)
                stream.pointee.src_size = src.count
                let flags = isLast ? Int32(COMPRESSION_STREAM_FINALIZE.rawValue) : 0
                repeat {
                    stream.pointee.dst_ptr = dstBuffer
                    stream.pointee.dst_size = dstCapacity
                    let status = compression_stream_process(stream, flags)
                    let produced = dstCapacity - stream.pointee.dst_size
                    if produced > 0 {
                        try writer.write(contentsOf: Data(bytes: dstBuffer, count: produced))
                    }
                    if status == COMPRESSION_STATUS_END {
                        done = true
                        return
                    }
                    guard status == COMPRESSION_STATUS_OK else {
                        throw ZipArchiveError.decompressionFailed(source.lastPathComponent)
                    }
                    if stream.pointee.src_size == 0, produced < dstCapacity { return }
                } while true
            }
        }
        try writer.synchronize()
        return Result(crc: CRC32.finalize(crc), uncompressedSize: total)
    }
}
