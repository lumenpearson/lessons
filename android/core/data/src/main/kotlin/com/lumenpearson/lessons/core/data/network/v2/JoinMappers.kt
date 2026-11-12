package com.lumenpearson.lessons.core.data.network.v2

import com.lumenpearson.lessons.contract.v2.CreateDeviceResponse
import com.lumenpearson.lessons.core.data.network.v2.dto.CreateDeviceResponseDto
import com.lumenpearson.lessons.core.data.network.v2.dto.DiaryBindingDto
import com.lumenpearson.lessons.core.data.repository.DiaryBinding
import com.lumenpearson.lessons.core.data.repository.DiaryProviderKey
import com.lumenpearson.lessons.core.data.repository.Session

internal fun CreateDeviceResponseDto.toSession(status: Int = 200): Session {
    if (classId <= 0 || token.isBlank()) throw malformedV2Payload("The join response has no class or token", status)
    return Session(classId.toLong(), className, school.cleanOptional(), token, diary?.domainBinding())
}

internal fun CreateDeviceResponse.toSession(): Session = CreateDeviceResponseDto(
    token = token, classId = classId, className = className, school = school.takeIf { hasSchool() },
    diary = diary.takeIf { hasDiary() }?.let { binding ->
        DiaryBindingDto(binding.provider, binding.region.takeIf { binding.hasRegion() },
            binding.schoolId.takeIf { binding.hasSchoolId() }, binding.schoolName.takeIf { binding.hasSchoolName() })
    },
).toSession()

private fun DiaryBindingDto.domainBinding(): DiaryBinding? = DiaryProviderKey.fromWire(provider)?.let {
    DiaryBinding(it, region.cleanOptional(), schoolId, schoolName.cleanOptional())
}

internal fun String?.cleanOptional(): String? = this?.trim()?.ifBlank { null }
