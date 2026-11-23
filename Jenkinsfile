pipeline {
    agent { label 'ci-role-general && ci-os-linux && ci-size-4g' }
    options { timestamps(); disableConcurrentBuilds(); timeout(time: 45, unit: 'MINUTES') }
    parameters {
        booleanParam(name: 'RUN_NATIVE_GAMETESTS', defaultValue: true,
            description: 'Required for release acceptance: isolate Forge GameTests on qualified >=4 GiB Linux general worker.')
    }
    stages {
        stage('Verify Java 17 toolchain') {
            steps {
                script {
                    def jdk17 = sh(script: '''set -eu
                        for jdk in /home/jenkins/agent/toolcache/mcp-native-jdks/jdk-17 /usr/lib/jvm/java-17-openjdk-amd64 /opt/java/jdk-17; do
                            if [ -x "$jdk/bin/javac" ] && "$jdk/bin/java" -version 2>&1 | grep -q 'version "17'; then
                                printf '%s' "$jdk"
                                exit 0
                            fi
                        done
                        echo 'Java 17 JDK missing on qualified Linux worker' >&2
                        exit 1
                    ''', returnStdout: true).trim()
                    env.JAVA_HOME = jdk17
                    sh '"$JAVA_HOME/bin/java" -version && "$JAVA_HOME/bin/javac" -version'
                }
            }
        }
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
            steps { sh './gradlew -PnativeCoverage=true :forge:runGameTestServer --no-daemon --console=plain --max-workers=1' }
            post { always { archiveArtifacts artifacts: 'forge/run-gametest/logs/*.log', allowEmptyArchive: true } }
        }
        stage('Fluid Logistics 1.3.2 native coexistence') {
            when { expression { return params.RUN_NATIVE_GAMETESTS } }
            steps { sh './gradlew -PnativeCoverage=true -PwithFluidLogistics=true :forge:runGameTestServer --no-daemon --console=plain --max-workers=1' }
            post { always { archiveArtifacts artifacts: 'forge/run-gametest-fluid/logs/*.log', allowEmptyArchive: true } }
        }
        stage('Forge adapter native coverage') {
            when { expression { return params.RUN_NATIVE_GAMETESTS } }
            steps { sh './gradlew :forge:nativeForgeCoverageReport --no-daemon --console=plain --max-workers=1' }
            post { always { archiveArtifacts artifacts: 'forge/build/reports/jacoco/nativeForgeCoverageReport/**,forge/build/jacoco/forge-*.exec', allowEmptyArchive: true } }
        }
    }
    post {
        always {
            archiveArtifacts artifacts: 'core/build/reports/jacoco/test/**,core/build/test-results/test/*.xml,forge/build/libs/*.jar', allowEmptyArchive: true, fingerprint: true
        }
    }
}
