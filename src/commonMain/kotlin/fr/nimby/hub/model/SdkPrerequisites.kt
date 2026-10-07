package fr.nimby.hub.model

enum class SdkPrerequisiteStatus { NOT_DECLARED, COMPATIBLE, MISSING, INCOMPATIBLE, NOT_READY, UNVERIFIABLE }

/** Presentation of a candidate release against the chosen profile, not the
 * previously installed mod or the SDK currently active in another profile.
 * This does not authorize installation or change the profile. */
data class SdkPrerequisites(
    val minimum: String?,
    val maximumExclusive: String?,
    val selectedVersion: String?,
    val status: SdkPrerequisiteStatus,
) {
    companion object {
        fun forProject(candidate: Project, settings: HubSettings): SdkPrerequisites {
            val sdk = settings.selectedRecord("sdk")
            val minimum = candidate.sdkMin
            val maximum = candidate.sdkMaxExclusive
            val localSdk = settings.developing && settings.development.origins["sdk"] == ModOrigin.LOCAL
            val status = when {
                minimum == null && maximum == null && (candidate.loaderApi ?: 0) == 0 -> SdkPrerequisiteStatus.NOT_DECLARED
                (minimum == null) != (maximum == null) -> SdkPrerequisiteStatus.UNVERIFIABLE
                minimum != null && (!Versions.valid(minimum) || !Versions.valid(maximum!!) ||
                    Versions.compare(minimum, maximum) >= 0) -> SdkPrerequisiteStatus.UNVERIFIABLE
                sdk == null -> SdkPrerequisiteStatus.MISSING
                localSdk && settings.development.builds["sdk"]?.ready != true -> SdkPrerequisiteStatus.NOT_READY
                !Versions.valid(sdk.version) -> SdkPrerequisiteStatus.UNVERIFIABLE
                minimum != null && (Versions.compare(sdk.version, minimum) < 0 ||
                    Versions.compare(sdk.version, maximum!!) >= 0) -> SdkPrerequisiteStatus.INCOMPATIBLE
                // Match the existing compatibility rule for older SDK records
                // whose manifest predates the explicit loaderApi field.
                (candidate.loaderApi ?: 0) > (sdk.loaderApi ?: if (Versions.compare(sdk.version, "0.7.2") >= 0) 1 else 0) -> SdkPrerequisiteStatus.INCOMPATIBLE
                else -> SdkPrerequisiteStatus.COMPATIBLE
            }
            return SdkPrerequisites(minimum, maximum, sdk?.version, status)
        }
    }
}
