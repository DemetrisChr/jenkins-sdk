package com.couchbase.perf.shared.main

import com.couchbase.context.StageContext
import com.couchbase.context.environments.Environment
import com.couchbase.perf.sdk.stages.BuildSDKDriver
import com.couchbase.perf.sdk.stages.Defer
import com.couchbase.perf.sdk.stages.InitialiseSDKPerformer
import com.couchbase.perf.sdk.stages.Log
import com.couchbase.perf.sdk.stages.OutputPerformerConfig
import com.couchbase.perf.sdk.stages.RunSDKDriver
import com.couchbase.perf.shared.config.ConfigParser
import com.couchbase.perf.shared.config.PerfConfig
import com.couchbase.perf.shared.config.Run
import com.couchbase.perf.shared.database.PerfDatabase
import com.couchbase.perf.shared.database.RunFromDb
import com.couchbase.perf.shared.stages.StopDockerContainer
import com.couchbase.stages.*
import com.couchbase.stages.servers.InitialiseCluster
import com.couchbase.versions.CppVersions
import com.couchbase.versions.DotNetVersions
import com.couchbase.versions.GoVersions
import com.couchbase.versions.ImplementationVersion
import com.couchbase.versions.JVMVersions
import com.couchbase.versions.PythonVersions
import com.couchbase.versions.NodeVersions
import groovy.json.JsonSlurper
import groovy.transform.CompileStatic
import groovy.yaml.YamlSlurper

import java.util.stream.Collectors

import static java.util.stream.Collectors.groupingBy


class Execute {
    static void jcPrep(StageContext ctx, String[] args){
        //Get timescaledb password from jenkins credential
        String dbPwd = ""
        if (args.length > 0) {
            dbPwd = args[0]
            ctx.jc.database.password = args[0]
        }
    }

    @CompileStatic
    static List<Run> parseConfig(StageContext ctx) {
        def config = ConfigParser.readPerfConfig("config/job-config.yaml")
        modifyConfig(ctx, config)
        def allPerms = ConfigParser.allPerms(ctx, config)
        return allPerms
    }

    @CompileStatic
    static String getContents(String url, String username, String password) {
        def get = new URL(url).openConnection()
        get.setRequestProperty("Authorization", "Basic " + Base64.encoder.encodeToString((username + ":" + password).bytes))
        return get.getInputStream().getText()
    }

    static List<PerfConfig.Implementation> versions(StageContext ctx, Object implementation, String client, Set<ImplementationVersion> versions) {
        String[] split = implementation.version.split("\\.")
        Integer lookingForMajor = null
        Integer lookingForMinor = null
        Integer lookingForPatch = null

        if (split[0] != 'X') lookingForMajor = Integer.parseInt(split[0])
        if (split[1] != 'X') lookingForMinor = Integer.parseInt(split[1])
        if (split[2] != 'X') lookingForPatch = Integer.parseInt(split[2])

        List<ImplementationVersion> lookingFor = versions.stream()
                .filter(v -> {
                    boolean out = true

                    // Bit of hardcoded logic to filter out Kotlin developer previews, since they don't compile with
                    // the current performer
                    if (implementation.language == "Kotlin"
                        && v.snapshot != null
                        && v.snapshot.startsWith("-dp")) {
                        ctx.env.log("Filtering out kotlin ${v.toString()}")
                        out = false
                    }

                    if (out && lookingForMajor != null && lookingForMajor != v.major) out = false
                    if (out && lookingForMinor != null && lookingForMinor != v.minor) out = false
                    if (out && lookingForPatch != null && lookingForPatch != v.patch) out = false
                    return out
                })
                .toList()

        return lookingFor.stream()
                .map(v -> new PerfConfig.Implementation(implementation.language, v.toString(), null))
                .toList()
    }

    static List<PerfConfig.Implementation> jvmVersions(StageContext ctx, Object implementation, String client) {
        def allVersions = JVMVersions.getAllJVMReleases(client)
        return versions(ctx, implementation, client, allVersions)
    }

