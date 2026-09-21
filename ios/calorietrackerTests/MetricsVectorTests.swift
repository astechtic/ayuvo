import Foundation
import SwiftUI
import Testing
import UIKit
@testable import calorietracker

/// Runs every case of `shared/metrics/test-vectors/*.json` through `MetricsReference`
/// (docs/ui-structure.md §7) and requires exact equality with the Python reference.
struct MetricsVectorTests {
    static let vectorFiles = [
        "bucket_bounds", "anchor_step", "bucket_series", "headline", "sparkline",
        "fasting_days", "workouts", "rings", "pins", "catalog_resolve",
    ]

    static var vectorsDirectory: URL {
        HealthTestFixtures.repoRootURL.appendingPathComponent("shared/metrics/test-vectors")
    }

    @Test func everySharedVectorFileHasARunner() throws {
        let names = try FileManager.default.contentsOfDirectory(atPath: Self.vectorsDirectory.path)
            .filter { $0.hasSuffix(".json") }
            .map { String($0.dropLast(5)) }
        #expect(!names.isEmpty)
        for name in names.sorted() {
            #expect(Self.vectorFiles.contains(name), "no Swift runner for test-vectors/\(name).json")
        }
    }

    @Test(arguments: vectorFiles)
    func vectorFileMatchesReference(_ name: String) throws {
        let url = Self.vectorsDirectory.appendingPathComponent("\(name).json")
        let root = try #require(RJ.parse(try String(contentsOf: url, encoding: .utf8)), "unreadable \(name).json")
        #expect(root["format"].string == "ayuvo-metrics-vectors")
        let function = try #require(root["function"].string)
        let cases = root["cases"].array ?? []
        #expect(!cases.isEmpty)
        var passed = 0
        var failures: [String] = []
        for c in cases {
            let actual = MetricsReference.runCase(function: function, input: c["input"])
            if let diff = RecordsVectorTests.firstDifference(actual, c["expected"]) {
                failures.append("\(c["name"].string ?? "?"): \(diff)")
            } else {
                passed += 1
            }
        }
        print("METRICS-VECTORS \(name).json \(passed)/\(cases.count)")
        let report = "\(name).json \(passed)/\(cases.count) passed\n" + failures.joined(separator: "\n")
        #expect(failures.isEmpty, Comment(rawValue: report))
    }
}

@MainActor
struct MetricCatalogTests {
    private var sharedURL: URL { HealthTestFixtures.repoRootURL.appendingPathComponent("shared/metrics/metric_catalog.json") }

    @Test func bundledCopyIsByteIdenticalToSharedFile() throws {
        let shared = try Data(contentsOf: sharedURL)
        let copy = try Data(contentsOf: HealthTestFixtures.repoRootURL.appendingPathComponent("ios/calorietracker/Metrics/Resources/metric_catalog.json"))
        #expect(shared == copy, "Metrics/Resources/metric_catalog.json differs from shared/metrics/metric_catalog.json")
    }

    @Test func bundledCatalogDecodesWithEveryDomainAndMetric() throws {
        let catalog = try #require(MetricCatalogData.load())
        #expect(catalog.domains.map(\.id).sorted() == BrowseCategory.allCases.map(\.rawValue).sorted())
        #expect(Set(catalog.metrics.map(\.key)) == Set(AppMetric.allCases.map(\.key)))
        #expect(catalog.favourites.max == 12)
        #expect(catalog.prefs.dailyStepGoal.default == ActivitySettings.defaultDailyStepGoal)
        for metric in catalog.metrics {
            #expect(!metric.about.isEmpty, "\(metric.key) has no about text")
        }
    }

    @Test func everySymbolResolves() throws {
        let catalog = try #require(MetricCatalogData.load())
        let symbols = catalog.domains.map(\.icon.ios) + catalog.metrics.map(\.icon.ios)
            + catalog.health.overrides.compactMap(\.icon?.ios)
        for symbol in symbols {
            #expect(UIImage(systemName: symbol) != nil, "SF Symbol \(symbol) does not exist on this OS")
        }
    }

    @Test func domainHexMatchesPalette() throws {
        let catalog = try #require(MetricCatalogData.load())
        let traitsLight = UITraitCollection(userInterfaceStyle: .light)
        let traitsDark = UITraitCollection(userInterfaceStyle: .dark)
        for domain in catalog.domains {
            let colour = UIColor(AyuvoPalette.domain(domain.id))
            #expect(hex(colour.resolvedColor(with: traitsLight)) == AyuvoPalette.hexValue(domain.colourHex), "\(domain.id) light")
            #expect(hex(colour.resolvedColor(with: traitsDark)) == AyuvoPalette.hexValue(domain.colourHexDark), "\(domain.id) dark")
        }
    }

    private func hex(_ colour: UIColor) -> UInt {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        colour.getRed(&r, green: &g, blue: &b, alpha: &a)
        return (UInt((r * 255).rounded()) << 16) | (UInt((g * 255).rounded()) << 8) | UInt((b * 255).rounded())
    }

    @Test func keysRoundTrip() {
        for metric in AppMetric.allCases {
            #expect(MetricKey(pinID: metric.key) == .app(metric))
            #expect(MetricKey.app(metric).id == metric.key)
        }
        #expect(MetricKey(pinID: "steps") == .health("steps"))
        #expect(MetricKey(pinID: "app:unknown") == nil)
        #expect(MetricKey(pinID: "  ") == nil)
        #expect(MetricKey.health("heart_rate").id == "heart_rate")
    }

    @Test func descriptorsFollowTheCatalog() {
        let calories = MetricCatalog.descriptor(for: .app(.calories))
        #expect(calories.domainID == "nutrition")
        #expect(calories.aggregation == .sum)
        #expect(calories.chartKind == .bar)
        #expect(calories.ranges == HealthDetailRange.allCases)
        let weight = MetricCatalog.descriptor(for: .app(.weight))
        #expect(weight.chartKind == .line)
        #expect(!weight.ranges.contains(.day))
        let steps = MetricCatalog.descriptor(for: .health("steps"))
        #expect(steps.domainID == "activity")
        #expect(steps.goalSource == "prefs.dailyStepGoal")
        // Health metrics carry their own glyph instead of borrowing the domain's (Calories flame).
        #expect(steps.systemImage == "figure.walk")
        #expect(MetricCatalog.descriptor(for: .health("sleep")).systemImage == "bed.double.fill")
        #expect(MetricCatalog.descriptor(for: .health("basal_metabolic_rate")).systemImage
            == MetricCatalogData.shared.domain(MetricCatalog.descriptor(for: .health("basal_metabolic_rate")).domainID)?.icon.ios)
        #expect(MetricCatalog.descriptor(for: .health("weight")).browseHidden)
        #expect(BrowseCategory.ordered.first == .nutrition)
        #expect(BrowseCategory.heart.healthCategory == .heart)
        #expect(BrowseCategory(healthCategory: .mentalWellbeing) == .mindfulness)
    }
}
