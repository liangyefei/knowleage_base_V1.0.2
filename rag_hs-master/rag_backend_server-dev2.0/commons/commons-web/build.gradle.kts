plugins {
    id("java-common-conventions")
}

dependencies {
    implementation(libs.quarkus.rest)
//    implementation(libs.quarkus.hibernate.reactive.panache)
    implementation(libs.quarkus.hibernate.validator)
    implementation(libs.quarkus.mapstruct)
    implementation(libs.quarkus.arc)
    implementation(libs.quarkus.rest.jackson)
    implementation(libs.quarkus.redis.client)
//    implementation(libs.quarkus.reactive.mysql.client)

    implementation(libs.mapstruct)
    implementation(libs.bcpkix.jdk18on)
    implementation(libs.owasp.encoder)

    implementation(project(":commons:commons-errorcode"))
    implementation(project(":commons:commons-i18n"))

//    testImplementation(libs.rest.assured)
//    testImplementation(libs.quarkus.junit5)
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation(libs.quarkus.junit5.mockito)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")

    annotationProcessor(libs.mapstruct.processor)
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}