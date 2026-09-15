package org.olcbox.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ConnectionSelection {
    @SerialName("manual") Manual,
    @SerialName("lowest") Lowest,
    @SerialName("balanced") Balanced;

    fun label(): String = when (this) {
        Manual -> "Manual"
        Lowest -> "Lowest channel latency"
        Balanced -> "Balanced connections"
    }
}
