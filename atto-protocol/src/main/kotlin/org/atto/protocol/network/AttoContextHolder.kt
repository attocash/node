package org.atto.protocol.network

//object AttoContextHolder {
//
//    private val context = object : ThreadLocal<ConcurrentHashMap<String, Any>>() {
//        override fun initialValue(): ConcurrentHashMap<String, Any> {
//            return ConcurrentHashMap()
//        }
//    }
//
//    fun put(key: String, o: Any) {
//        context.get()[key] = o
//    }
//
//    @Suppress("UNCHECKED_CAST")
//    fun <T> get(key: String): T? {
//        return context.get()[key] as T
//    }
//
//    fun clear() {
//        context.get().clear()
//    }
//}