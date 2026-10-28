plugins {
    java
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}

tasks.withType<Javadoc> {
    options.encoding = "UTF-8"
}

tasks.withType<ProcessResources> {
    filteringCharset = "UTF-8"
}

tasks.withType<Test> {
    systemProperty("file.encoding", "UTF-8")
}

// 全局排除 Jakarta Mail 实现（angus-activation / jakarta.mail / com.sun.mail）：
// angus-activation 自带 GraalVM Feature（AngusActivationFeature），native 分析期反射
// jakarta.mail.Part（optional 依赖不在 classpath）→ native 构建崩溃（NoClassDefFoundError）。
// 它经 tika-parser-mail / jaxb-core 多条路径传递引入，须在所有模块统一排除；
// 本项目不解析邮件类文档，无功能影响。
configurations.configureEach {
    exclude(group = "org.eclipse.angus")
    exclude(group = "com.sun.mail")
    exclude(group = "jakarta.mail")
}
