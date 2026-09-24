import Foundation
import Testing
@testable import calorietracker

/// The gallery's text (docs/coach.md §9). `shared/coach/prompt_gallery.json` is the source of truth:
/// every id must have a title and a prompt saying exactly what the catalog's English says.
/// The twin of `PromptGalleryTextTest.kt`.
struct PromptGalleryTextTests {
    private static var catalog: RJ {
        let url = HealthTestFixtures.repoRootURL.appendingPathComponent("shared/coach/prompt_gallery.json")
        return RJ.parse((try? String(contentsOf: url, encoding: .utf8)) ?? "") ?? .null
    }

    @Test func everyPromptHasItsTextVerbatim() throws {
        let prompts = try #require(Self.catalog["prompts"].array)
        #expect(!prompts.isEmpty)
        for entry in prompts {
            let id = try #require(entry["id"].string)
            #expect(PromptGalleryText.title(id) == entry["title_en"].string, Comment(rawValue: id))
            #expect(PromptGalleryText.prompt(id) == entry["prompt_en"].string, Comment(rawValue: id))
        }
    }

    @Test func everyCategoryHasATitle() throws {
        for value in Self.catalog["categories"].array ?? [] {
            let name = try #require(value.string)
            #expect(PromptGalleryText.category(name) != nil, Comment(rawValue: name))
        }
    }

    /// The chip ids the shared selection can return must all be real gallery entries.
    @Test func everyChipIDIsInTheCatalog() throws {
        let ids = Set((Self.catalog["prompts"].array ?? []).compactMap { $0["id"].string })
        let chipIDs = CR.chipGoals.values.flatMap { $0 } + CR.chipDefault + ["sleep_week", "training_review"]
        for id in chipIDs {
            #expect(ids.contains(id), Comment(rawValue: "\(id) is not a gallery entry"))
        }
    }

    /// A chip is hidden by the same rule as a gallery card: what is not connected is not offered.
    @Test func chipsAreGatedLikeTheGallery() {
        let nothing = CR.chipsFor(goal: "lose", hasWorkouts: true, hasSleep: true,
                                  sources: .obj([:]), catalog: CoachCatalog.gallery)
        #expect((nothing["ids"].array ?? []).isEmpty)

        let food = CR.chipsFor(goal: "lose", hasWorkouts: false, hasSleep: true,
                               sources: .obj(["food": .bool(true)]), catalog: CoachCatalog.gallery)
        let ids = (food["ids"].array ?? []).compactMap(\.string)
        #expect(ids.contains("dinner_tonight"))
        #expect(!ids.contains("sleep_week"), "sleep needs the Health source")
    }
}
