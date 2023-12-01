// For the SDK performance CI job.

import java.util.stream.Collectors

String GERRIT_REPO = 'ssh://review.couchbase.org:29418/transactions-fit-performer.git'

Boolean INSTALL_CLOUD_NATIVE_GATEWAY = true
// Run specific CNG so we know we're performance testing the same thing.
String CLOUD_NATIVE_GATEWAY_DOCKER_VERSION = "ghcr.io/cb-vanilla/cloud-native-gateway:0.2.0-141"

Boolean SLEEP_ON_FAIL = false

String AGENT_UBUNTU = "ubuntu20"
String AGENT_MAC = "m1"
String AGENT = AGENT_UBUNTU

def runAWS(String command) {
    return sh(script: "aws ${command}", returnStdout: true)
}

def runSSH(String ip, String command, boolean returnStdout = false, boolean inBackground = false) {
    sh(script: 'ssh ' + (inBackground ? '-f ' : '') + '-o "StrictHostKeyChecking=no" -i $SSH_KEY_PATH ec2-user@' + ip + " '" + command + "'", returnStdout: returnStdout)
}

def ignore(String command) {
    try {
        sh(script: command)
    }
    catch (ignored) {}
}

void setupPrerequisitesCentos8() {
    sh(script: "cd /etc/yum.repos.d")
    // https://techglimpse.com/failed-metadata-repo-appstream-centos-8/
    sh(script: "sudo sed -i 's/mirrorlist/#mirrorlist/g' /etc/yum.repos.d/CentOS-*")
    sh(script: "sudo sed -i 's|#baseurl=http://mirror.centos.org|baseurl=http://vault.centos.org|g' /etc/yum.repos.d/CentOS-*")
    sh(script: "sudo yum install awscli -y -q")
}

void setupPrerequisitesMac() {
    sh(script: 'curl "https://awscli.amazonaws.com/AWSCLIV2.pkg" -o "AWSCLIV2.pkg"')
    sh(script: 'sudo installer -pkg AWSCLIV2.pkg -target /')
}

void setupPrerequisitesUbuntu() {
    sh(script: 'sudo apt-get -qq update', returnStdout: true)
    sh(script: 'sudo apt -qq install --assume-yes awscli', returnStdout: true)
}

