import org.kohsuke.github.GitHub

// lets define the organsations and projects to include/exclude
def githubOrganisations = ["fabric8io"]
def excludedProjectNames = []
def includedProjectNames = ["example-camel-cdi"]

mavenJob('base-maven-build') {
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

githubOrganisations.each { orgName ->
  def gh = GitHub.connectAnonymously()
  gh.getOrganization(orgName).listRepositories().each { repo ->
    def repoName = repo.name

    if (!excludedProjectNames.contains(repoName) && (includedProjectNames.contains(repoName) || includedProjectNames.isEmpty())) {
      def fullName = repo.getFullName()
      def gitUrl = repo.gitTransportUrl

      println "Found build ${repoName} with class ${repo.class.name} full ${fullName} and url ${gitUrl}"

      mavenJob(repo.name) {
        using('base-maven-build')
        scm {
          github(fullName) {
            branch('master')
            clean(true)
            createTag(false)
            cloneTimeout(30)
          }
        }

        goals('clean install')
      }
    }
  }
}
