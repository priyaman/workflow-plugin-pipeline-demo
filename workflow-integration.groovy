tomcatHost = 'localhost'
tomcatPort = '8180'
appHost = "http://${tomcatHost}:${tomcatPort}"
tomcatUser = 'admin'
tomcatPassword = 'tomcat'
tomcatDeployUrl = "http://${tomcatUser}:${tomcatPassword}@${tomcatHost}:${tomcatPort}/manager/deploy"
tomcatUndeployUrl = "http://${tomcatUser}:${tomcatPassword}@${tomcatHost}:${tomcatPort}/manager/undeploy"
artifactName='webapp.war'

// If JAVA_HOME is not set, this is an easy way to setup JAVA_HOME from within the workflow
// Comment this out if JAVA_HOME is already set
env.JAVA_HOME='/usr/lib/jvm/java-openjdk'
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

    // When integrating with external application, before pausing the workflow, send all the contextual data
    // such that the external application can trigger the resume event.
    // Contextual data should include, URL (as shown below), user data (for authentication and authorization), form data (if workflow requires additional data to continue)
    echo "Build URL is ${env.BUILD_URL}input/ApprovalAppnameDeployment/submit"

    // Email a link to approvers. 
    // When integrating with external applications, this is where you'd pass the necessary data to be able to resume the job.
    // mail body: "Please go to ${env.BUILD_URL}input/", from: 'uaarkoti@cloudbees.com', replyTo: 'uday@cloudbees.com', subject: "Job ${env.JOB_NAME} build #${env.BUILD_NUMBER} is waiting for your approval", to: 'uaarkoti@cloudbees.com'

    // Pause the workflow. You can resume this workflow from with in Jenkins or from an external application using HTTP REST API call.
    // Sample CURL command to resume with form data: 
    //    curl --data "name=comments&value=Approved&json=%7B%22parameter%22%3A+%7B%22name%22%3A+%22comments%22%2C+%22value%22%3A+%22Approved%22%7D%7D&proceed=Approve" http://uday:uday@localhost:8081/job/workflow-integration/16/input/ApprovalAppnameDeployment/submit
    //    NOTE: json parameter (with encoded url) is only required if the workflow expects input to continue, which is the case with the below input step
    // Sample CURL command to resume without form data
    //    curl -X POST http://uday:uday@localhost:8081/job/workflow-integration/16/input/ApprovalAppnameDeployment/proceedEmpty
    // Sample CURL command to abort without form data
    //    curl -X POST http://uday:uday@localhost:8081/job/workflow-integration/16/input/ApprovalAppnameDeployment/abort
    input id: 'ApprovalAppnameDeployment', message: 'Please approve as appropriate', ok: 'Approve', parameters: [[$class: 'StringParameterDefinition', defaultValue: 'Approved', description: '', name: 'comments']]
    try {
        checkpoint('Before production')
    } catch (NoSuchMethodError _) {
        echo 'Checkpoint feature available in Jenkins Enterprise by CloudBees.'
    }
    stage name: 'Production', concurrency: 1
    node('master') {
        sh "curl -I ${appHost}/staging/"
        // Parameters in an array doesn't seem to work. Throws java.lang.ClassCastException: org.codehaus.groovy.runtime.GStringImpl cannot be cast to java.lang.String
        //unarchive mapping: ["target/${artifactName}" : "${artifactName}"]
        unarchive mapping: ['target/webapp.war' : 'webapp.war']
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
