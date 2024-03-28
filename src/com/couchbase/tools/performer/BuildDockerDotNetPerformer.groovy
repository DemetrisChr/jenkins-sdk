package com.couchbase.tools.performer

import com.couchbase.context.environments.Environment
import com.couchbase.tools.tags.TagProcessor
import com.couchbase.versions.ImplementationVersion
import groovy.transform.CompileStatic

@CompileStatic
class BuildDockerDotNetPerformer {
    /**
     * @param path absolute path to above 'transactions-fit-performer'
     * @param build what to build
     */
    static void build(Environment imp, String path, VersionToBuild build, String imageName, boolean onlySource = false) {
        imp.log("Building .NET ${build}")

        // Build context needs to be perf-sdk as we need the .proto files
        imp.dirAbsolute(path) {
            imp.dir('transactions-fit-performer') {
                imp.execute('git submodule update --init --recursive', false, false, true)
                imp.dir('performers/dotnet') {
                    // couchbase-net-client is a git submodule
                    TagProcessor.processTags(new File(imp.currentDir()), build)
                }
            }
            if (!onlySource) {
                var dockerfile = "Dockerfile_NET8"
                if (build instanceof HasVersion && build.implementationVersion().isBelow(ImplementationVersion.from("3.4.10"))){
                    dockerfile = "Dockerfile_NET6"
                }
                if (build instanceof BuildMain) {
                    imp.execute("docker build -f transactions-fit-performer/performers/dotnet/Couchbase.Transactions.FitPerformer/$dockerfile -t $imageName .", false, true, true)
                }
                else if (build instanceof BuildGerrit) {
                    imp.execute("docker build -f transactions-fit-performer/performers/dotnet/Couchbase.Transactions.FitPerformer/$dockerfile -t $imageName --build-arg BUILD_GERRIT=${build.gerrit()} .", false, true, true)
                }
                else if (build instanceof HasSha) {
                    imp.execute("docker build -f transactions-fit-performer/performers/dotnet/Couchbase.Transactions.FitPerformer/$dockerfile -t $imageName --build-arg SDK_BRANCH=${build.sha()} .", false, true, true)
                }
                else if (build instanceof HasVersion) {
                    imp.execute("docker build -f transactions-fit-performer/performers/dotnet/Couchbase.Transactions.FitPerformer/$dockerfile -t $imageName --build-arg SDK_BRANCH=tags/${build.version()} .", false, true, true)
                }
            }
        }
    }
}
