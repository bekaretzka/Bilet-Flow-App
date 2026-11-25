package com.example.biletflow.presentation.events

import androidx.compose.runtime.*
import androidx.compose.material3.Text
import biletflow.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import com.example.biletflow.domain.repositories.EventsRepository
import com.example.biletflow.data.repositories.EventsApiException
import kotlinx.coroutines.CancellationException

data class EventContext(val id:Int,val title:String?=null,val unavailable:Boolean=false)
suspend fun loadEventContext(repository:EventsRepository,id:Int):EventContext = try {
    EventContext(id,repository.getEvent(id).title)
} catch(error:Exception) {
    if(error is CancellationException) throw error
    EventContext(id,unavailable=(error as? EventsApiException)?.statusCode==404)
}
/** Supplemental only: failure must never replace the surrounding order, QR or support case. [known]: a title the response already carries (Order.event), no request then. */
@Composable
fun EventContextCaption(id:Int,repository:EventsRepository?,known:String?=null) {
    if(known != null) { Text(known); return }
    val context by produceState(EventContext(id),id,repository) {
        value=if(repository==null) EventContext(id) else loadEventContext(repository,id)
    }
    Text(context.title ?: stringResource(if(context.unavailable) Res.string.event_context_unavailable else Res.string.event_context_number,id))
}
