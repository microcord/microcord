package com.discord.utilities.view.validators

import android.view.View
import androidx.annotation.MainThread

/**
 * Collects the [Input]s of one form and validates them together.
 *
 * Registration order matters: [validate] evaluates every field so all messages appear at once,
 * while [setErrors] walks the fields in reverse so that when several fields are rejected by the
 * server the focus request from the *first* field wins.
 */
class ValidationManager() {

    private val inputs = LinkedHashMap<FieldName, Input<out View>>()

    constructor(vararg inputs: Input<out View>) : this() {
        for (input in inputs) this.inputs[input.name] = input
    }

    @MainThread
    fun addInput(input: Input<out View>) {
        inputs[input.name] = input
    }

    @MainThread
    fun removeInput(name: FieldName) {
        inputs.remove(name)
    }

    /**
     * @param showErrors whether failures should also be rendered on the fields.
     * @return true when every registered field passes.
     */
    @MainThread
    fun validate(showErrors: Boolean = true): Boolean {
        var valid = true
        for (input in inputs.values) {
            valid = input.validate(showErrors) && valid
        }
        return valid
    }

    /**
     * Applies server-reported errors, keyed by field name.
     *
     * @return the error keys that did not match any registered field, so the caller can surface
     *   them somewhere else instead of silently dropping them.
     */
    @MainThread
    fun setErrors(errorMap: Map<FieldName, List<String>>): Collection<FieldName> {
        val handled = ArrayList<FieldName>()
        for (input in inputs.values.reversed()) {
            if (input.setErrorMessage(errorMap[input.name]?.firstOrNull())) {
                handled.add(input.name)
            }
        }
        return errorMap.keys.minus(handled.toSet())
    }
}
