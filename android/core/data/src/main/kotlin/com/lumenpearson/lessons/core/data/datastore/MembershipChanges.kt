package com.lumenpearson.lessons.core.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import java.util.WeakHashMap
import kotlinx.coroutines.sync.Mutex

/** Separate preference facades over the same file must serialize the same membership/cache changes. */
internal object MembershipChanges {
    private val stores = WeakHashMap<DataStore<Preferences>, Mutex>()

    fun forStore(store: DataStore<Preferences>): Mutex = synchronized(stores) {
        stores.getOrPut(store) { Mutex() }
    }
}
