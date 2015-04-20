tomcatHost = 'localhost'
tomcatPort = '8180'
appHost = "http://${tomcatHost}:${tomcatPort}"
tomcatUser = 'admin'
tomcatPassword = 'tomcat'
tomcatDeployUrl = "http://${tomcatUser}:${tomcatPassword}@${tomcatHost}:${tomcatPort}/manager/deploy"
tomcatUndeployUrl = "http://${tomcatUser}:${tomcatPassword}@${tomcatHost}:${tomcatPort}/manager/undeploy"
artifactName='webapp.war'

node('linux-rhel-66') {
   git url: 'https://github.com/jenkinsbyexample/workflow-plugin-pipeline-demo.git'
   devQAStaging()
}
production()

def devQAStaging() {
    stage 'Build'
    sh 'mvn clean package'
    archive "target/${artifactName}"

    stage 'Code Coverage'
    echo 'Using Sonar for code coverage'

    stage 'QA'

    parallel(longerTests: {
        runWithServer {url ->
            sh "mvn -f sometests/pom.xml test -Durl=${url} -Dduration=30"
        }
    }, quickerTests: {
        runWithServer {url ->
            sh "mvn -f sometests/pom.xml test -Durl=${url} -Dduration=20"
        }
    })

    try {
        checkpoint('Before Staging')
    } catch (NoSuchMethodError _) {
        echo 'Checkpoint feature available in Jenkins Enterprise by CloudBees.'
    }

    stage name: 'Staging', concurrency: 1
    deploy "target/${artifactName}", 'staging'
    smokeTest 'staging'
    echo "Deployed to ${appHost}/staging/"
    undeploy 'staging'
}

def production() {
    try {
        checkpoint('Before production')
    } catch (NoSuchMethodError _) {
        echo 'Checkpoint feature available in Jenkins Enterprise by CloudBees.'
    }
   
    stage name: 'Production', concurrency: 1
    node('master') {        
        sh "curl -I ${appHost}/staging/"
        unarchive mapping: ['target/webapp.war' : 'webapp.war']
        deploy "${artifactName}", 'production'
        smokeTest 'production'
        echo "Deployed to ${appHost}/production/"
        sleep 10
        undeploy 'production'
    }
}

def deploy(war, id) {
    sh "curl --upload-file ${war} '${tomcatDeployUrl}?path=/${id}&update=true'"
}

def undeploy(id) {
    sh "curl '${tomcatUndeployUrl}?path=/${id}'"
}

def runWithServer(body) {
    def id = UUID.randomUUID().toString()
    deploy "target/${artifactName}", id
    try {
        body.call "${appHost}/${id}/"
    } finally {
        undeploy id
    }
}

def smokeTest(id) {
    sh "curl --write-out %{http_code} --silent -iL --output /dev/null http://web.cloudbees.vlan:8180/${id} > result"
    def result = readFile('result')
    echo "${result}"
    if ( result > 200 ) {
       error "Smoke test failed with error code [${result}]"
    }
}
