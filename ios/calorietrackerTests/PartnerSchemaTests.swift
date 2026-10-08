import Foundation
import Testing
@testable import calorietracker

/// Schema parity: the embedded SQL is `shared/partner/schema.sql` byte for byte, and the opened database has
/// exactly the contract's tables, indexes and columns.
struct PartnerSchemaTests {
    static var sharedSchemaURL: URL { HealthTestFixtures.repoRootURL.appendingPathComponent("shared/partner/schema.sql") }

    @Test func embeddedSchemaEqualsSharedFile() throws {
        let shared = try String(contentsOf: Self.sharedSchemaURL, encoding: .utf8)
        #expect(PartnerSchema.sql == shared)
        #expect(MedicationsSchema.parseStatementsStrict(shared) != nil, "text after the last ;")
        #expect(PartnerSchema.statements.count == PartnerSchema.tableNames.count + PartnerSchema.indexNames.count)
        for s in PartnerSchema.statements {
            #expect(!s.uppercased().contains("ON CONFLICT"), "SQLite 3.18 has no upsert")
        }
        let migrations = HealthTestFixtures.repoRootURL.appendingPathComponent("shared/partner/migrations")
        let files = (try? FileManager.default.contentsOfDirectory(atPath: migrations.path))?.filter { $0.hasSuffix(".sql") } ?? []
        #expect(files.isEmpty, "a shared migration exists but PartnerSchema has none")
    }

    @Test func openedDatabaseHasContractTablesIndexesAndColumns() async throws {
        let dir = try HealthTestFixtures.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: dir) }
        let db = try await PartnerDatabase.open(url: dir.appendingPathComponent("Partner/partner.sqlite"))
        #expect(try await db.tableNames() == PartnerSchema.tableNames.sorted())
        #expect(try await db.indexNames() == PartnerSchema.indexNames.sorted())
        #expect(try await db.userVersion() == 1)
        #expect(try await db.meta("schema_version") == "1")
        #expect(try await db.journalMode()?.lowercased() == "wal")
        #expect(try await db.pragmaInt("foreign_keys") == 1)
        #expect(try await db.pragmaInt("synchronous") == 1)
        #expect(try await db.pragmaInt("busy_timeout") == 5000)
        #expect(HealthDatabaseLocation.isExcludedFromBackup(dir.appendingPathComponent("Partner")))

        // Columns equal a scratch database created straight from the shared file.
        let reference = try await PartnerDatabase.inMemory()
        let records = try await db.columns(of: "partner_records").map(\.name)
        #expect(records == ["owner_id", "type", "record_id", "category", "rev", "day", "ts_ms", "updated_ms", "data_json"])
        for table in PartnerSchema.tableNames {
            #expect(try await db.columns(of: table) == (try await reference.columns(of: table)), "\(table) columns differ")
        }
        let ledger = try await db.columns(of: "outbound_ledger").map(\.name)
        #expect(ledger == ["type", "record_id", "category", "day", "content_hash", "rev", "deleted"])
        await db.close()

        // Reopening is a no-op migration.
        let again = try await PartnerDatabase.open(url: dir.appendingPathComponent("Partner/partner.sqlite"))
        #expect(try await again.userVersion() == 1)
        await again.close()
    }

    @Test func partnerDatabaseLivesOutsideEveryBackup() throws {
        #expect(PartnerLocation.databaseURL().path.hasSuffix("Application Support/Ayuvo/Partner/partner.sqlite"))
        // Backups and exports archive explicit stores; none of them may reference the partner database.
        let services = HealthTestFixtures.repoRootURL.appendingPathComponent("ios/calorietracker/Services")
        for file in ["CloudBackupArchive.swift", "AppBackupService.swift", "AllDataExport.swift", "PortableDataExport.swift"] {
            let text = try String(contentsOf: services.appendingPathComponent(file), encoding: .utf8)
            #expect(!text.contains("PartnerLocation") && !text.contains("PartnerDatabase") && !text.contains("partner.sqlite"),
                    "\(file) must not include partner data")
        }
    }
}
