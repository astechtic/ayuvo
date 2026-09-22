import Foundation
import SwiftUI
import Testing
@testable import calorietracker

/// Launch-route priority and the Browse/Metric route values (docs/ui-structure.md §2).
@MainActor
struct BrowseRouteTests {
    @Test func medicationBeatsQuickActionAndMethod() {
        let route = LaunchRouteResolver.resolve(medication: .some("m1"), action: .camera, method: .manual)
        #expect(route == .medications(detailID: "m1"))
    }

    @Test func medicationWithoutDetailStillOpensMedications() {
        let route = LaunchRouteResolver.resolve(medication: .some(nil), action: nil, method: nil)
        #expect(route == .medications(detailID: nil))
    }

    @Test func quickActionBeatsLogMethod() {
        #expect(LaunchRouteResolver.resolve(medication: nil, action: .barcode, method: .manual) == .quickAction(.barcode))
        #expect(LaunchRouteResolver.resolve(medication: nil, action: nil, method: .copyFromDay) == .logMethod(.copyFromDay))
    }

    @Test func sharedImageIsTheLastResort() {
        #expect(LaunchRouteResolver.resolve(medication: nil, action: nil, method: nil) == nil)
        #expect(LaunchRouteResolver.resolve(medication: nil, action: nil, method: nil, hasSharedImage: true) == .nutrition)
    }

    @Test func routesAreDistinctHashableValues() {
        let routes: Set<BrowseRoute> = [.nutrition, .fasting, .body, .activity, .workouts, .exerciseLibrary, .medications, .bodyMeasurements]
        #expect(routes.count == 8)
        #expect(MetricRoute.detail(.app(.calories)) != MetricRoute.detail(.app(.protein)))
        #expect(MetricRoute.detail(.health("steps")) != MetricRoute.favourites)
    }

    @Test func everyBrowseDomainHasADestination() {
        for category in BrowseCategory.ordered {
            let target = category.target
            let known = target.hasPrefix("screen:") || target.hasPrefix("metric:") || target.hasPrefix("category:") || target == "tab:records"
            #expect(known, "Unknown target \(target) for \(category.rawValue)")
            if target.hasPrefix("category:") {
                #expect(category.healthCategory != nil, "\(category.rawValue) should map to a registry category")
            }
            if target.hasPrefix("metric:") {
                #expect(MetricKey(pinID: String(target.dropFirst("metric:".count))) != nil)
            }
        }
    }

    @Test func tabsCoverTheFiveRoots() {
        #expect(AppTab.allCases.map(\.rawValue) == ["summary", "browse", "records", "coach", "settings"])
        #expect(AppTab.summary.accessibilityID == "tab.summary")
    }

    @Test func openBrowseReplacesThePath() {
        let navigator = AppNavigator()
        navigator.openBrowse([.activity, .workouts])
        #expect(navigator.selectedTab == .browse)
        #expect(navigator.browsePath.count == 2)
        navigator.openBrowse([.nutrition])
        #expect(navigator.browsePath.count == 1)
        navigator.resetBrowse()
        #expect(navigator.browsePath.isEmpty)
    }

    @Test func openNutritionCarriesTheRequest() {
        let navigator = AppNavigator()
        navigator.openNutrition(action: .voice)
        #expect(navigator.quickActionRequest?.action == .voice)
        #expect(navigator.browsePath.count == 1)
        navigator.openNutrition(method: .copyFromDay)
        #expect(navigator.foodLogMethodRequest?.method == .copyFromDay)
    }
}
