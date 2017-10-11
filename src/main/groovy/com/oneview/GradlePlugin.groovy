package com.oneview

import com.amazonaws.auth.AWSCredentialsProviderChain
import com.amazonaws.auth.EC2ContainerCredentialsProviderWrapper
import com.amazonaws.auth.EnvironmentVariableCredentialsProvider
import com.amazonaws.auth.SystemPropertiesCredentialsProvider
import com.amazonaws.auth.profile.ProfileCredentialsProvider
import org.ajoberstar.grgit.Grgit
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.credentials.AwsCredentials
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.tasks.bundling.Jar

class GradlePlugin implements Plugin<Project> {

    @Override
    void apply(Project target) {

        def usingAws = true

        def awsCredentials
        try {
            awsCredentials = new AWSCredentialsProviderChain(
                    new EnvironmentVariableCredentialsProvider(),
                    new SystemPropertiesCredentialsProvider(),
                    new ProfileCredentialsProvider("ovc"),
                    new ProfileCredentialsProvider(),
                    new EC2ContainerCredentialsProviderWrapper()
            ).credentials
        } catch (ignored) {
            usingAws = false
        }

        def scmRepo = Grgit.open(currentDir: target.projectDir)
        target.version scmRepo.describe()

        target.ext {
            scmHash = scmRepo.head().getAbbreviatedId()
            scmBranch = scmRepo.branch.getCurrent().getName()
            isSnapshot = target.version.contains('-')
            if (scmBranch.startsWith('release-')) {
                repoSuffix = 'rcs'
            } else if (scmBranch.startsWith('hotfix-')) {
                repoSuffix = 'hotfixes'
            } else if (scmBranch.startsWith('sprint-redteam-master')) {
                repoSuffix = 'releases';
            } else if (isSnapshot) {
                repoSuffix = 'snapshots';
            } else{
                repoSuffix = 'releases';
            }
            depVersions = isSnapshot ? '+' : target.version

        }

        target.configure(target) {
            apply plugin: 'maven-publish'

            repositories {

                maven {
                    if (usingAws) {
                        url "s3://ovc-gradle-repo/releases"
                        credentials(AwsCredentials) {
                            accessKey awsCredentials.AWSAccessKeyId
                            secretKey awsCredentials.AWSSecretKey
                        }
                    } else {
                        url "https://s3.amazonaws.com/ovc-gradle-repo/releases"
                    }
                }

                if (isSnapshot) {
                    maven {
                        if (usingAws) {
                            url "s3://ovc-gradle-repo/$target.repoSuffix"
                            credentials(AwsCredentials) {
                                accessKey awsCredentials.AWSAccessKeyId
                                secretKey awsCredentials.AWSSecretKey
                            }
                        } else {
                            url "https://s3.amazonaws.com/ovc-gradle-repo/$target.repoSuffix"
                        }
                    }
                }
            }

            if (usingAws) {
                publishing {
                    repositories {
                        maven {
                            url "s3://ovc-gradle-repo/$target.repoSuffix"
                            credentials(AwsCredentials) {
                                accessKey awsCredentials.AWSAccessKeyId
                                secretKey awsCredentials.AWSSecretKey
                            }
                        }
                    }
                }
            }

            target.plugins.withType(JavaPlugin) {
                target.configure(target) {
                    tasks.withType(Jar) {
                        manifest {
                            attributes(
                                    'GitCommitSHA': scmHash,
                                    'GitBranch': scmBranch,
                                    'OVCVersion': target.version,
                                    'FullVersion': "${target.version}-$scmHash"
                            )
                        }
                    }
                }
            }

        }

        target.task('versionProperties') {

            inputs.property 'version', target.version
            inputs.property 'hash', target.scmHash
            inputs.property 'branch', target.scmBranch

            def propFile = new File("${target.buildDir}/tmp/release.properties");
            outputs.file target.file(propFile)

            doLast {
                propFile.parentFile.mkdirs()
                propFile.createNewFile();

                def prop = new Properties()

                prop.setProperty('ovc_version', target.version)
                prop.setProperty('git_commit_sha', target.scmHash)
                prop.setProperty('git_branch', target.scmBranch)
                prop.setProperty('full_version', "${target.version}-${target.scmHash}")

                prop.store(propFile.newWriter(), null);
            }
        }
    }
}
