// **********************************************************************
//
// Copyright (c) 2014-2016 ZeroC, Inc. All rights reserved.
//
// **********************************************************************

package com.zeroc.gradle.icebuilder.slice;

import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.UnknownTaskException

class SlicePlugin implements Plugin<Project> {
    void apply(Project project) {
        project.task('compileSlice', type: SliceTask) {
            group = "Slice"
        }

        // Create and install the extension object.
        project.extensions.create("slice", SliceExtension, project.container(Java))

        project.extensions.slice.extensions.create("freezej", Freezej,
             project.container(Dict), project.container(Index))

        project.slice.output = project.file("${project.buildDir}/generated-src")

        try {
            project.getTasks().getByName("compileJava").dependsOn('compileSlice');
            project.sourceSets.main.java.srcDir project.slice.output
        } catch(UnknownTaskException ex)  {
            // Using an exception to add android support isn't very nice
            // but I couldn't find another way to find out whether a task
            // exists.
            //
            // Android support.
            //
            // WORKAROUND
            //
            // This is a horrible hack, but necessary to work with the android plugin.
            // The problem is that the compileDebugJava task isn't available at this point,
            // and using project.getGradle().projectsEvaluated({ to add the dependency on the
            // compileDebugJava/compileReleaseJava task doesn't work in android studio as of
            // 0.8.9.
            try {
                project.getTasks().getByName("preBuild").dependsOn('compileSlice');
                // This doesn't work either.
                //project.sourceSets.main.java.srcDir project.slice.output
            } catch(UnknownTaskException ex2)  {
            }
        }
    }
}
