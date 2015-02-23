// Load config.json
def config_data = '''
{
   "maven": "Maven 3.2.5",
   "jdk": "Oracle JDK 1.7",
   "profile": "clean package",
   "arquillian-core": {
      "downstream": [
         {
            "id": "arquillian-extension-drone",
            "exp": "version.arquillian.core"
         },
         {
            "id": "arquillian-extension-warp",
            "exp": "version.arquillian_core"
         }
      ]
   },
   "arquillian-extension-drone": {
      "downstream": [
         {
            "id": "arquillian-graphene",
            "exp": "version.arquillian.drone"
         }
      ]
   },
   "arquillian-container-weld": {
      "profiles": [
         "clean package -P 2.x",
         "clean package -P 1.1.x"
      ]
   }
}'''

def config = new groovy.json.JsonSlurper().parseText(config_data)

def repos = enrich(getRepositories())

def default_maven_install = config.get("maven", "Maven 3.2.5")
def default_jdk_install = config.get("jdk", "Oracle JDK 1.7")
def default_profile = config.get("profile", "clean package")

 // http://localhost:8080/job/arquillian-core/1/maven-repository/repository/
repos.each { repo ->
   println("Creating job for ${repo.name}")

   def custom_definition = config.get(repo.id, ["a":"b"]);
   def custom_profiles = custom_definition.get("profiles", [default_profile])
   def custom_downstream = custom_definition.get("downstream", [])
   def custom_jdk_install = custom_definition.get("jdk", default_jdk_install)
   def custom_maven_install = custom_definition.get("maven", default_maven_install)

   job(type: Maven) {
      name(repo.id)
      displayName(repo.name + " (generated)")
      jdk(custom_jdk_install)

      add_scm(it, repo)

      preBuildSteps {
         shell("""\
            echo > build_internal.properties
            echo Arquillian.Version=`mvn -nsu help:evaluate -Dexpression=project.version | grep 'maven-help' -A 5 | sed -n -e '/^\\[.*\\]/ !{ /^[0-9]/ { p; q } }'` >> build_internal.properties
            echo Arquillian.Repository=\${JOB_URL}\${BUILD_NUMBER}/maven-repository/repository/ >> build_internal.properties
            """)
         environmentVariables {
            propertiesFile("build_internal.properties")
            env("Jenkins.Repository", "\${Arquillian.Upstream.Repository}")
         }
      }

      parameters {
         stringParam("Arquillian.Maven.Additional", "", "Additional commandline arguments for the Maven job")
         stringParam("Arquillian.Upstream.Repository", "", "Additional Maven Repository")
      }

      mavenInstallation(custom_maven_install)
      localRepository(LocalToExecutor)

      goals(custom_profiles[0])
      goals("\${Arquillian.Maven.Additional}")
   }

   if(repo.id.equals("arquillian-core")) {

      job(type: BuildFlow) {
         name("flow." + repo.id)
         displayName(repo.name + " Flow (generated)")
         jdk(default_jdk_install)

         add_scm(it, repo)

         buildFlow("""
            def core = build('arquillian-core', 'sha1': params['sha1'])
            def snapshotRepo = core.environment.get('Arquillian.Repository')
            def snapshotVersion = core.environment.get('Arquillian.Version')

            downstreams = new groovy.json.JsonSlurper().parseText('${groovy.json.JsonOutput.toJson(custom_downstream)}')

            jobs=[]

            downstreams.collect { job ->
               def additional = '-D' + job.exp + '=' + snapshotVersion + ' '
               additional += '-Dupstream_repo -U'
               jobs << {
                  build(
                     job.id,
                     'Arquillian.Maven.Additional': additional,
                     'Arquillian.Upstream.Repository': snapshotRepo
                  )
               }
            }
            join = parallel(jobs)
         """)
      }

   } else {

      job(type: BuildFlow) {
         name("flow." + repo.id)
         displayName(repo.name + " Flow (generated)")
         jdk(default_jdk_install)

         add_scm(it, repo)

         buildFlow("""

            // Previous Core versions
            def core_versions = getLatestCoreVersions()

            def module = build('${repo.id}')
            def snapshotRepo = module.environment.get('Arquillian.Repository')
            def snapshotVersion = module.environment.get('Arquillian.Version')

            jobs=[]
            core_versions.collect { version ->
               def additional = '-Dversion.arquillian_core=' + version + ' '
               additional += '-Dversion.arquillian.core=' + version + ' '
               jobs << {
                  build(
                     '${repo.id}',
                     'Arquillian.Maven.Additional': additional,
                  )
               }
            }
            join = parallel(jobs)

            // Downstream jobs

            downstreams = new groovy.json.JsonSlurper().parseText('${groovy.json.JsonOutput.toJson(custom_downstream)}')
            downstream_jobs=[]
            downstreams.collect { job ->
               def additional = '-D' + job.exp + '=' + snapshotVersion + ' '
               additional += '-Dupstream_repo -U'
               downstream_jobs << {
                  build(
                     job.id,
                     'Arquillian.Maven.Additional': additional,
                     'Arquillian.Upstream.Repository': snapshotRepo
                  )
               }
            }
            downstream_join = parallel(downstream_jobs)

            def getLatestCoreVersions() {
               def core_metadata_url = new URL('https://repo1.maven.org/maven2/org/jboss/arquillian/arquillian-bom/maven-metadata.xml')
               def xml = new groovy.util.XmlSlurper().parse(core_metadata_url.newReader())

               versions = []
               xml.versioning.versions.version.each {
                  versions << it.text()
               }

               versions = versions.findAll {
                  return !it.contains('wildfly')
               }.reverse()

               return versions[0..2]
            }

         """)
      }
   }
/*
   view() {
      name("Entry")
      jobFilters {
         regex {
            ".*Flow.*"
         }
      }
   }
*/
}

/** Helpers for job setup **/

def add_scm(job, repo) {
   job.scm {
      git {
         remote {
            name("origin")
            github(repo.github, "git")
            refspec("+refs/heads/*:refs/remotes/origin/* +refs/pull/*:refs/remotes/origin/pr/*")
         }
         branch("\${sha1}")
      }
   }
   job.parameters {
      stringParam("sha1", "master", "sha1 or ref to build")
   }
}

/** Helpers for Repository information **/

def enrich(repos) {
   return repos.collect {
      return [
         url: it,
         id: urlToId(it),
         name: urlToName(it),
         github: urlToGithub(it)]
   }
}

def urlToId(url) {
   return url.substring(url.lastIndexOf("/")+1, url.size()-4)
}

def urlToGithub(url) {
   return url.substring(url.lastIndexOf("/", url.lastIndexOf("/")-1)+1, url.size()-4)
}

def urlToName(url) {
   return (urlToId(url).split('-')*.capitalize()).join(" ")
}


def getRepositories() {

   def repos = []
   def page =1
   def more_pages = true

   while(more_pages) {

      def repoUrl = new URL(
         "SOME_URL")
      def xml = new groovy.util.XmlSlurper().parse(repoUrl.newReader())

      xml.result.enlistment.each {
         repos << it.repository.url.text()
      }

      def offset = xml.first_item_position.toInteger()
      def returned = xml.items_returned.toInteger()
      def available = xml.items_available.toInteger()

      if(offset + returned < available) {
        page += 1
      } else {
        more_pages = false
      }
   }

   return repos
}