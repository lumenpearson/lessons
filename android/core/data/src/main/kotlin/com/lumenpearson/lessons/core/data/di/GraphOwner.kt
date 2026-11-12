package com.lumenpearson.lessons.core.data.di

/** The application owns build constants that library entry points cannot read. */
interface GraphOwner {
    fun initializeLessonsGraph()
}
