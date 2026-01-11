package com.brain.tracscript.core

// Событие в шине
data class DataBusEvent(
    val type: String,                  // тип события, например "wialon_table_json"
    val payload: Map<String, Any?>,    // произвольные данные
    val timestamp: Long = System.currentTimeMillis()
)

// Подписка (нужно, чтобы уметь отписаться)
interface Subscription {
    fun unsubscribe()
}

// Интерфейс шины
interface DataBus {
    fun post(event: DataBusEvent)
    fun subscribe(subscriber: (DataBusEvent) -> Unit): Subscription
}

// Простая реализация для одного процесса/потока
class SimpleDataBus : DataBus {

    private val subscribers = mutableListOf<(DataBusEvent) -> Unit>()

    @Synchronized
    override fun post(event: DataBusEvent) {
        // копия списка, чтобы не ловить ConcurrentModification
        val snapshot = subscribers.toList()
        snapshot.forEach { it(event) }
    }

    @Synchronized
    override fun subscribe(subscriber: (DataBusEvent) -> Unit): Subscription {
        subscribers += subscriber
        return object : Subscription {
            override fun unsubscribe() {
                synchronized(this@SimpleDataBus) {
                    subscribers.remove(subscriber)
                }
            }
        }
    }
}