stage("run") {

    node(AGENT) {
        if (AGENT == AGENT_MAC) {
            // Running on Mac allows ssh-ing in, but it's not that useful as most work is done on the AWS node (which we
            // can always SSH onto). And there's limited numbers of Mac agents.
            String agentIp = sh(script: "ipconfig getifaddr en0", returnStdout: true).trim()
            echo "ssh couchbase@${agentIp}"
        }

        // Private repo, so cannot check out directly on AWS node.  Need to scp it over.
        dir ("transactions-fit-performer") {
            checkout([$class: 'GitSCM', userRemoteConfigs: [[url: "git@github.com:couchbaselabs/transactions-fit-performer.git"]]])
            if (TRANSACTIONS_FIT_PERFORMER_REFSPEC != '') {
                echo 'TRANSACTIONS_FIT_PERFORMER_REFSPEC is not null. So applying gerrit changes'
                checkout([$class: 'GitSCM', branches: [[name: "FETCH_HEAD"]],  userRemoteConfigs: [[refspec: "$TRANSACTIONS_FIT_PERFORMER_REFSPEC", url: "$GERRIT_REPO"]]])
                sh(script: "git log -n 3")
            }
        }

        withAWS(credentials: 'aws-sdkqe') {
            withCredentials([file(credentialsId: 'abbdb61e-d9b7-47ea-b160-7ff840c97bda', variable: 'SSH_KEY_PATH')]) {
                withCredentials([string(credentialsId: 'TIMEDB_PWD', variable: 'TIMEDB_PWD')]) {
                withCredentials([string(credentialsId: 'github_container_registry_token', variable: 'GHCR_PASSWORD')]) {

                    // setupPrerequisitesCentos8()
                    if (AGENT == AGENT_MAC) {
                        setupPrerequisitesMac()
                    }
                    else if (AGENT == AGENT_UBUNTU) {
                        setupPrerequisitesUbuntu()
                    }

                    // String instanceType = "c5.large" // for cheaper iteration
                    // String instanceType = "c5d.4xlarge"  // $0.768 an hour 16vCPU 32GB 400GB NVMe storage
                    String instanceType = "c5.4xlarge"  // $0.768 an hour 16vCPU 32GB
                    String region = "us-west-1" // us-east-2 is the cheapest, but us-west-1 is where the OpenShift cluster is run (to be close to HQ)
                    // String imageId = "ami-02d1e544b84bf7502"     // Amazon Linux 2 x86-64 on us-east-2
                    // String imageId = "ami-03c7d01cf4dedc891"     // Amazon Linux 2 x86-64 on us-east-1
                    String imageId = "ami-0583a1f1cd3c11ebc"        // Amazon Linux 2 x86-64 on us-west-1
                    String hdSizeGB = 400 // CBD-5001 - seeing issues with the default 8GB.  The --block-device-mappings DeviceName must match the AMI's.
                    // String securityGroup = "sg-40ff4629"            // for us-east-2
                    // String securityGroup = "sg-073bffa623008db9c"   // for us-east-1
                    String securityGroup = "sg-e942db8d"            //  for us-west-1

                    String instanceId = runAWS("ec2 run-instances --image-id ${imageId} --count 1 --instance-type ${instanceType} --key-name cbdyncluster --security-group-ids ${securityGroup} --region ${region} --output text --query 'Instances[*].InstanceId' --block-device-mappings 'DeviceName=/dev/xvda,Ebs={VolumeSize=${hdSizeGB}}'").trim()
                    echo "Created AWS instance ${instanceId}"
                    String ip = null

                    try {
                        // CBD-5002: Remove any old instances first.  Shouldn't be any running, this is a safety check.
                        removeOldInstances(region)

                        ip = waitForInstanceToBeReady(region, instanceId)

                        sh(script: 'scp -C -r -o "StrictHostKeyChecking=no" -i $SSH_KEY_PATH transactions-fit-performer ec2-user@' + ip + ":transactions-fit-performer")

                        // Setup Docker
                        runSSH(ip, "sudo yum update -y", true)
                        // Anytime we're returning output here, it's just to hide very verbose output
                        runSSH(ip, "sudo amazon-linux-extras install docker", true)
                        // Don't require docker to be run as sudo
                        runSSH(ip, "sudo usermod -aG docker ec2-user")
                        runSSH(ip, "sudo systemctl start docker")
                        // All Docker containers will be run on this network
                        runSSH(ip, "docker network create perf")

                        // We've installed just the minimum to get the Cluster up, so it can be coming up while we're doing other stuff
                        runSSH(ip, "docker run -d --name cbs --network perf -p 8091-8096:8091-8096 -p 11210-11211:11210-11211 couchbase:7.1.1 >/dev/null 2>&1", true)

                        runSSH(ip, "sudo yum install -y git java-17-amazon-corretto-devel", true)

                        runSSH(ip, "git clone https://github.com/couchbaselabs/jenkins-sdk")
                        runSSH(ip, "git clone https://github.com/couchbase/couchbase-jvm-clients")
                        if (COUCHBASE_JVM_CLIENTS_REFSPEC != '') {
                            echo 'Applying COUCHBASE_JVM_CLIENTS_REFSPEC'
                            runSSH(ip, "cd couchbase-jvm-clients && git fetch https://review.couchbase.org/couchbase-jvm-clients ${COUCHBASE_JVM_CLIENTS_REFSPEC} && git checkout FETCH_HEAD")
                        }

                        runSSH(ip, "git clone https://github.com/couchbase/couchbase-net-client")

                        try {
                            // sed: look for the start of the cluster section; /a adds on the next line
                            runSSH(ip, 'sed -i "/- type: unmanaged/a\\      instance: ' + instanceType + '" jenkins-sdk/config/job-config.yaml')
                            runSSH(ip, 'sed -i "/- type: unmanaged/a\\      compaction: disabled" jenkins-sdk/config/job-config.yaml')
                            runSSH(ip, 'sed -i "/- type: unmanaged/a\\      topology: A" jenkins-sdk/config/job-config.yaml')
                            if (INSTALL_CLOUD_NATIVE_GATEWAY) {
                                runSSH(ip, 'sed -i "/- type: unmanaged/a\\      cloudNativeGatewayVersion: ' + CLOUD_NATIVE_GATEWAY_DOCKER_VERSION + '" jenkins-sdk/config/job-config.yaml')
                            }
                            runSSH(ip, "cat jenkins-sdk/config/job-config.yaml")
                        }
                        catch (error) {
                            echo "Failed to modify config: " + error
                        }

                        runSSH(ip, "cd jenkins-sdk && ./gradlew -q shadowJar")
                        runSSH(ip, "find . -iname '*SNAPSHOT*.jar'")

                        // Cluster should be up by now
                        script {
                            // Have 32GB on these nodes, leave 4GB for the driver and performer
                            def memoryQuota = 28000
                            runSSH(ip, "docker exec cbs opt/couchbase/bin/couchbase-cli cluster-init -c localhost --cluster-username Administrator --cluster-password password --services data,index,query --cluster-ramsize ${memoryQuota}")
                            runSSH(ip, "docker exec cbs opt/couchbase/bin/couchbase-cli bucket-create -c localhost --username Administrator --password password --bucket default --bucket-type couchbase --bucket-ramsize ${memoryQuota}")
                            runSSH(ip, "curl -u Administrator:password http://localhost:8091/pools/default -d memoryQuota=${memoryQuota}")

                            // We're focussed on SDK performance, so disable server settings that can affect performance
                            // Note that per CBD-5001, this means we need to periodically compact manually
                            runSSH(ip, 'curl -u Administrator:password http://localhost:8091/controller/setAutoCompaction -d databaseFragmentationThreshold[percentage]="undefined" -d parallelDBAndViewCompaction=false')

                            if (INSTALL_CLOUD_NATIVE_GATEWAY) {
                                runSSH(ip, "echo $GHCR_PASSWORD")
                                runSSH(ip, "docker login ghcr.io -u qecouchbase --password $GHCR_PASSWORD")
                                runSSH(ip, "docker run -d --network perf -p 8443:18098/tcp $CLOUD_NATIVE_GATEWAY_DOCKER_VERSION --cb-host cbs --self-sign")
                            }
                        }

                        // Run jenkins-sdk, which will do everything else
                        sh(script: 'ssh -o "StrictHostKeyChecking=no" -i $SSH_KEY_PATH ec2-user@' + ip + ' "cd jenkins-sdk && java -jar build/libs/jenkins2-1.0-SNAPSHOT-all.jar $TIMEDB_PWD"')
                    }
                    catch (err) {
                        // For debugging, can log into the agent (if Mac) or the AWS instance now
                        if (SLEEP_ON_FAIL) {
                            echo "http://${ip}:8091"
                            echo "ssh -i ~/keys/cbdyncluster.pem ec2-user@${ip}"
                            sleep(60 * 60 * 12) // in seconds.  Setting to give plenty of time for debugging an overnight run, but not be too expensive.
                        }
                        throw err;
                    }
                    finally {
                        runAWS("ec2 terminate-instances --instance-ids ${instanceId} --region ${region}")
                    }
                }
                }
            }
        }
    }


}

