plugins {
    base
}

allprojects {
    group = "io.github.propagandist"

    // 版数はここだけを正とする。画面と CLI が出す版数もここから焼き込まれる（pdf-core の BuildInfo）。
    //
    // 無条件に代入してはならない。CI はタグから -Pversion=0.2.0 のように渡しており、
    // 代入で上書きすると、タグが何であろうとここに書いた版数のインストーラができる。
    if (version == Project.DEFAULT_VERSION) {
        version = "0.1.0-SNAPSHOT"
    }
}

subprojects {
    apply(plugin = "java-library")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
        }
    }
}
