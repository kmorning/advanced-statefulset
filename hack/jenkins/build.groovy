//
// Jenkins pipeline script for release job.
//
// It accepts one parameters:
//
// - GIT_URL (string): git url to build
// - GIT_REF (string): git ref to build
//

import groovy.transform.Field

@Field
def podYAML = '''
apiVersion: v1
kind: Pod
spec:
  containers:
  - name: main
    image: gcr.io/k8s-testimages/kubekins-e2e:v20191108-9467d02-master
    command:
    - runner.sh
    - sleep
    - 99d
    # we need privileged mode in order to do docker in docker
    securityContext:
      privileged: true
    env:
    - name: DOCKER_IN_DOCKER_ENABLED
      value: "true"
    resources:
      requests:
        memory: "4000Mi"
        cpu: 2000m
    # kind needs /lib/modules and cgroups from the host
    volumeMounts:
    - mountPath: /lib/modules
      name: modules
      readOnly: true
    - mountPath: /sys/fs/cgroup
      name: cgroup
    # dind expects /var/lib/docker to be volume
    - name: docker-root
      mountPath: /var/lib/docker
    - name: docker-graph
      mountPath: /docker-graph
  volumes:
  - name: modules
    hostPath:
      path: /lib/modules
      type: Directory
  - name: cgroup
    hostPath:
      path: /sys/fs/cgroup
      type: Directory
  - name: docker-root
    emptyDir: {}
  - name: docker-graph
    emptyDir: {}
'''

@Field
def ARTIFACTS = "/home/jenkins/agent/workspace/go/src/github.com/pingcap/advanced-statefulset/artifacts"

def build(GIT_URL, GIT_REF, SHELL_CODE, ARTIFACTS = "") {
    podTemplate(yaml: podYAML) {
        node(POD_LABEL) {
            container('main') {
                stage("Debug Info") {
                    println "debug command: kubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
                }
                stage('Checkout') {
                    dir("/home/jenkins/agent/workspace/go/src/github.com/pingcap/advanced-statefulset") {
                        checkout changelog: false,
                            poll: false,
                            scm: [
                                $class: 'GitSCM',
                                branches: [[name: "${GIT_REF}"]],
                                doGenerateSubmoduleConfigurations: false,
                                extensions: [],
                                submoduleCfg: [],
                                userRemoteConfigs: [[
                                    credentialsId: 'github-sre-bot-ssh',
                                    refspec: '+refs/heads/*:refs/remotes/origin/* +refs/pull/*:refs/remotes/origin/pr/*',
                                    url: "${GIT_URL}",
                                ]]
                            ]
                    }
                }
                stage('Run') {
                    try {
                        dir("/home/jenkins/agent/workspace/go/src/github.com/pingcap/advanced-statefulset") {
                            sh """
                            echo "====== shell env ======"
                            echo "pwd: \$(pwd)"
                            env
                            echo "====== go env ======"
                            go env
                            echo "====== docker version ======"
                            docker version
                            """
                            sh """
                            export GOPATH=/home/jenkins/agent/workspace/go
                            ${SHELL_CODE}
                            """
                        }
                    } finally {
                        if (ARTIFACTS != "") {
                            dir(ARTIFACTS) {
                                archiveArtifacts artifacts: "**", allowEmptyArchive: true
                                junit testResults: "*.xml", allowEmptyResults: true
                            }
                        }
                    }
                }
            }
        }
    }
}

def call(GIT_URL, GIT_REF) {
    timeout(60) {
        stage("Verify") {
            build(GIT_URL, GIT_REF, "make verify")
        }
        def builds = [:]
        builds["Build and Test"] = {
            build(GIT_URL, GIT_REF, "make build test")
        }
        builds["Integration"] = {
            build(GIT_URL, GIT_REF, "make test-integration")
        }
        def MIRRORS = "DOCKER_IO_MIRROR=http://172.16.4.143:5000"
        builds["E2E v1.16.3"] = {
            build(GIT_URL, GIT_REF, "${MIRRORS} IKUBE_VERSION=v1.16.3 GINKGO_NODES=8 ./hack/e2e.sh -- --report-dir=${ARTIFACTS} --report-prefix=v1.16.3_", ARTIFACTS)
        }
        builds["E2E v1.12.10"] = {
            build(GIT_URL, GIT_REF, "${MIRRORS} KUBE_VERSION=v1.12.10 GINKGO_NODES=8 ./hack/e2e.sh -- --report-dir=${ARTIFACTS} --report-prefix=v1.12.10_", ARTIFACTS)
        }
        builds.failFast = false
        parallel builds
    }
}

return this

// vim: et
