import Foundation
import Testing
@testable import calorietracker

/// The profile store, its migration, and the flat-key mirror (docs/ai-models.md §4).
///
/// These cover what a vector cannot: that the store and the Keychain end up holding what the pure
/// functions say they should, and that the mirror is never left stale — which is the failure that
/// would silently make the Records chip and the export header name a provider no real call uses.
struct AIProfileStoreTests {

    /// A throwaway UserDefaults so a test never touches the real app's settings.
    private static func scratch(_ name: String = UUID().uuidString) -> UserDefaults {
        let defaults = UserDefaults(suiteName: "ai.profile.tests." + name)!
        defaults.removePersistentDomain(forName: "ai.profile.tests." + name)
        return defaults
    }

    private static func seedLegacy(_ d: UserDefaults, provider: AIProvider = .openai,
                                   model: String = "gpt-5.4-mini") {
        d.set(provider.rawValue, forKey: "selectedAIProvider")
        d.set(model, forKey: "selectedAIModel")
    }

    // MARK: - Migration

    @Test func migrationTurnsTheFourLegacySlotsIntoProfiles() {
        let d = Self.scratch()
        Self.seedLegacy(d)
        d.set(true, forKey: "separateTextProviderEnabled")
        d.set(AIProvider.gemini.rawValue, forKey: "selectedTextAIProvider")
        d.set("gemini-3.5-flash-lite", forKey: "selectedTextAIModel")
        AIProviderSettings.migrateProfilesIfNeeded(defaults: d, nowMs: 1_700_000_000_000)

        let profiles = (AIProviderSettings.profilesRJ(d).array ?? [])
            .compactMap(AIModelProfile.init(rj:))
        #expect(profiles.count == 2)
        #expect(profiles.map(\.providerToken) == [AIProvider.openai.rawValue,
                                                  AIProvider.gemini.rawValue])
        // Nobody has to re-enter a key: every migrated profile inherits the provider's.
        #expect(profiles.allSatisfy { $0.credentialRef == "provider:\($0.providerToken)" })
        #expect(d.integer(forKey: AIProviderSettings.profilesMigrationVersionKey)
                == AIRef.migrationVersion)
    }

    @Test func migrationRunsExactlyOnce() {
        let d = Self.scratch()
        Self.seedLegacy(d)
        AIProviderSettings.migrateProfilesIfNeeded(defaults: d, nowMs: 1)
        let first = AIProviderSettings.profilesRJ(d).jsonText
        // A later launch with a different clock and a different flat selection must not re-migrate.
        d.set(AIProvider.anthropic.rawValue, forKey: "selectedAIProvider")
        AIProviderSettings.migrateProfilesIfNeeded(defaults: d, nowMs: 2)
        #expect(AIProviderSettings.profilesRJ(d).jsonText == first)
    }

    @Test func anEmptyLegacyStateStillYieldsAUsablePrimary() {
        let d = Self.scratch()
        AIProviderSettings.migrateProfilesIfNeeded(defaults: d, nowMs: 1)
        let roles = AIProviderSettings.rolesRJ(d)
        let primary = roles["image"]["profile_id"].string
        #expect(primary != nil)
        let profile = (AIProviderSettings.profilesRJ(d).array ?? [])
            .compactMap(AIModelProfile.init(rj:)).first { $0.id == primary }
        #expect(profile?.providerToken == AIProvider.gemini.rawValue)
    }

    // MARK: - The mirror

    @Test func theFlatKeysAlwaysMirrorTheRolePointers() {
        let d = Self.scratch()
        Self.seedLegacy(d)
        AIProviderSettings.migrateProfilesIfNeeded(defaults: d, nowMs: 1)
        AIProviderSettings.projectToLegacyKeys(profiles: AIProviderSettings.profilesRJ(d),
                                               roles: AIProviderSettings.rolesRJ(d), defaults: d)
        // What the mirror wrote must equal what the reference says it should have written.
        let projection = AIRef.projectLegacy(AIProviderSettings.profilesRJ(d).array ?? [],
                                             AIProviderSettings.rolesRJ(d))
        #expect(d.string(forKey: "selectedAIProvider")
                == projection["slots"]["image"]["provider"].string)
        #expect(d.string(forKey: "selectedAIModel")
                == projection["slots"]["image"]["model"].string)
        #expect(d.string(forKey: AIProviderSettings.primaryFingerprintKey)
                == projection["fingerprint"].string)
    }

    // MARK: - Onboarding

    @Test func onboardingsChoiceIsAdoptedNotIgnored() {
        let d = Self.scratch()
        Self.seedLegacy(d)
        AIProviderSettings.migrateProfilesIfNeeded(defaults: d, nowMs: 1)
        // Onboarding is unchanged: it writes the flat keys and nothing else.
        d.set(AIProvider.anthropic.rawValue, forKey: "selectedAIProvider")
        d.set("claude-sonnet-5", forKey: "selectedAIModel")
        let reason = AIProviderSettings.adoptLegacyPrimaryIfNeeded(defaults: d, nowMs: 2)
        #expect(reason == "created")
        let roles = AIProviderSettings.rolesRJ(d)
        let adopted = (AIProviderSettings.profilesRJ(d).array ?? [])
            .compactMap(AIModelProfile.init(rj:))
            .first { $0.id == roles["image"]["profile_id"].string }
        #expect(adopted?.providerToken == AIProvider.anthropic.rawValue)
        #expect(adopted?.modelID == "claude-sonnet-5")
    }

    @Test func anUnchangedProjectionIsLeftAlone() {
        let d = Self.scratch()
        Self.seedLegacy(d)
        AIProviderSettings.migrateProfilesIfNeeded(defaults: d, nowMs: 1)
        let before = AIProviderSettings.profilesRJ(d).jsonText
        #expect(AIProviderSettings.adoptLegacyPrimaryIfNeeded(defaults: d, nowMs: 2) == "match")
        #expect(AIProviderSettings.profilesRJ(d).jsonText == before)
    }

    @Test func adoptionNeverDuplicatesAnExistingConfiguration() {
        let d = Self.scratch()
        Self.seedLegacy(d)
        AIProviderSettings.migrateProfilesIfNeeded(defaults: d, nowMs: 1)
        let count = (AIProviderSettings.profilesRJ(d).array ?? []).count
        d.removeObject(forKey: AIProviderSettings.primaryFingerprintKey)
        #expect(AIProviderSettings.adoptLegacyPrimaryIfNeeded(defaults: d, nowMs: 2) == "adopted")
        #expect((AIProviderSettings.profilesRJ(d).array ?? []).count == count)
    }

    // MARK: - Resolution reads what the store wrote

    @Test func aDeletedProfileTakesItsRolePointerWithIt() {
        let d = Self.scratch()
        Self.seedLegacy(d)
        AIProviderSettings.migrateProfilesIfNeeded(defaults: d, nowMs: 1)
        let roles = AIProviderSettings.rolesRJ(d)
        let id = roles["image"]["profile_id"].string
        let resolved = AIRef.resolveRole(.obj([
            "profiles": AIProviderSettings.profilesRJ(d),
            "roles": .obj(["image": .obj(["profile_id": .null, "enabled": .bool(true)]),
                           "text": .obj(["profile_id": .null, "enabled": .bool(false)]),
                           "image_fallback": .obj(["profile_id": .null, "enabled": .bool(false)]),
                           "text_fallback": .obj(["profile_id": .null, "enabled": .bool(false)])]),
            "env": .obj(["requires_vision": .bool(false), "max_response_tokens": .int(1024)]),
        ]))
        #expect(id != nil)
        // Profiles exist but nothing points at them: refuse rather than answer from one of them.
        #expect(resolved["blocked"].string == "no_profile")
    }
}
