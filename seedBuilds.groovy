import io.fabric8.repo.git.resteasy.ResteasyGitRepoClient;
// lets define the organsations and projects to include/exclude
def excludedProjectNames = []
def includedProjectNames = []

def username = "${JENKINS_GOGS_USER}"
def password = "${JENKINS_GOGS_PASSWORD}"

def address = "http://${GOGS_SERVICE_HOST}:${GOGS_SERVICE_PORT}/"

println "Using git api url: ${address}"

// lets try run a maven command
def callCommand(String command) {
  def mvnProc = command.execute()
  println ">>> ${command}"
  println mvnProc.text
}

def mvnCall(goals) {
    def mavenVersion = '3.3.1'
    def mavenPath = "/var/jenkins_home/tools/hudson.tasks.Maven_MavenInstallation/${mavenVersion}/apache-maven-${mavenVersion}"
    def mavenBin = "${mavenPath}/bin/mvn"

    callCommand "${mavenBin} ${goals}"
}

def mvnFabric8CreateBuildConfig(options) {
    def fabric8Version = '2.2-SNAPSHOT'
    //def fabric8Version = '2.1.11'

    def command = "io.fabric8:fabric8-maven-plugin:${fabric8Version}:create-build-config ${options}"
    println "Creating the OpenShift BuildConfig"

    mvnCall command
}

mavenJob('base-maven-build') {
    keepDependencies(false)

    logRotator(
            1, // days to keep
            5, // num to keep
            -1, // artifact days to keep
            -1 // artifact num to keep
    )

    wrappers {
        timestamps()
        colorizeOutput()
        maskPasswords()
        timeout {
            elastic(
                    450, // Build will timeout when it take 3 time longer than the reference build duration, default = 150
                    5,   // Number of builds to consider for average calculation
                    120   // 30 minutes default timeout (no successful builds available as reference)
            )
            failBuild()
        }
    }
    mavenInstallation('3.3.1')
    localRepository(LocalRepositoryLocation.LOCAL_TO_WORKSPACE)
}

freeStyleJob('base-freestyle-build') {
    wrappers {
        timestamps()
        colorizeOutput()
        maskPasswords()
        golang('1.4.2')
        timeout {
            elastic(
                    150, // Build will timeout when it take 3 time longer than the reference build duration, default = 150
                    3,   // Number of builds to consider for average calculation
                    30   // 30 minutes default timeout (no successful builds available as reference)
            )
            failBuild()
        }
    }
}


def client = ResteasyGitRepoClient.createWithContextClassLoader(address, username, password)
repos = client.listRepositories()
repos.each { repo ->
    def fullName = repo.getFullName()
    def gitUrl = repo.getCloneUrl()
    def repoName = fullName.substring(fullName.indexOf("/") + 1)

    println "Found repo name: ${repoName}, full: ${fullName}, clone url: ${gitUrl}"

    if (!excludedProjectNames.contains(repoName) && (includedProjectNames.contains(repoName) || includedProjectNames.isEmpty())) {
        println "Adding repo ${repoName} to jenkins build"
        createJobs(repoName, fullName, gitUrl, username, password)
    }
}


