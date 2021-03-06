package io.apiman.sidekick.watchers

import io.apiman.gateway.engine.beans.Api
import io.apiman.sidekick.appConfig
import io.apiman.sidekick.publishers.ApiPublisher
import io.apiman.sidekick.reader.ApiConfigReader
import io.fabric8.kubernetes.api.model.Service
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientException
import io.fabric8.kubernetes.client.Watcher
import kotlinx.coroutines.*
import mu.KLogging

/**
 *
 * @author Jakub Cechacek
 */
class ApimanWatcher(
    override val k8Client: KubernetesClient,
    val reader: ApiConfigReader<Api>,
    val publisher: ApiPublisher<Api>
) : ApiWatcher {
    companion object : KLogging()

    override suspend fun onStartup() = coroutineScope {
        logger.info { "Performing startup sync" }
        val apimanApis = publisher.fetchPublished()
        val ocpServices = serviceQuery().list().items
        val ocpApis = ocpServices.map { reader.read(it, shallow = true) }

        (apimanApis - ocpApis).forEach { api ->
            launch {
                publisher.retireApi(api)
            }
        }
    }

    private suspend fun resourceAdded(resource: Service) {
        val api = reader.read(resource)
        publisher.publishApi(api)
        logger.info { "[ADDED]  ${resource.metadata.namespace}:${resource.metadata.name}" }
    }

    private suspend fun resourceDeleted(resource: Service) {
        val api = reader.read(resource, shallow = true)
        publisher.retireApi(api)
        logger.info("[DELETED] ${resource.metadata.namespace}:${resource.metadata.name}")
    }
    override fun eventReceived(action: Watcher.Action, resource: Service) = runBlocking {
        logger.debug { "Watcher received ${action} action" }
        when (action) {
            Watcher.Action.ADDED -> resourceAdded(resource)
            Watcher.Action.MODIFIED -> resourceAdded(resource)
            Watcher.Action.DELETED -> resourceDeleted(resource)
            else -> { }
        }
    }

    override fun onClose(cause: KubernetesClientException?) {
        if (cause != null) {
            logger.error { cause }
        }
    }

    private fun serviceQuery() = k8Client
        .services()
        .inAnyNamespace()
        .withLabel(appConfig().discovery.label)!!
}