package com.couchbase.versions

import com.couchbase.tools.network.NetworkUtil
import groovy.transform.Memoized


class CppVersions {
    private final static String REPO = "couchbase/couchbase-cxx-client"
    private final static String BRANCH = "main"

    @Memoized
    static String getLatestSha() {
        return GithubVersions.getLatestShaWithDatetime(REPO, BRANCH)
    }

    @Memoized
    static Set<ImplementationVersion> getAllReleases() {
        def out = GithubVersions.getAllReleases(REPO)
        return withoutUnsupportedVersions(out)
    }

    static String formatSnapshotVersion(ImplementationVersion version, String sha) {
        return Versions.appendPreReleaseIdentifierToVersion(version.toString(), sha)
    }

    /**
     * Removes the versions that are not supported by the C++ Performer. Currently the unsupported versions are
     * all 1.0.0-beta.X releases and 1.0.0-dp.X releases where X < 10
     */
    private static Set<ImplementationVersion> withoutUnsupportedVersions(Set<ImplementationVersion> allVersions) {
        return allVersions.findAll( (v) -> {
            if (!(v.major == 1 && v.minor == 0 && v.patch == 0) || v.snapshot == null) return true

            String[] parts = v.snapshot.substring(1).split("\\.")
            if (parts[0] == "dp") {
                return Integer.parseInt(parts[1]) >= 10
            } else return parts[0] != "beta"
        })
    }
}
