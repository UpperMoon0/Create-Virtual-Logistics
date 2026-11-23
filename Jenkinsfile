pipeline {
    agent { label 'linux && general' }
    options { timestamps(); disableConcurrentBuilds(); timeout(time: 45, unit: 'MINUTES') }
    environment { JAVA_HOME = '/usr/lib/jvm/java-17-openjdk-amd64' }
    stages {
        stage('Java core + coverage') {
            steps { sh 'chmod +x gradlew && ./gradlew :core:check --no-daemon --console=plain --max-workers=2' }
            post {
                always {
                    junit allowEmptyResults: true, testResults: 'core/build/test-results/test/*.xml'
                    archiveArtifacts artifacts: 'core/build/reports/jacoco/test/**,core/build/reports/tests/test/**', allowEmptyArchive: true
                }
            }
        }
        stage('Forge 1.20.1 build') {
            steps { sh './gradlew :forge:build --no-daemon --console=plain --max-workers=2' }
            post { success { archiveArtifacts artifacts: 'forge/build/libs/*.jar', fingerprint: true } }
        }
    }
}
