plugins {
    id("java-common-conventions")
}

dependencies {
    // JPA + Panache（仅编译 API，运行时由 Quarkus 容器提供）
    implementation(libs.quarkus.hibernate.reactive.panache)
    // Redis 客户端（cache-aside：字典 key 白名单缓存）
    implementation(libs.quarkus.redis.client)
    // Jackson（缓存 value 序列化）
    implementation(libs.quarkus.rest.jackson)
    // CDI（@ApplicationScoped）
    implementation(libs.quarkus.arc)
}
