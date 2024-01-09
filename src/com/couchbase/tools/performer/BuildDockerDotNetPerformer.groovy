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

        if (build instanceof BuildGerrit) {
            throw new RuntimeException("Building Gerrit not currently supported for .NET")
        }

        // Build context needs to be perf-sdk as we need the .proto files
        imp.dirAbsolute(path) {
            imp.dir('transactions-fit-performer') {
                //The project file needs to be modified if the checked out .NET client does not have Protostellar.
                //Since the FitPerformer repository is only checked out once, two saves must be present.
                if (sdkVersion.isBelow(ImplementationVersion.from("3.4.14-rc1"))){
                    imp.execute("cp Couchbase.Transactions.FitPerformer/ProjectFiles/NoPSProjectFile.xml Couchbase.Transactions.FitPerformer/Couchbase.Transactions.FitPerformer.csproj", false, false, true)
                } else {
                    imp.execute("cp Couchbase.Transactions.FitPerformer/ProjectFiles/PSProjectFile.xml Couchbase.Transactions.FitPerformer/Couchbase.Transactions.FitPerformer.csproj", false, false, true)
                }
                imp.execute('git submodule update --init --recursive', false, false, true)
                imp.dir('performers/dotnet') {
                    // couchbase-net-client is a git submodule
                    imp.dir('couchbase-net-client') {
                        if (build instanceof BuildMain) {
                            imp.execute("git checkout master", false, false, true)
                        }
                        else if (build instanceof HasSha) {
                            imp.execute("git checkout ${build.sha()}", false, false, true)
                        }
                        else if (build instanceof HasVersion) {
                            imp.execute("git checkout tags/${build.version()}", false, false, true)
                        }
                    }
                    TagProcessor.processTags(new File(imp.currentDir()), build)
                }
            }
            if (!onlySource) {
                imp.execute("docker build -f transactions-fit-performer/performers/dotnet/Couchbase.Transactions.FitPerformer/Dockerfile -t $imageName .", false, true, true)
            }
        }
    }
}
