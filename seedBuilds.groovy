import org.kohsuke.github.GitHub

def gh = GitHub.connectAnonymously()
gh.getOrganization('fabric8io').listRepositories().each { repo ->
    def repoName = repo.name
    println "Found build ${repoName}"
    if (repoName == "example-camel-cdi") {
      job(repo.name) {
          scm {
              gitHub(repo.fullName)
          }
          steps {
              // ...
          }
      }
    }
}
