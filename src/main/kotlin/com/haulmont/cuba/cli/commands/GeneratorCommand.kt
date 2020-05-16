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

package com.haulmont.cuba.cli.commands

import com.haulmont.cli.core.commands.CommandExecutionException
import com.haulmont.cli.core.commands.CommonParameters
import com.haulmont.cli.core.prompting.Answers
import com.haulmont.cli.core.prompting.Prompts
import com.haulmont.cli.core.prompting.QuestionsList

/**
 * Base command for all commands, that generate any artifact.
 *
 * GeneratorCommand has special lifecycle.
 *
 * Prompting [GeneratorCommand.prompting] is the first phase,
 * at which user is asked with questions about ahead generated artifact.
 *
 * After that, the command creates artifact model based on the prompting phase user answers and register it in the cliContext
 * by name retrieved from [getModelName].
 *
 * At generation phase, the command gets all available models as ```Map<String, Any>``` and generates artifact.
 */
abstract class GeneratorCommand<out Model : Any> : AbstractCubaCommand() {
    /**
     * Returns current model if it already generated. Otherwise, it will raise an exception,
     * so don't call it before [createModel] method.
     */
    protected val model: Model
        get() = run {
            if (context.hasModel(getModelName())) {
                context.getModel<Model>(getModelName())
            } else fail("Model has not yet been created")
        }

    override fun run() {
        val model = Prompts.create(kodein) { prompting() }
                .let(Prompts::ask)
                .let(this::createModel)

        context.addModel(getModelName(), model)

        beforeGeneration()

        val bindings = context.getModels().toMutableMap()
        bindings += ("gitignore" to ".gitignore")
        generate(bindings)
    }

    fun isNonInteractiveMode() = CommonParameters.nonInteractive.isNotEmpty()

    fun isInteractiveMode() = CommonParameters.nonInteractive.isEmpty()

    /**
     * Special method to make additional validations before generating artifact from model.
     * If generation is impossible for some reasons, call [fail] method.
     */
    @Throws(CommandExecutionException::class)
    open fun beforeGeneration() {

    }

    abstract fun getModelName(): String

    /**
     * Specifies question that will be asked to user.
     * User may provide answers in non-interactive mode by specifying answers as command options with syntax
     * ```
     * command -PquestionOne=answerOne -PquestionTwo=answerTwo ...
     * ```
     */
    abstract fun QuestionsList.prompting()

    abstract fun createModel(answers: Answers): Model

    abstract fun generate(bindings: Map<String, Any>)
}

/**
 * Unsafe get value from map with automatic type casting.
 */
@Suppress("UNCHECKED_CAST")
infix fun <V> String.from(map: Map<String, *>): V {
    return map[this] as V
}