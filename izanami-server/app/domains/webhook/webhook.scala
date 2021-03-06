package domains.webhook

import java.time.LocalDateTime

import akka.Done
import akka.actor.ActorSystem
import domains.Domain.Domain
import domains.events.EventStore
import domains.events.Events.{WebhookCreated, WebhookDeleted, WebhookUpdated}
import domains.webhook.WebhookStore._
import domains.webhook.notifications.WebHooksActor
import domains.{AuthInfo, Domain, Key}
import env.{DbDomainConfig, WebhookConfig}
import play.api.libs.json._
import play.api.libs.ws._
import store.Result.Result
import store._

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

case class Webhook(clientId: WebhookKey,
                   callbackUrl: String,
                   domains: Seq[Domain] = Seq.empty[Domain],
                   patterns: Seq[String] = Seq.empty[String],
                   types: Seq[String] = Seq.empty[String],
                   headers: JsObject = Json.obj(),
                   created: LocalDateTime = LocalDateTime.now(),
                   isBanned: Boolean = false) {
  def isAllowed = Key.isAllowed(clientId) _
}

object Webhook {

  import Domain._
  import play.api.libs.json._
  import playjson.rules._
  import shapeless.syntax.singleton._

  private val reads: Reads[Webhook] = jsonRead[Webhook].withRules(
    'domains ->> orElse(Seq.empty[Domain]) and
      'patterns ->> orElse(Seq.empty[String]) and
      'types ->> orElse(Seq.empty[String]) and
      'headers ->> orElse(Json.obj()) and
      'created ->> orElse(LocalDateTime.now()) and
      'isBanned ->> orElse(false)
  )

  private val writes = Json.writes[Webhook]

  implicit val format = Format(reads, writes)

  def isAllowed(key: WebhookKey)(auth: Option[AuthInfo]) =
    Key.isAllowed(key)(auth)
}

trait WebhookStore extends DataStoreWithTTL[WebhookKey, Webhook]

object WebhookStore {

  type WebhookKey = Key

  sealed trait WebhookMessages

  def apply(jsonStore: JsonDataStore,
            eventStore: EventStore,
            dbConfig: DbDomainConfig,
            webHookConfig: WebhookConfig,
            wsClient: WSClient,
            actorSystem: ActorSystem): WebhookStore = {
    val webhookStore =
      new WebhookStoreImpl(jsonStore, dbConfig, eventStore, actorSystem)
    actorSystem.actorOf(
      WebHooksActor.props(wsClient, eventStore, webhookStore, webHookConfig),
      "webhooks")
    webhookStore
  }
}

class WebhookStoreImpl(jsonStore: JsonDataStore,
                       config: DbDomainConfig,
                       eventStore: EventStore,
                       system: ActorSystem)
    extends WebhookStore {

  import Webhook._
  import WebhookStore._
  import system.dispatcher
  private implicit val s = system
  private implicit val es = eventStore

  private val lockKey = Key.Empty / "batch" / "lock"

  override def create(id: WebhookKey, data: Webhook): Future[Result[Webhook]] =
    jsonStore.create(id, format.writes(data)).to[Webhook].andPublishEvent { r =>
      WebhookCreated(id, r)
    }

  override def createWithTTL(id: WebhookKey,
                             data: Webhook,
                             ttl: FiniteDuration) =
    jsonStore
      .createWithTTL(id, format.writes(data), ttl)
      .to[Webhook]
      .andPublishEvent { r =>
        WebhookCreated(id, r)
      }

  override def update(oldId: WebhookKey,
                      id: WebhookKey,
                      data: Webhook): Future[Result[Webhook]] =
    jsonStore
      .update(oldId, id, format.writes(data))
      .to[Webhook]
      .andPublishEvent { r =>
        WebhookUpdated(id, data, r)
      }

  override def updateWithTTL(oldId: WebhookKey,
                             id: WebhookKey,
                             data: Webhook,
                             ttl: FiniteDuration) =
    jsonStore.updateWithTTL(oldId, id, format.writes(data), ttl).to[Webhook]

  override def delete(id: WebhookKey): Future[Result[Webhook]] =
    jsonStore.delete(id).to[Webhook].andPublishEvent { r =>
      WebhookDeleted(id, r)
    }

  override def deleteAll(patterns: Seq[String]): Future[Result[Done]] =
    jsonStore.deleteAll(patterns)

  override def getById(id: WebhookKey): FindResult[Webhook] =
    JsonFindResult[Webhook](jsonStore.getById(id))

  override def getByIdLike(
      patterns: Seq[String],
      page: Int,
      nbElementPerPage: Int): Future[PagingResult[Webhook]] =
    jsonStore
      .getByIdLike(patterns, page, nbElementPerPage)
      .map(jsons => JsonPagingResult(jsons))

  override def getByIdLike(patterns: Seq[String]): FindResult[Webhook] =
    JsonFindResult[Webhook](jsonStore.getByIdLike(patterns))

  override def count(patterns: Seq[String]): Future[Long] =
    jsonStore.count(patterns)
}
