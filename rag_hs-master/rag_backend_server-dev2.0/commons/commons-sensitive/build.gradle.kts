plugins {
    id("java-common-conventions")
}

dependencies {
    // 敏感词 DFA 匹配（hutool-dfa 的 WordTree：纯 HashMap 嵌套实现，无反射/动态代理，native 安全）
    implementation(libs.hutool.dfa)
    // 响应式端口接口（Uni）
    implementation(libs.quarkus.arc)

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
