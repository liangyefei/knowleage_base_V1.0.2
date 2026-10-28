plugins {
    id("java-common-conventions")
}

dependencies {
    implementation(libs.quarkus.hibernate.reactive)
    implementation(libs.quarkus.hibernate.reactive.panache)
}
