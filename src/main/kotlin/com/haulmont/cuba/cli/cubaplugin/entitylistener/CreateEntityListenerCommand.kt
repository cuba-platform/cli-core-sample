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

package com.haulmont.cuba.cli.cubaplugin.entitylistener

import com.beust.jcommander.Parameters
import com.haulmont.cli.core.PrintHelper
import com.haulmont.cli.core.Resources
import com.haulmont.cuba.cli.commands.GeneratorCommand
import com.haulmont.cuba.cli.cubaplugin.di.cubaKodein
import com.haulmont.cuba.cli.cubaplugin.model.EntitySearch
import com.haulmont.cuba.cli.cubaplugin.model.ModuleStructure.Companion.CORE_MODULE
import com.haulmont.cuba.cli.cubaplugin.model.ModuleStructure.Companion.GLOBAL_MODULE
import com.haulmont.cuba.cli.generation.TemplateProcessor
import com.haulmont.cli.core.kodein
import com.haulmont.cli.core.prompting.Answers
import com.haulmont.cli.core.prompting.QuestionsList
import com.haulmont.cuba.cli.getTemplate
import org.kodein.di.Kodein
import org.kodein.di.generic.instance
import java.io.PrintWriter

@Parameters(commandDescription = "Creates new entity listener")
class CreateEntityListenerCommand(override val kodein: Kodein = cubaKodein) : GeneratorCommand<EntityListenerModel>() {
    private val printHelper: PrintHelper by kodein.instance<PrintHelper>()

    private val resources by Resources.fromMyPlugin()

    private val entitySearch: EntitySearch by cubaKodein.instance<EntitySearch>()

    private val printWriter: PrintWriter by cubaKodein.instance<PrintWriter>()

    override fun getModelName(): String = EntityListenerModel.MODEL_NAME

    override fun preExecute() = checkProjectExistence()

    override fun QuestionsList.prompting() {
        val entitiesList = entitySearch.getAllEntities()
                .filter { !it.embeddable }
                .map { it.fqn }

        if (entitiesList.isEmpty()) {
            printWriter.println("Project does not have any suitable entities.")
            abort()
        }

        question("className", "Listener name") {
            validate {
                checkIsClass()
            }
        }
        textOptions("entityType", "Select entity", entitiesList)
        question("packageName", "Listener package") {
            default(projectModel.rootPackage + ".listener")
            validate {
                checkIsPackage()
            }
        }
        question("beanName", "Bean name") {
            default { projectModel.namespace + "_" + it["className"] }
        }

        questionList("interfaces") {
            val interfaces = listOf("beforeInsert", "beforeUpdate", "beforeDelete",
                    "afterInsert", "afterUpdate", "afterDelete",
                    "beforeAttach", "beforeDetach")

            interfaces.forEach {
                confirmation(it, "Implement ${it.capitalize()}EntityListener?") {
                    default(true)
                }
            }

            validate {
                if (interfaces.none { value[it] as Boolean }) {
                    fail("Listener must implement at least one of the interfaces")
                }
            }
        }
    }

    override fun createModel(answers: Answers): EntityListenerModel = EntityListenerModel(answers)

    override fun beforeGeneration() {
        projectStructure.getModule(CORE_MODULE)
                .resolvePackagePath(model.packageName)
                .resolve(model.className + ".java")
                .let {
                    ensureFileAbsence(it, "Entity listener \"${model.packageName}.${model.className}\" already exists")
                }
    }

    override fun generate(bindings: Map<String, Any>) {
        TemplateProcessor(resources.getTemplate("entityListener"), bindings) {
            transformWhole()
        }

        registerListener()
    }

    private fun registerListener() {
        val entityPath = projectStructure.getModule(GLOBAL_MODULE)
                .resolvePackagePath(model.entityPackageName)
                .resolve(model.entityName + ".java")

        val entityLines = entityPath.toFile()
                .readLines()
                .toMutableList()

//        Add import if need
        val importListenerRegex = Regex("import +com\\.haulmont\\.cuba\\.core\\.entity\\.annotation\\.Listeners; *")

        val hasListenerImport = entityLines.find { it.matches(importListenerRegex) } != null

        if (!hasListenerImport) {
            entityLines.add(2, "import com.haulmont.cuba.core.entity.annotation.Listeners;")
        }

//        Create or alter Listeners annotation
        val listenersRegex = Regex("@Listeners\\((value *= *)?\\{?([\". a-zA-Z0-9_,]+)*}?\\)")
        val classRegex = Regex(".* +class +.*")

        val listeners: MutableList<String> = mutableListOf()
        var annotationIndex = -1
        var classIndex = -1
        entityLines.forEachIndexed { index, line ->
            listenersRegex.find(line)?.let {
                listeners += it.groupValues[2]
                        .replace("\"", " ")
                        .split(",")
                        .map { it.trim() }
                annotationIndex = index
            }
            if (line.matches(classRegex)) {
                classIndex = index
            }
        }

        listeners += model.beanName

        val annotationString = "@Listeners({${listeners.joinToString { "\"$it\"" }}})"

        if (annotationIndex != -1) {
            entityLines.removeAt(annotationIndex)
            entityLines.add(annotationIndex, annotationString)
        } else {
            entityLines.add(classIndex, annotationString)
        }

        entityPath.toFile().bufferedWriter().use {
            entityLines.forEach { line ->
                it.write(line)
                it.write("\n")
            }
        }

        printHelper.fileModified(entityPath)
    }
}