String waitForInstanceToBeReady(String region, String instanceId) {
    // Can't find documentation on what order these lifecycle events occur in, and have seen issues where unable to
    // ssh when just waiting for instance-running, so just wait for them all
    try {
        runAWS("ec2 wait instance-running --region ${region} --instance-ids ${instanceId}")
        // This takes a while and is maybe unnecessary now with the ssh polling below
        // runAWS("ec2 wait instance-status-ok --region ${region} --instance-ids ${instanceId}")
    }
    catch (err) {
        echo "Got error waiting, continuing: ${err}"
    }

    String ip = runAWS("ec2 describe-instances --region ${region} --instance-ids ${instanceId} --output text --query 'Reservations[0].Instances[0].NetworkInterfaces[0].Association.PublicIp'").trim()

    echo "AWS instance running on ${ip}"

    // "instance-running" status at least does not seem to guarantee that services like SSH are available, so poll
    int guard = 30
    boolean done = false
    while (!done) {
        try {
            runSSH(ip, "sudo whoami")
            done = true
        }
        catch (err) {
            echo "Got error while polling for ssh connectivity, continuing: ${err}"
            sleep(1)
        }

        guard -= 1
        if (guard == 0) {
            done = true
        }
    }

    return ip
}

void removeOldInstances(String region) {
    try {
        String oldInstancesRaw = runAWS("ec2 describe-instances --region ${region} --filters \"Name='tag:project',Values=sdk-performance\" --output text --query 'Reservations[*].Instances[*].InstanceId'").trim()
        echo oldInstancesRaw
        List<String> oldInstances = oldInstancesRaw.split('\n').toList()
        oldInstances.each { if (!it.trim().isEmpty()) runAWS("ec2 terminate-instances --instance-ids ${it.trim()} --region ${region}") }
    }
    catch (err) {
        echo "Got error trying to remove old instances, continuing: ${err}"
    }
}
