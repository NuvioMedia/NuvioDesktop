/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.api.Project

fun MavenPublishBaseExtension.signAllPublicationsIfEnabled(project: Project) {
    if (project.getPropertyOrNull("mediamp.sign.publications.disabled")?.toBoolean() == true) return
    signAllPublications()
}

fun MavenPublishBaseExtension.configurePom(project: Project) {
    pom {
        name.set(project.name)
        description.set(project.description)
        url.set("https://github.com/open-ani/mediamp")

        licenses {
            license {
                name.set("GNU General Public License, Version 3")
                url.set("https://github.com/open-ani/mediamp/blob/main/LICENSE")
                distribution.set("https://www.gnu.org/licenses/gpl-3.0.txt")
            }
        }

        developers {
            developer {
                id.set("openani")
                name.set("OpenAni and contributors")
                email.set("support@openani.org")
            }
        }

        scm {
            connection.set("scm:git:https://github.com/open-ani/mediamp.git")
            developerConnection.set("scm:git:git@github.com:open-ani/mediamp.git")
            url.set("https://github.com/open-ani/mediamp")
        }
    }
}