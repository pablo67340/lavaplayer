plugins {
  java
  `maven-publish`
}

group = "com.sedmelluq"

allprojects {
  group = rootProject.group

  repositories {
    mavenCentral()
    mavenLocal()
    maven(url = "https://m2.dv8tion.net/releases")
    maven(url = "https://jitpack.io")
  }

  apply(plugin = "java")
  apply(plugin = "maven-publish")

  java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
  }

  publishing {
    repositories {
      maven {
        setUrl("s3://m2.dv8tion.net/releases")
        credentials(AwsCredentials::class) {
          accessKey = project.findProperty("sedmelluqMavenS3AccessKey")?.toString()
          secretKey = project.findProperty("sedmelluqMavenS3SecretKey")?.toString()
        }
      }
    }
  }
}
