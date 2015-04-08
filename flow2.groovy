tomcatHost = 'localhost'
tomcatPort = '8180'
appHost = "http://${tomcatHost}:${tomcatPort}"
tomcatUser = 'admin'
tomcatPassword = 'tomcat'
tomcatDeployUrl = "http://${tomcatUser}:${tomcatPassword}@${tomcatHost}:${tomcatPort}/manager/deploy"
tomcatUndeployUrl = "http://${tomcatUser}:${tomcatPassword}@${tomcatHost}:${tomcatPort}/manager/undeploy"

node('slave') {
    env.PATH="${tool 'Maven 3.x'}/bin:${env.PATH}"
    stage 'Dev'
    sh 'mvn clean package'
    archive 'target/x.war'

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
    stage name: 'Staging', concurrency: 1
    deploy 'target/x.war', 'staging'
}

input message: "Does ${appHost}/staging/ look good?"
try {
    checkpoint('Before production')
} catch (NoSuchMethodError _) {
    echo 'Checkpoint feature available in Jenkins Enterprise by CloudBees.'
}
stage name: 'Production', concurrency: 1
node('master') {
    sh "curl -I ${appHost}/staging/"
    unarchive mapping: ['target/x.war' : 'x.war']
    deploy 'x.war', 'production'
    echo "Deployed to ${appHost}/production/"
}

def deploy(war, id) {
    sh "curl --upload-file ${war} '${tomcatDeployUrl}?path=/${id}&update=true'"
}

def undeploy(id) {
    sh "curl '${tomcatUndeployUrl}?path=/${id}'"
}

def runWithServer(body) {
    def id = UUID.randomUUID().toString()
    deploy 'target/x.war', id
    try {
        body.call "${appHost}/${id}/"
    } finally {
        undeploy id
    }
}
