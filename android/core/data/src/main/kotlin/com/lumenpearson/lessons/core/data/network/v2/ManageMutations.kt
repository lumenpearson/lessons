package com.lumenpearson.lessons.core.data.network.v2

import com.lumenpearson.lessons.core.data.network.v2.dto.SchoolClassDto
import com.lumenpearson.lessons.core.data.network.v2.dto.SubjectDto
import com.lumenpearson.lessons.core.data.repository.ClassEdit
import com.lumenpearson.lessons.core.data.repository.ClassJoinMode
import com.lumenpearson.lessons.core.data.repository.ManagedClass
import com.lumenpearson.lessons.core.data.repository.ManagedSubject
import com.lumenpearson.lessons.core.data.repository.SubjectForm

internal data class ClassMutation(val resource: SchoolClassDto, val paths: List<String>)
internal data class SubjectMutation(val resource: SubjectDto, val paths: List<String>)

internal sealed interface ManagementChange {
    data object Unchanged : ManagementChange
    data object Clear : ManagementChange
    data class Value(val value: String) : ManagementChange
}

internal fun changedManagementValue(before: String, after: String): String? =
    after.trim().takeIf { it != before.trim() }

internal fun optionalManagementChange(before: String?, after: String?): ManagementChange {
    val value = after.cleanManagementValue()
    return when {
        value == before.cleanManagementValue() -> ManagementChange.Unchanged
        value == null -> ManagementChange.Clear
        else -> ManagementChange.Value(value)
    }
}

internal fun ClassEdit.mutation(before: ManagedClass): ClassMutation {
    val mask = MutationMask()
    val resource = SchoolClassDto(
        name = mask.required("name", before.name, name),
        school = mask.optional("school", before.school, school),
        city = mask.optional("city", before.city, city),
        timezone = mask.required("timezone", before.timezone, timezone),
    )
    return ClassMutation(resource, mask.paths)
}

internal fun ClassJoinMode.mutation(): ClassMutation = ClassMutation(
    SchoolClassDto(joinMode = if (this == ClassJoinMode.INVITE) "JOIN_MODE_INVITE" else "JOIN_MODE_OPEN"),
    listOf("join_mode"),
)

internal fun SubjectForm.mutation(before: ManagedSubject): SubjectMutation {
    val mask = MutationMask()
    val resource = SubjectDto(
        id = managementId(before.id),
        name = mask.required("name", before.name, name),
        shortName = mask.optional("short_name", before.shortName, shortName),
        teacher = mask.optional("teacher", before.teacher, teacher),
        color = mask.optional("color", before.color, color),
    )
    return SubjectMutation(resource, mask.paths)
}

internal fun SubjectForm.resource(): SubjectDto = SubjectDto(
    name = name.trim(), shortName = shortName.cleanManagementValue(),
    teacher = teacher.cleanManagementValue(), color = color.cleanManagementValue(),
)

internal fun managementId(value: Long): Int {
    require(value in 1..Int.MAX_VALUE.toLong()) { "The resource id is outside the supported Int32 range" }
    return value.toInt()
}

internal fun List<String>.fieldMaskQuery(): String = joinToString(",") { field ->
    field.split('_').mapIndexed { index, part ->
        if (index == 0) part else part.replaceFirstChar { it.uppercaseChar() }
    }
        .joinToString("")
}

private class MutationMask {
    val paths = mutableListOf<String>()

    fun required(field: String, before: String, after: String): String {
        return changedManagementValue(before, after)?.also { paths += field }.orEmpty()
    }

    fun optional(field: String, before: String?, after: String?): String? {
        return when (val change = optionalManagementChange(before, after)) {
            ManagementChange.Unchanged -> null
            ManagementChange.Clear -> {
                paths += field
                null
            }
            is ManagementChange.Value -> change.value.also { paths += field }
        }
    }
}

private fun String?.cleanManagementValue(): String? = this?.trim()?.ifBlank { null }