    static void modifyConfig(StageContext ctx, PerfConfig config) {
        List< PerfConfig.Implementation> implementationsToAdd = new ArrayList<>()

        config.matrix.implementations.forEach(implementation -> {
            if (implementation.version == "snapshot") {
                if (implementation.language == "Java") {
                    def snapshot = JVMVersions.getLatestSnapshotBuild("java-client")
                    ctx.env.log("Found snapshot build for Java: ${snapshot}")
                    implementationsToAdd.add(new PerfConfig.Implementation(implementation.language, snapshot.toString(), null))
                }
                else if (implementation.language == "Kotlin") {
                    def snapshot = JVMVersions.getLatestSnapshotBuild("kotlin-client")
                    ctx.env.log("Found snapshot build for Kotlin: ${snapshot}")
                    implementationsToAdd.add(new PerfConfig.Implementation(implementation.language, snapshot.toString(), null))
                }
                else if (implementation.language == "Scala") {
                    def snapshot = JVMVersions.getLatestSnapshotBuild("scala-client_2.12")
                    ctx.env.log("Found snapshot build for Scala: ${snapshot}")
                    implementationsToAdd.add(new PerfConfig.Implementation(implementation.language, snapshot.toString(), null))
                }
                else if (implementation.language == ".NET") {
                    def sha = DotNetVersions.getLatestSha()
                    def allReleases = DotNetVersions.getAllReleases()
                    def highest = ImplementationVersion.highest(allReleases)
                    ctx.env.log("Found latest sha for Dotnet: ${sha}")
                    String version = highest.toString() + "-" + sha
                    implementationsToAdd.add(new PerfConfig.Implementation(implementation.language, version, null, sha))
                }
                else if (implementation.language == "Go") {
                    def sha = GoVersions.getLatestSha()
                    def allReleases = GoVersions.getAllReleases()
                    def highest = ImplementationVersion.highest(allReleases)
                    ctx.env.log("Found latest sha for Go: ${sha}")
                    String version = highest.toString() + "-" + sha
                    implementationsToAdd.add(new PerfConfig.Implementation(implementation.language, version, null, null))
                }
                else if (implementation.language == "Python") {
                    def sha = PythonVersions.getLatestSha()
                    def allReleases = PythonVersions.getAllReleases()
                    def highest = ImplementationVersion.highest(allReleases)
                    ctx.env.log("Found latest sha for Python: ${sha}")
                    String version = highest.toString() + "-" + sha
                    implementationsToAdd.add(new PerfConfig.Implementation(implementation.language, version, null, sha))
                }
                else if (implementation.language == "Node") {
                    def sha = NodeVersions.getLatestSha()
                    def allReleases = NodeVersions.getAllReleases()
                    def highest = ImplementationVersion.highest(allReleases)
                    ctx.env.log("Found latest sha for Node: ${sha}")
                    String version = highest.toString() + "-" + sha
                    implementationsToAdd.add(new PerfConfig.Implementation(implementation.language, version, null, sha))
                }
                else if (implementation.language == "C++") {
                    def sha = CppVersions.getLatestSha()
                    // Version number is hardcoded for now - TODO: Change this when the next version is released
                    def version = CppVersions.getLatestSnapshotLabel()
                    ctx.env.log("Found latest sha for C++: ${sha}")
                    implementationsToAdd.add(new PerfConfig.Implementation(implementation.language, version, null, sha))
                }
                else {
                    throw new UnsupportedOperationException("Cannot support snapshot builds with language ${implementation.language} yet")
                }
            }
            else if (implementation.version.contains('X')) {
                if (implementation.language == "Java") implementationsToAdd.addAll(jvmVersions(ctx, implementation, "java-client"))
                else if (implementation.language == "Scala") implementationsToAdd.addAll(jvmVersions(ctx, implementation, "scala-client_2.12"))
                else if (implementation.language == "Kotlin") implementationsToAdd.addAll(jvmVersions(ctx, implementation, "kotlin-client"))
                else if (implementation.language == ".NET") implementationsToAdd.addAll(versions(ctx, implementation, ".NET", DotNetVersions.allReleases))
                else if (implementation.language == "Go") implementationsToAdd.addAll(versions(ctx, implementation, "Go", GoVersions.allReleases))
                else if (implementation.language == "Python") implementationsToAdd.addAll(versions(ctx, implementation, "Python", PythonVersions.allReleases))
                else if (implementation.language == "Node") implementationsToAdd.addAll(versions(ctx, implementation, "Node", NodeVersions.allReleases))
                else if (implementation.language == "C++") implementationsToAdd.addAll(versions(ctx, implementation, "C++", CppVersions.allReleases))
                else {
                    throw new UnsupportedOperationException("Cannot support snapshot builds with language ${implementation.language} yet")
                }
            }
            else if (implementation.version.startsWith('refs/')) {
                if (!["Java", "Scala", "Kotlin"].contains(implementation.language)) {
                    throw new UnsupportedOperationException("Gerrit builds not currently supported for " + implementation.language)
                }
                implementationsToAdd.add(new PerfConfig.Implementation(implementation.language, implementation.version, null, null))
            }
            // If adding another special type here, remember to add it to removeIf below
        })

        config.matrix.implementations.removeIf(v -> v.version == "snapshot" || v.version.contains('X') || v.version.startsWith('refs/'))
        if (implementationsToAdd != null) {
            config.matrix.implementations.addAll(implementationsToAdd)
        }
        ctx.env.log("Added ${implementationsToAdd} snapshot or range versions")

        config.matrix.clusters.forEach(cluster -> {

            String hostname = cluster.hostname
            String adminUsername = "Administrator"
            String adminPassword = "password"

            try {
                var resp1 = getContents("http://" + hostname + ":8091/pools/default", adminUsername, adminPassword)
                var resp2 = getContents("http://" + hostname + ":8091/pools", adminUsername, adminPassword)

                def jsonSlurper = new JsonSlurper()

                var raw1 = jsonSlurper.parseText(resp1)
                var raw2 = jsonSlurper.parseText(resp2)

                var node1 = raw1.nodes[0]

                // These null checks so we can set these params in the config when wanting to compare localhost against
                // a copy of the prod database.
                if (cluster.nodeCount == null) {
                    cluster.nodeCount = raw1.nodes.size()
                }
                if (cluster.memory == null) {
                    cluster.memory = raw1.memoryQuota
                }
                if (cluster.cpuCount == null) {
                    cluster.cpuCount = node1.cpuCount
                }
                if (cluster.version == null) {
                    cluster.version = raw2.implementationVersion
                }
            }
            catch (Throwable err) {
                ctx.env.log("Could not connect to cluster ${hostname} with ${adminUsername}:${adminPassword}")
                throw err
            }
        })
    }

