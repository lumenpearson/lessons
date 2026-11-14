package com.lumenpearson.lessons.core.data.network.v2

import com.lumenpearson.lessons.core.data.network.v2.dto.ListSchoolsResponseDto
import com.lumenpearson.lessons.core.data.network.v2.dto.SchoolDto
import com.lumenpearson.lessons.core.data.repository.School
import com.lumenpearson.lessons.core.data.repository.SchoolPage

internal fun SchoolDto.toManagedSchool() = School(
    name, fullName.ifBlank { name }, ogrn, address, city, region, active,
)

internal fun ListSchoolsResponseDto.toManagedSchools() = SchoolPage(
    schools.filter { it.name.isNotBlank() }.map { it.toManagedSchool() },
    page = 1, pages = 1, total = totalSize, truncated = truncated,
)
