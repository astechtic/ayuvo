import Compression
import Foundation
import Testing
@testable import calorietracker

struct ZipArchiveTests {
    private func deflate(_ data: Data) -> Data {
        let capacity = max(64, data.count * 2)
        let destination = UnsafeMutablePointer<UInt8>.allocate(capacity: capacity)
        defer { destination.deallocate() }
        let written = data.withUnsafeBytes { source -> Int in
            compression_encode_buffer(destination, capacity, source.bindMemory(to: UInt8.self).baseAddress!, data.count, nil, COMPRESSION_ZLIB)
        }
        return Data(bytes: destination, count: written)
    }

    /// Builds a minimal zip by hand so the reader is tested against foreign writers.
    private func handBuiltZip(entries: [(name: String, plain: Data, deflated: Bool, dataDescriptor: Bool)]) -> Data {
        var out = Data()
        var central = Data()
        for entry in entries {
            let payload = entry.deflated ? deflate(entry.plain) : entry.plain
            let crc = CRC32.checksum(entry.plain)
            let name = Data(entry.name.utf8)
            let offset = UInt32(out.count)
            let flags: UInt16 = entry.dataDescriptor ? 0x0008 : 0
            out.append(contentsOf: ZipArchiveWriter.u32(0x0403_4b50))
            out.append(contentsOf: ZipArchiveWriter.u16(20))
            out.append(contentsOf: ZipArchiveWriter.u16(flags))
            out.append(contentsOf: ZipArchiveWriter.u16(entry.deflated ? 8 : 0))
            out.append(contentsOf: ZipArchiveWriter.u16(0))
            out.append(contentsOf: ZipArchiveWriter.u16(0))
            // With a data descriptor the local header carries zeros; sizes live in the central directory.
            out.append(contentsOf: ZipArchiveWriter.u32(entry.dataDescriptor ? 0 : crc))
            out.append(contentsOf: ZipArchiveWriter.u32(entry.dataDescriptor ? 0 : UInt32(payload.count)))
            out.append(contentsOf: ZipArchiveWriter.u32(entry.dataDescriptor ? 0 : UInt32(entry.plain.count)))
            out.append(contentsOf: ZipArchiveWriter.u16(UInt16(name.count)))
            out.append(contentsOf: ZipArchiveWriter.u16(0))
            out.append(name)
            out.append(payload)
            if entry.dataDescriptor {
                out.append(contentsOf: ZipArchiveWriter.u32(0x0807_4b50))
                out.append(contentsOf: ZipArchiveWriter.u32(crc))
                out.append(contentsOf: ZipArchiveWriter.u32(UInt32(payload.count)))
                out.append(contentsOf: ZipArchiveWriter.u32(UInt32(entry.plain.count)))
            }
            central.append(contentsOf: ZipArchiveWriter.u32(0x0201_4b50))
            central.append(contentsOf: ZipArchiveWriter.u16(20))
            central.append(contentsOf: ZipArchiveWriter.u16(20))
            central.append(contentsOf: ZipArchiveWriter.u16(flags))
            central.append(contentsOf: ZipArchiveWriter.u16(entry.deflated ? 8 : 0))
            central.append(contentsOf: ZipArchiveWriter.u16(0))
            central.append(contentsOf: ZipArchiveWriter.u16(0))
            central.append(contentsOf: ZipArchiveWriter.u32(crc))
            central.append(contentsOf: ZipArchiveWriter.u32(UInt32(payload.count)))
            central.append(contentsOf: ZipArchiveWriter.u32(UInt32(entry.plain.count)))
            central.append(contentsOf: ZipArchiveWriter.u16(UInt16(name.count)))
            central.append(contentsOf: ZipArchiveWriter.u16(0))
            central.append(contentsOf: ZipArchiveWriter.u16(0))
            central.append(contentsOf: ZipArchiveWriter.u16(0))
            central.append(contentsOf: ZipArchiveWriter.u16(0))
            central.append(contentsOf: ZipArchiveWriter.u32(0))
            central.append(contentsOf: ZipArchiveWriter.u32(offset))
            central.append(name)
        }
        let centralOffset = UInt32(out.count)
        out.append(central)
        out.append(contentsOf: ZipArchiveWriter.u32(0x0605_4b50))
        out.append(contentsOf: ZipArchiveWriter.u16(0))
        out.append(contentsOf: ZipArchiveWriter.u16(0))
        out.append(contentsOf: ZipArchiveWriter.u16(UInt16(entries.count)))
        out.append(contentsOf: ZipArchiveWriter.u16(UInt16(entries.count)))
        out.append(contentsOf: ZipArchiveWriter.u32(UInt32(central.count)))
        out.append(contentsOf: ZipArchiveWriter.u32(centralOffset))
        out.append(contentsOf: ZipArchiveWriter.u16(0))
        return out
    }

