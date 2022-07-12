package com.couchbase.perf.shared.config


import com.fasterxml.jackson.annotation.JsonProperty
import groovy.json.JsonGenerator
import groovy.transform.CompileStatic
import groovy.transform.ToString


/**
 * The parsed job-config.yaml.
 *
 * Try to parse the minimum required into objects, as we currently have very similar code here and in the driver
 * (which has to parse a similar per-run config), and it's brittle.  Aim to just parse through the YAML into the per-run
 * config as much as possible.
 */
@CompileStatic
@ToString(includeNames = true, includePackage = false)
class PerfConfig {
    Servers servers
    Database database
    Map<String, String> executables
    Matrix matrix

    @ToString(includeNames = true, includePackage = false)
    static class Matrix {
        List<Cluster> clusters
        List<Implementation> implementations
        List<Object> workloads
    }

    @ToString(includeNames = true, includePackage = false)
    static class Servers {
        String performer
    }

    @ToString(includeNames = true, includePackage = false)
    static class Database {
        String host
        int port
        String user
        String password
        String database
    }

    // This is the superset of all supported cluster params.  Not all of them are used for each cluster type.
    @ToString(includeNames = true, includePackage = false)
    static class Cluster {
        String version
        Integer nodes
        Integer replicas
        String type
        String source
        String hostname
        String hostname_docker
        Integer port
    }

    @ToString(includeNames = true, includePackage = false)
    static class Implementation {
        String language
        String version
        Integer port
    }
}

@ToString(includeNames = true, includePackage = false)
class PredefinedVariablePermutation {
    String name
    Object value

    PredefinedVariablePermutation(String name, Object value){
        this.name = name
        this.value = value
    }

    enum PredefinedVariableName {
        @JsonProperty("horizontal_scaling") HORIZONTAL_SCALING,
        @JsonProperty("doc_pool_size") DOC_POOL_SIZE,
        @JsonProperty("durability") DURABILITY

        String toString() {
            return this.name().toLowerCase()
        }
    }
}

@CompileStatic
@ToString(includeNames = true, includePackage = false)
class Run {
    PerfConfig.Cluster cluster
    PerfConfig.Implementation impl
    Object workload

    def toJson() {
        Map<String, Object> jsonVars = new HashMap<>()

//        Map<String, String> clusterVars = new HashMap<>()
//        if(cluster.type == "gocaves"){
//            String hostname = "$cluster.hostname:$cluster.port"
//            clusterVars["hostname"] = hostname
//        }
//        else{
//            clusterVars["hostname"] = cluster.hostname
//        }

        def gen = new JsonGenerator.Options()
                .excludeNulls()
                .build()

        return gen.toJson([
                "impl"    : impl,
                "vars"    : jsonVars,
                // CBD-4971: (temporarily?) removing cluster from JSON
                // "cluster" : clusterVars,
                "workload": workload,
        ])
    }
}