    static Map<PerfConfig.Cluster, List<Run>> parseConfig2(StageContext ctx, List<RunFromDb> fromDb) {
        /**
         * Config file declaratively says what runs should exist.  Our job is to compare to runs that do exist, and run any required.
         *
         * Read all permutations
         * See what runs already exist
         * Group these by cluster, then by performer. Each cluster-performer pair is going to run '2nd chunk'
         * For each cluster, bring it up
         * For each cluster-performer in that cluster
         * - Build and bring up the performer
         * - Run it with required runs. Ah hmm will need to fully unroll the variables here.
         * - Bring down performer
         * Bring down cluster
         */

        def toRun = fromDb.stream()
                .filter(run -> run.dbRunIds.isEmpty() || ctx.force)
                .map(run -> run.run)
                .collect(Collectors.toList())

        def groupedByCluster = toRun.stream()
                .collect(groupingBy((Run run) -> run.cluster))

        ctx.env.log("Have ${toRun.size()} runs not in database (or forced rerun), requiring ${groupedByCluster.size()} clusters")

        toRun.forEach(run -> ctx.env.log("Run: ${run}"))

        return groupedByCluster
    }

    static List<Stage> plan(StageContext ctx, Map<PerfConfig.Cluster, List<Run>> input, jc) {
        def stages = new ArrayList<Stage>()

        if (!ctx.skipDriverDockerBuild() && !input.isEmpty()) {
            stages.add(new BuildSDKDriver())
        }

        int runIdx = 0
        int runsTotal = 0
        input.forEach((k, v) -> runsTotal += v.size())

        def failedJobs = new ArrayList<String>()

        input.forEach((cluster, runsForCluster) -> {
            def clusterStage = new InitialiseCluster(cluster)
            def clusterChildren = new ArrayList<Stage>()

            def groupedByPerformer = runsForCluster.stream()
                    .collect(groupingBy((Run run) -> run.impl))

            ctx.env.log("Cluster ${cluster} requires ${groupedByPerformer.size()} performers")

            groupedByPerformer.forEach((performer, runsForClusterAndPerformer) -> {
                def performerRuns = []

                // We can perform multiple runs inside a single execution of a driver+performer pair.
                runsForClusterAndPerformer.forEach(run -> new Log("Run ${++runIdx} of ${runsTotal} ${run.impl.language} ${run.impl.version}"))

                def performerStage = new InitialiseSDKPerformer(performer)
                def runId = UUID.randomUUID().toString()
                def configFilenameAbs = "${ctx.env.workspaceAbs}${File.separatorChar}${runId}.yaml"

                def output = new OutputPerformerConfig(
                        clusterStage,
                        performerStage,
                        jc,
                        cluster,
                        performer,
                        runsForClusterAndPerformer,
                        ctx.jc.settings,
                        configFilenameAbs)

                performerRuns.add(new StopDockerContainer(InitialiseSDKPerformer.CONTAINER_NAME))
                performerRuns.add(output)

                clusterChildren.addAll(performerRuns)
                // ScopedStage because we want to bring performer up, run driver, bring performer down
                clusterChildren.add(new ScopedStage(performerStage, [new RunSDKDriver(output)],
                        (err) -> {
                            def jobName = "${performer.language} ${performer.version}"
                            ctx.env.log("Job ${jobName} failed with err: ${err}")
                            failedJobs.add(jobName)
                            if (ctx.stopOnFailure()) {
                                throw err
                            }
                        }))
            })

            stages.add(new ScopedStage(clusterStage, clusterChildren))
        })

        stages.add(new Defer(() -> {
            ctx.env.log("Failed jobs: ${failedJobs.size()}\n${failedJobs.join("\n")}")
            if (!failedJobs.isEmpty()) {
                throw new RuntimeException("${failedJobs.size()} jobs failed")
            }
        }))

        return stages
    }


    static void execute(String[] args) {
        def ys = new YamlSlurper()
        def configFile = new File("config/job-config.yaml")
        def jc = ys.parse(configFile)
        def env = new Environment(jc)
        env.log("Reading config from ${configFile.absolutePath}")

        def ctx = new StageContext()
        ctx.jc = jc
        ctx.env = env
        ctx.performerServer = jc.servers.performer
        ctx.dryRun = jc.variables.dryRun
        ctx.force = jc.variables.force
        ctx.runsRequired = jc.variables.runsRequired
        String version = jcPrep(ctx, args)
        def allPerms = parseConfig(ctx)
        PerfDatabase.migrate(ctx, args)
        def db = PerfDatabase.compareRunsAgainstDb(ctx, allPerms, args)
        def parsed2 = parseConfig2(ctx, db)
        def planned = plan(ctx, parsed2, jc)
        def root = new Stage() {
            @Override
            String name() {
                return "Root"
            }

            protected List<Stage> stagesPre(StageContext _) {
                return planned
            }

            @Override
            protected void executeImpl(StageContext _) {}
        }
        try {
            root.execute(ctx)
        } finally {
            root.finish(ctx)
        }
    }

    public static void main(String[] args) {
        execute(args)
    }
}