    @Test func writerAndReaderRoundTripStoredEntries() throws {
        let directory = try HealthTestFixtures.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let url = directory.appendingPathComponent("a.zip")
        let big = Data((0..<600_000).map { UInt8($0 % 251) })
        let bigFile = directory.appendingPathComponent("big.bin")
        try big.write(to: bigFile)
        let writer = try ZipArchiveWriter(url: url)
        try writer.addStored(name: "manifest.json", data: Data("{}".utf8))
        try writer.addStored(name: "samples.ndjson", fileURL: bigFile)
        try writer.finish()
        let reader = try ZipArchiveReader(url: url)
        #expect(reader.entries.map(\.name) == ["manifest.json", "samples.ndjson"])
        #expect(try reader.data(for: reader.entry(named: "manifest.json")!) == Data("{}".utf8))
        let entry = reader.entry(named: "samples.ndjson")!
        #expect(entry.crc32 == CRC32.checksum(big))
        #expect(try reader.data(for: entry) == big)
    }

    @Test func readerInflatesDeflateEntriesWithAndWithoutDataDescriptors() throws {
        let text = Data(String(repeating: "{\"id\":\"x\",\"value\":1}\n", count: 5000).utf8)
        let zip = handBuiltZip(entries: [
            ("plain.txt", Data("hello".utf8), false, false),
            ("deflated.ndjson", text, true, false),
            ("descriptor.ndjson", text, true, true),
        ])
        let reader = try ZipArchiveReader(data: zip)
        #expect(reader.entries.count == 3)
        #expect(try reader.data(for: reader.entry(named: "plain.txt")!) == Data("hello".utf8))
        #expect(try reader.data(for: reader.entry(named: "deflated.ndjson")!) == text)
        #expect(try reader.data(for: reader.entry(named: "descriptor.ndjson")!) == text)
        var lines = 0
        try reader.forEachLine(of: reader.entry(named: "deflated.ndjson")!, maxLineBytes: 1024) { _ in lines += 1 }
        #expect(lines == 5000)
    }

    @Test func overlongLinesAreRejected() throws {
        let long = Data(String(repeating: "a", count: 2000).utf8)
        let zip = handBuiltZip(entries: [("long.ndjson", long, false, false)])
        let reader = try ZipArchiveReader(data: zip)
        #expect(throws: HealthImportError.lineTooLong) {
            try reader.forEachLine(of: reader.entry(named: "long.ndjson")!, maxLineBytes: 1000) { _ in }
        }
    }

    @Test func cloudBackupUnpackReadsDeflateArchivesFromAndroid() throws {
        let payload = Data("{\"format\":\"ayuvo-cloud-backup\"}".utf8)
        let zip = handBuiltZip(entries: [("backup.json", payload, true, true)])
        let files = try CloudBackupZip.unpack(zip)
        #expect(files["backup.json"] == payload)
        #expect(try CloudBackupZip.unpack(Data("nope".utf8)).isEmpty)
    }

    @Test func garbageIsNotAnArchive() {
        #expect(throws: ZipArchiveError.notAnArchive) { _ = try ZipArchiveReader(data: Data("garbage garbage garbage".utf8)) }
    }

    @Test func crc32MatchesTheKnownVector() {
        #expect(CRC32.checksum(Data("123456789".utf8)) == 0xCBF4_3926)
        var streaming: UInt32 = 0xffff_ffff
        Data("1234".utf8).withUnsafeBytes { CRC32.update(&streaming, with: $0) }
        Data("56789".utf8).withUnsafeBytes { CRC32.update(&streaming, with: $0) }
        #expect(CRC32.finalize(streaming) == 0xCBF4_3926)
    }
}
