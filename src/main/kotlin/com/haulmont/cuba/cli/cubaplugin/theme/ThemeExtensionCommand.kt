/*
 * Copyright (c) 2008-2018 Haulmont.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.haulmont.cuba.cli.cubaplugin.theme

import com.beust.jcommander.Parameters
import com.haulmont.cuba.cli.core.*
import com.haulmont.cuba.cli.cubaplugin.model.ModuleStructure.Companion.WEB_MODULE
import com.haulmont.cuba.cli.commands.GeneratorCommand
import com.haulmont.cuba.cli.commands.from
import com.haulmont.cuba.cli.generation.Snippets
import com.haulmont.cuba.cli.generation.TemplateProcessor
import com.haulmont.cuba.cli.cubaplugin.model.SpecificVersion
import com.haulmont.cuba.cli.core.prompting.Answers
import com.haulmont.cuba.cli.core.prompting.QuestionsList
import com.haulmont.cuba.cli.getTemplate
import org.kodein.di.generic.instance
import java.io.PrintWriter
import java.nio.file.Files
import java.util.stream.Collectors

@Parameters(commandDescription = "Generates theme extension")
class ThemeExtensionCommand : GeneratorCommand<ThemeExtensionModel>() {
    private val messages by localMessages()

    private val resources by Resources.fromMyPlugin()

    private val snippets: Snippets by lazy {
        Snippets(resources, "theme")
    }

    private val writer: PrintWriter by kodein.instance<PrintWriter>()

    private val printHelper: PrintHelper by kodein.instance<PrintHelper>()

    private val themesToExtend: List<String> by lazy {
        val themesDirectory = projectStructure.getModule(WEB_MODULE).path.resolve("themes")

        val alreadyExtended = if (Files.exists(themesDirectory)) {
            Files.walk(themesDirectory, 1)
                    .filter { it != themesDirectory && Files.isDirectory(it) }
                    .map { it.fileName.toString() }
                    .collect(Collectors.toList())
        } else listOf<String>()


        val restThemes: MutableList<String> = mutableListOf("halo")
        restThemes.removeAll(alreadyExtended)

        if(projectModel.platformVersion >= SpecificVersion(6, 10)) {
            restThemes.add("hover")
        }

        restThemes.isNotEmpty() || fail("Halo and havana themes already extended")

        return@lazy restThemes
    }

    override fun preExecute() {
        checkProjectExistence()
    }

    override fun getModelName(): String = ThemeExtensionModel.MODEL_NAME

    override fun QuestionsList.prompting() {
        if (themesToExtend.size > 1) {
            textOptions("themeName", "Choose theme to extend", themesToExtend)
        } else {
            writer.println("Only ${themesToExtend.first()} theme can be extended.")
        }

        confirmation("confirmed", messages["confirmationMessage"])
    }

    override fun createModel(answers: Answers): ThemeExtensionModel {
        val confirmed = answers["confirmed"] as Boolean

        confirmed || fail("User rejected", silent = true)

        if (themesToExtend.size == 1) {
            return ThemeExtensionModel(themesToExtend.first())
        }
        return ThemeExtensionModel("themeName" from answers)
    }

    override fun generate(bindings: Map<String, Any>) {
        val targetDirectory = projectStructure.getModule(WEB_MODULE).path
                .resolve("themes", model.themeName)

        TemplateProcessor(resources.getTemplate("themes/" + model.themeName), bindings) {
            transform("styles.scss", to = targetDirectory)
            transform("\${project.rootPackage}", to = targetDirectory)
            copy("favicon.ico", to = targetDirectory)
            copy("branding", to = targetDirectory)
        }

        val moduleRegistered = projectStructure.settingsGradle
                .toFile()
                .readLines()
                .contains(snippets["moduleRegistration"])

        if (!moduleRegistered) {
            registerModule()
        }
    }

    private fun registerModule() {
        val settingsGradle = projectStructure.settingsGradle

        val lines = settingsGradle.toFile().readLines().map {
            if (it.startsWith("include(")) {
                it.replace(Regex("include\\((.*)\\)")) {
                    val modules = it.groupValues[1].split(",")
                    (modules + "\":\${modulePrefix}-web-themes\"").joinToString(" ,", "include(", ")")
                }
            } else it
        } + snippets["moduleRegistration"]

        settingsGradle.toFile().writeText(lines.joinToString("\n"))

        printHelper.fileModified(settingsGradle)


        val buildGradle = projectStructure.buildGradle
        buildGradle.toFile()
                .readText()
                .replace(snippets["webModuleSearch"],
                        snippets["webModuleReplace"])
                .replace(
                        snippets["configureWebModuleSearch"],
                        snippets["configureWebModuleReplace"])
                .let {
                    buildGradle.toFile().writeText(it)
                }
        printHelper.fileModified(buildGradle)
    }
}