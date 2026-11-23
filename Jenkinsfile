pipeline {
    agent { label 'ci-role-general && ci-os-linux && ci-size-4g' }
    options { timestamps(); disableConcurrentBuilds(); timeout(time: 45, unit: 'MINUTES') }
    environment { JAVA_HOME = '/usr/lib/jvm/java-17-openjdk-amd64' }
    parameters {
        booleanParam(name: 'RUN_NATIVE_GAMETESTS', defaultValue: true,
            description: 'Required for release acceptance: isolate Forge GameTests on qualified >=4 GiB Linux general worker.')
    }
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
        stage('Native Forge GameTests (release gate)') {
            when { expression { return params.RUN_NATIVE_GAMETESTS } }
            steps { sh './gradlew :forge:runGameTestServer --no-daemon --console=plain --max-workers=1' }
            post { always { archiveArtifacts artifacts: 'forge/run-gametest/logs/*.log', allowEmptyArchive: true } }
        }
    }
    post {
        always {
            archiveArtifacts artifacts: 'core/build/reports/jacoco/test/**,core/build/test-results/test/*.xml,forge/build/libs/*.jar', allowEmptyArchive: true, fingerprint: true
        }
    }
}
