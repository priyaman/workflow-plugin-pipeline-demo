tomcatHost = 'localhost'
tomcatPort = '8180'
appHost = "http://${tomcatHost}:${tomcatPort}"
tomcatUser = 'admin'
tomcatPassword = 'tomcat'
tomcatDeployUrl = "http://${tomcatUser}:${tomcatPassword}@${tomcatHost}:${tomcatPort}/manager/deploy"
tomcatUndeployUrl = "http://${tomcatUser}:${tomcatPassword}@${tomcatHost}:${tomcatPort}/manager/undeploy"
artifactName='webapp.war'

node('master') {
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
}

def production() {
    input message: "Does ${appHost}/staging/ look good?"
    try {
        checkpoint('Before production')
    } catch (NoSuchMethodError _) {
        echo 'Checkpoint feature available in Jenkins Enterprise by CloudBees.'
    }
    stage name: 'Production', concurrency: 1
    node('master') {
        sh "curl -I ${appHost}/staging/"
        // Parameters in an array doesn't seem to work. Throws java.lang.ClassCastException: org.codehaus.groovy.runtime.GStringImpl cannot be cast to java.lang.String
        //unarchive mapping: ['target/webapp.war' : 'webapp.war']
        unarchive mapping: ["target/${artifactName}" : "${artifactName}"]
        deploy "${artifactName}", 'production'
        echo "Deployed to ${appHost}/production/"
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
