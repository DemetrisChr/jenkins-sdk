package com.couchbase.tools.performer

import com.couchbase.context.environments.Environment
import com.couchbase.tools.tags.TagProcessor
import groovy.transform.CompileStatic

import java.util.logging.Logger
import java.util.regex.Pattern

@CompileStatic
class BuildDockerColumnarPythonPerformer {
    private static Logger logger = Logger.getLogger("")

    /**
     * @param imp        the build environment
     * @param path       absolute path to above 'transactions-fit-performer'
     * @param build what to build
     * @param imageName  the name of the docker image
     * @param onlySource whether to skip the docker build
     */
    static void build(Environment imp, String path, VersionToBuild build, String imageName, boolean onlySource = false, Map<String, String> dockerBuildArgs = [:]) {
        imp.log("Building Python ${build}")

        if (build instanceof BuildGerrit) {
            throw new RuntimeException("Building Gerrit not currently supported for Python")
        }

        imp.dirAbsolute(path) {
            imp.dir('transactions-fit-performer/performers/columnar/python') {
                writePythonRequirementsFile(imp, build)
                TagProcessor.processTags(new File(imp.currentDir()), build, Optional.of(Pattern.compile(".*\\.py")))
            }

            def serializedBuildArgs = dockerBuildArgs.collect((k, v) -> "--build-arg $k=$v").join(" ")

            if (!onlySource) {
                imp.execute("docker build -f ./transactions-fit-performer/performers/columnar/python/Dockerfile -t $imageName $serializedBuildArgs .", false, true, true)
            }
        }
    }

    private static void writePythonRequirementsFile(Environment imp, VersionToBuild build) {
        def requirements = new File("${imp.currentDir()}/requirements.txt")
        def lines = requirements.readLines()
        requirements.write("")

        for (String line : lines) {
            if ((line.contains("couchbase") && !line.contains("/")) || line.contains("github.com/couchbaselabs/columnar-python-client")) {
                if (build instanceof HasSha) {
                    requirements.append("git+https://github.com/couchbaselabs/columnar-python-client.git@${build.sha()}#egg=couchbase\n")
                }
                else if (build instanceof HasVersion) {
                    requirements.append("couchbase==${build.version()}\n")
                }
                else if (build instanceof BuildMain) {
                    requirements.append("git+https://github.com/couchbaselabs/columnar-python-client.git@master#egg=couchbase\n")
                }
            } else {
                requirements.append(line + "\n")
            }
        }
    }
}
