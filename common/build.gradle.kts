repositories { mavenCentral() }
dependencies {
    implementation("com.google.code.gson:gson:2.13.2")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.xerial:sqlite-jdbc:3.53.0.0")
}
tasks.test { useJUnitPlatform() }
