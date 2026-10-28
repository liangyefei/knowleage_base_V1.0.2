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
//    implementation(libs.quarkus.redis.client)
//    implementation(libs.quarkus.reactive.mysql.client)

    implementation(libs.mapstruct)

//    testImplementation(libs.rest.assured)
//    testImplementation(libs.quarkus.junit5)

    annotationProcessor(libs.mapstruct.processor)
}