def createJobs(repoName, fullName, gitUrl, username, password) {
    def firstJobName = "${repoName}-ci"
    def monitorViewName = "${repoName}-cd-monitor"
    def pipelineViewName = "${repoName}-cd-pipeline"

    /**
     * CI Build
     */
    mavenJob(firstJobName) {
        using('base-maven-build')
        description('Run the build and the unit tests with a specific version. If they pass, move it to the next step.')
        blockOnDownstreamProjects()

        parameters {
            stringParam(
                    'MAJOR_VERSION_NUMBER', // param name
                    '1.0', // default value
                    'The major version. We will not use SNAPSHOTs for CD, so need to give a real version'
            )
        }
        scm {
            git(gitUrl, '*/master') {
                clean(true)
                createTag(false)
                cloneTimeout(30)
            }
        }
        publishers {
            downstreamParameterized {
                trigger(repoName + "-it", 'SUCCESS', false){
                    predefinedProp('TAG_PREFIX', repoName)
                    predefinedProp('RELEASE_NUMBER', '$MAJOR_VERSION_NUMBER.$BUILD_NUMBER')
                }
            }
        }
        preBuildSteps {
            shell("git checkout -b ${repoName}-\$MAJOR_VERSION_NUMBER.\$BUILD_NUMBER")
            maven('versions:set -DnewVersion=$MAJOR_VERSION_NUMBER.$BUILD_NUMBER')
        }
        postBuildSteps {
            conditionalSteps {
                condition {
                    status("SUCCESS", "SUCCESS")
                }

                shell("git commit -a -m \'new release candidate\' \n " +
                      "git push http://${username}:${password}@${GOGS_SERVICE_HOST}:${GOGS_SERVICE_PORT}/${fullName}.git  ${repoName}-\$MAJOR_VERSION_NUMBER.\$BUILD_NUMBER")
            }
            conditionalSteps {
                condition {
                    status("FAILURE", "FAILURE")
                }
                shell("git branch -D ${repoName}-\$MAJOR_VERSION_NUMBER.\$BUILD_NUMBER")
            }

        }

        goals('clean install')
    }

    /**
     * Integration Test Build
     */
    mavenJob("${repoName}-it") {
        using('base-maven-build')
        description('Run the itests for this module (expected to have a maven profile named itests. If they pass, move it to the next step.')
        parameters {
            stringParam(
                    'TAG_PREFIX', // prefix of the tag we created in the CI step, usually the repo name
                    repoName, // default value
                    'The tag prefix we created for the build if it passed the CI step'
            )
            stringParam(
                    'RELEASE_NUMBER', // the release we pushed to nexus and git repo
                    '', // no good default for this at the moment
                    'The release we pushed to nexus and git repo'
            )
        }
        scm {
            git(gitUrl, '${TAG-PREFIX}-${RELEASE_NUMBER}') {
                clean(true)
                createTag(false)
                cloneTimeout(30)
            }
        }
        publishers {
            downstreamParameterized {
                trigger("${repoName}-dev-deploy", 'SUCCESS', false){
                    predefinedProp('TAG_PREFIX', '$TAG_PREFIX')
                    predefinedProp('RELEASE_NUMBER', '$RELEASE_NUMBER')
                }
            }
        }
        goals('verify -Pitests')
    }

    /**
     * Deploy to DEV
     */
    freeStyleJob("${repoName}-dev-deploy") {
        using('base-freestyle-build')
        parameters {
            stringParam(
                    'TAG_PREFIX', // prefix of the tag we created in the CI step, usually the repo name
                    repoName, // default value
                    'The tag prefix we created for the build if it passed the CI step'
            )
            stringParam(
                    'RELEASE_NUMBER', // the release we pushed to nexus and git repo
                    '', // no good default for this at the moment
                    'The release we pushed to nexus and git repo'
            )
        }
        scm {
            git(gitUrl, '${TAG-PREFIX}-${RELEASE_NUMBER}') {
                clean(true)
                createTag(false)
                cloneTimeout(30)
            }
        }
        publishers {
            downstreamParameterized {
                trigger("${repoName}-dev-accept", 'SUCCESS', false){
                    predefinedProp('TAG_PREFIX', '$TAG_PREFIX')
                    predefinedProp('RELEASE_NUMBER', '$RELEASE_NUMBER')
                }
            }
        }
    }

    /**
     * Run Acceptance tests against DEV
     */
    mavenJob("${repoName}-dev-accept") {
        using('base-maven-build')
        parameters {
            stringParam(
                    'TAG_PREFIX', // prefix of the tag we created in the CI step, usually the repo name
                    repoName, // default value
                    'The tag prefix we created for the build if it passed the CI step'
            )
            stringParam(
                    'RELEASE_NUMBER', // the release we pushed to nexus and git repo
                    '', // no good default for this at the moment
                    'The release we pushed to nexus and git repo'
            )
        }
        scm {
            git(gitUrl) {
                branch('master')
                clean(true)
                createTag(false)
                cloneTimeout(30)
            }
        }

        goals('clean install')
    }


    buildPipelineView(pipelineViewName) {
        selectedJob("${repoName}-ci")
        title("Continuous Delivery pipeline for ${repoName}")
        refreshFrequency(30)
        showPipelineDefinitionHeader(true)
        showPipelineParameters(true)
        showPipelineParametersInHeaders(true)
        displayedBuilds(10)
        consoleOutputLinkStyle(OutputStyle.NewWindow)
    }

    buildMonitorView(monitorViewName) {
        description("All jobs for the ${repoName} CD pipeline")
        jobs {
            name("${repoName}-ci")
            name("${repoName}-it")
            name("${repoName}-dev-deploy")
            name("${repoName}-dev-accept")
        }
    }


    // now lets create an OpenShift BuildConfig for the CI / CD pipeline and passing in details of the Jenkins jobs and views:
    mvnFabric8CreateBuildConfig "-Dfabric8.repoName=${repoName} -Dfabric8.fullName=${fullName} -Dfabric8.gitUrl=${gitUrl} -Dfabric8.username=${username} -Dfabric8.jenkinsMonitorView=${monitorViewName}  -Dfabric8.jenkinsPipelineView=${pipelineViewName} -Dfabric8.jenkinsJob=${firstJobName}"
}


